package graalphp.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import static graalphp.runtime.Execution.*;

/** Synchronous callback execution is marshalled to its owning request dispatcher. */
@ExportLibrary(InteropLibrary.class)
public final class NativeCallback implements TruffleObject, AutoCloseable {
    private final Activation owner;
    private final ObjectModel.Invocation invocation;
    private final Object receiver;
    private final Object environment;
    private final Thread thread = Thread.currentThread();
    private volatile boolean closed;
    public NativeCallback(Activation owner, ObjectModel.Invocation invocation) {
        this.owner = owner;
        this.invocation = invocation;
        receiver = PhpValues.own(invocation.receiver());
        environment = PhpValues.own(invocation.environment());
    }
    Object invokeResumable(Activation caller, Object[] values, com.oracle.truffle.api.nodes.IndirectCallNode call) {
        if (closed) throw new PhpError("Callback outlived its request");
        var arguments = java.util.Arrays.stream(values).map(v -> new Argument(v, null)).toArray(Argument[]::new);
        try {
            var child = new Activation(caller.request, invocation.function(), caller.task, false, arguments);
            ObjectModel.bind(child, invocation);
            return Operations.executeChild(caller, child, call);
        } finally { for (var argument : arguments) argument.close(); }
    }
    @ExportMessage boolean isExecutable() { return !closed; }
    @ExportMessage @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object execute(Object[] values) throws UnsupportedMessageException {
        if (closed) throw new PhpError("Callback outlived its request");
        if (Thread.currentThread() != thread) {
            Object[] copied = values.clone();
            var result = owner.request.scheduler.dispatch(() -> executeLocal(copied));
            var location = ((com.oracle.truffle.api.RootCallTarget) owner.function.target()).getRootNode();
            return com.oracle.truffle.api.TruffleSafepoint.setBlockedThreadInterruptibleFunction(location,
                    NativeCallback::waitForResult, result);
        }
        return executeLocal(values);
    }
    private static Object waitForResult(java.util.concurrent.CompletableFuture<Object> future) throws InterruptedException {
        try { return future.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException error) { throw new PhpError("Callback dispatcher timed out; invoke blocking native code with ffi_call_async"); }
        catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof PhpError php) throw php;
            throw new PhpError(error.getCause().toString());
        }
    }
    private Object executeLocal(Object[] values) throws UnsupportedMessageException {
        if (closed) throw new PhpError("Callback outlived its request");
        var arguments = new Argument[values.length];
        var interop = InteropLibrary.getUncached();
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (interop.fitsInLong(value)) value = interop.asLong(value);
            else if (interop.fitsInDouble(value)) value = interop.asDouble(value);
            else if (interop.isString(value)) value = interop.asString(value);
            arguments[i] = new Argument(value, null);
        }
        try (var activation = new Activation(owner.request, invocation.function(), owner.task, false, arguments)) {
            ObjectModel.bind(activation, invocation);
            activation.synchronousCallback = true;
            Object result = invocation.function().target().call(activation);
            if (result instanceof ContinuationResult) throw new PhpError("A synchronous native callback cannot suspend");
            Object value = PhpValues.unwrap(result);
            if (!(value instanceof Number || value instanceof String || value instanceof Boolean)) throw new PhpError("Callback must return a scalar");
            return value;
        } finally { for (var argument : arguments) argument.close(); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        PhpValues.drop(receiver);
        PhpValues.drop(environment);
    }
}
