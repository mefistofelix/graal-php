package graalphp.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static graalphp.runtime.Execution.*;

/** Call-scoped C stacks and closures; PHP callbacks remain on the calling task. */
public final class NativeInvocation implements TruffleObject, AutoCloseable {
    public static final String SOURCE = """
        function native_invoke($call) {
            try {
                __ffi_begin($call);
                while (__ffi_next($call)) __ffi_reply($call, __ffi_callback($call));
                return __ffi_result($call);
            } finally {
                Async\\protect(function() use ($call) {
                    __ffi_abort($call);
                    try { __ffi_join($call); } finally { __ffi_close($call); }
                });
            }
        }
        """;
    private final Activation caller;
    private final NativeAccess access;
    private final CDeclarations.Function function;
    private final long address;
    private final Object[] arguments;
    private final List<NativeCallback> ownedCallbacks = new ArrayList<>();
    private final boolean async;
    private final String pool;
    private final com.oracle.truffle.api.nodes.RootNode location;
    private long handle;
    private int callbackIndex;
    private Object[] callbackArguments;
    private Object result;
    private Object reply;
    private volatile boolean aborting;
    private boolean done, closed;
    private Scheduler.Future event = new Scheduler.Future();
    private Scheduler.Future worker;
    private CompletableFuture<Object> response;

    private NativeInvocation(Activation caller, FfiApi.Binding binding, CDeclarations.Function function, Argument[] values, boolean async, String pool) {
        this.caller = caller; this.function = function; this.async = async;
        this.pool = pool;
        access = caller.request.context.nativeAccess;
        address = access.address(binding.library(), function.name());
        location = ((RootCallTarget) caller.function.target()).getRootNode();
        arguments = new Object[values.length];
        try {
            for (int i = 0; i < values.length; i++) {
                Object value = PhpValues.unwrap(values[i].value());
                var type = function.parameters().get(i).type();
                if (type.callback()) {
                    if (!(value instanceof NativeCallback)) {
                        var callback = new NativeCallback(caller, ObjectModel.callable(caller, value));
                        ownedCallbacks.add(callback); value = callback;
                    }
                } else if (type.string() ? !(value instanceof String) : !(value instanceof Number)) {
                    throw new PhpError("TypeError", "Invalid native argument " + i);
                }
                arguments[i] = value;
            }
        } catch (RuntimeException error) { ownedCallbacks.forEach(NativeCallback::close); throw error; }
    }
    static Object invoke(Activation caller, FfiApi.Binding binding, CDeclarations.Function function,
                         Argument[] values, boolean async, String pool, IndirectCallNode node) {
        var invocation = new NativeInvocation(caller, binding, function, values, async, pool);
        caller.request.resources.add(invocation);
        try {
            return Operations.invokeFunction(caller, caller.request.context.asyncFunction("native_invoke"),
                    new Argument[] { new Argument(invocation, null) }, node);
        } catch (RuntimeException error) { invocation.abort(); invocation.close(); throw error; }
    }
    private static String encode(String nfi) {
        if (nfi.startsWith("(")) {
            int end = nfi.lastIndexOf(')');
            String encoded = encode(nfi.substring(end + 2));
            String params = nfi.substring(1, end);
            if (!params.isEmpty()) for (String p : params.split(",")) encoded += encode(p);
            return "[" + encoded + "]";
        }
        return switch (nfi) {
            case "VOID" -> "v"; case "SINT8" -> "b"; case "UINT8" -> "B";
            case "SINT16" -> "h"; case "UINT16" -> "H"; case "SINT32" -> "i"; case "UINT32" -> "I";
            case "SINT64" -> "l"; case "UINT64" -> "L"; case "FLOAT" -> "f"; case "DOUBLE" -> "d";
            case "STRING" -> "s"; default -> throw new PhpError("FFI\\Exception", "Unsupported bridge ABI " + nfi);
        };
    }
    private Object nativeCall(String name, String signature, Object... args) {
        return access.call("builtin:runtime", "gp_ffi_" + name, signature, args);
    }
    private void checked(String name, String signature, Object... args) {
        if (((Number) nativeCall(name, signature, args)).intValue() != 0) throw new PhpError("FFI\\Exception", "Native bridge " + name + " failed");
    }
    private void create() {
        String signature = encode(function.result().nfi());
        for (var p : function.parameters()) signature += encode(p.type().nfi());
        handle = ((Number) nativeCall("create", "(UINT64,STRING):UINT64", address, signature)).longValue();
        if (handle == 0) throw new PhpError("FFI\\Exception", "Cannot allocate native invocation (maximum 64 parameters)");
        for (int i = 0; i < arguments.length; i++) {
            var type = function.parameters().get(i).type();
            if (!type.callback()) put(i, type.nfi(), arguments[i]);
        }
    }
    private void put(int index, String type, Object value) {
        if (type.equals("STRING")) checked("set_s", "(UINT64,SINT32,STRING):SINT32", handle, index, value);
        else if (type.equals("FLOAT") || type.equals("DOUBLE")) checked("set_d", "(UINT64,SINT32,DOUBLE):SINT32", handle, index, ((Number) value).doubleValue());
        else checked("set_i", "(UINT64,SINT32,SINT64):SINT32", handle, index, type.equals("VOID") ? 0L : ((Number) value).longValue());
    }
    private Object get(int index, String type) {
        return switch (type) {
            case "VOID" -> null;
            case "STRING" -> nativeCall("get_s", "(UINT64,SINT32):STRING", handle, index);
            case "FLOAT", "DOUBLE" -> ((Number) nativeCall("get_d", "(UINT64,SINT32):DOUBLE", handle, index)).doubleValue();
            default -> nativeCall("get_i", "(UINT64,SINT32):SINT64", handle, index);
        };
    }
    private String callbackType() { return function.parameters().get(callbackIndex).type().nfi(); }
    private String callbackResult() { String type = callbackType(); return type.substring(type.lastIndexOf(')') + 2); }
    private boolean step() {
        if (reply != null) { put(-1, callbackResult(), reply); reply = null; }
        int status = ((Number) nativeCall("step", "(UINT64):SINT32", handle)).intValue();
        if (status < 0) throw new PhpError("FFI\\Exception", status == -3
                ? "Call-scoped callbacks must run on the native invocation thread" : "Native stack switch failed: " + status);
        if (status == 0) { result = get(-1, function.result().nfi()); done = true; return false; }
        callbackIndex = status - 1;
        String params = callbackType().substring(1, callbackType().indexOf(')'));
        String[] types = params.isEmpty() ? new String[0] : params.split(",");
        callbackArguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) callbackArguments[i] = get(i, types[i]);
        return true;
    }
    private Object runWorker() {
        try {
            create();
            while (!aborting && step()) {
                CompletableFuture<Object> pending;
                synchronized (this) {
                    if (aborting) break;
                    response = pending = new CompletableFuture<>();
                    event.completion.complete(true);
                }
                reply = TruffleSafepoint.setBlockedThreadInterruptibleFunction(location, f -> {
                    try { return f.get(); }
                    catch (java.util.concurrent.ExecutionException e) { throw new PhpError(e.getCause().toString()); }
                }, pending);
            }
            return null;
        } catch (RuntimeException error) {
            synchronized (this) { event.completion.completeExceptionally(error); }
            throw error;
        } finally {
            if (handle != 0) {
                checked("abort", "(UINT64):SINT32", handle);
                checked("close", "(UINT64):SINT32", handle); handle = 0;
            }
            synchronized (this) { event.completion.complete(false); }
        }
    }
    private synchronized void answer(Object value) {
        String type = callbackResult();
        if (type.equals("VOID")) value = 0L;
        else if (value instanceof Boolean flag) value = flag ? 1L : 0L;
        else if (!(value instanceof Number)) throw new PhpError("TypeError", "Native callback must return a number");
        if (async) { event = new Scheduler.Future(); response.complete(value); response = null; }
        else reply = value;
    }
    private synchronized void abort() {
        aborting = true;
        if (async) { if (response != null) response.complete(0L); }
        else if (handle != 0) checked("abort", "(UINT64):SINT32", handle);
    }
    static Object operation(Activation caller, String name, Object[] values, IndirectCallNode node) {
        var invocation = (NativeInvocation) values[0];
        return switch (name) {
            case "__ffi_begin" -> {
                if (invocation.async) {
                    invocation.worker = caller.request.workers(caller, invocation.pool).submit(caller, invocation::runWorker);
                    // Scope cancellation also visits external jobs. The owning PHP task
                    // must drain this one in its finally, respecting Async\protect.
                    // Interrupting/cancelling the job separately can strand its event waiter.
                    invocation.worker.cancelAction = () -> {};
                    invocation.worker.observed = true;
                } else invocation.create();
                yield null;
            }
            case "__ffi_next" -> invocation.async ? Scheduler.awaitValue(caller, invocation.event, null) : invocation.step();
            case "__ffi_callback" -> ((NativeCallback) invocation.arguments[invocation.callbackIndex]).invokeResumable(caller, invocation.callbackArguments, node);
            case "__ffi_reply" -> { invocation.answer(values[1]); yield null; }
            case "__ffi_result" -> invocation.result;
            case "__ffi_abort" -> { invocation.abort(); yield null; }
            case "__ffi_join" -> invocation.worker == null ? null : Scheduler.awaitValue(caller, invocation.worker, null);
            case "__ffi_close" -> { invocation.close(); yield null; }
            default -> throw new PhpError("Unknown native bridge operation");
        };
    }
    @Override public void close() {
        if (closed) return;
        if (worker != null && !worker.completion.isDone()) throw new PhpError("Native worker is still active; preserving callback roots");
        if (!async && handle != 0) { abort(); checked("close", "(UINT64):SINT32", handle); handle = 0; }
        closed = true;
        ownedCallbacks.forEach(NativeCallback::close);
        caller.request.resources.remove(this);
    }
}
