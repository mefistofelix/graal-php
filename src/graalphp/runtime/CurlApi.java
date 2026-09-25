package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.*;
import static graalphp.runtime.Execution.*;

/** PHP curl functions backed by the statically linked provider and the libuv multi/socket reactor. */
public final class CurlApi {
    private CurlApi() {}
    public static final Map<String, Long> CONSTANTS = Map.ofEntries(
        Map.entry("CURLOPT_URL", 10002L), Map.entry("CURLOPT_RETURNTRANSFER", 19913L),
        Map.entry("CURLOPT_CAINFO", 10065L), Map.entry("CURLOPT_USERAGENT", 10018L),
        Map.entry("CURLOPT_HTTPHEADER", 10023L), Map.entry("CURLOPT_FOLLOWLOCATION", 52L),
        Map.entry("CURLOPT_MAXREDIRS", 68L), Map.entry("CURLOPT_TIMEOUT_MS", 155L),
        Map.entry("CURLOPT_CONNECTTIMEOUT_MS", 156L), Map.entry("CURLOPT_TIMEOUT", 13L),
        Map.entry("CURLOPT_CONNECTTIMEOUT", 78L), Map.entry("CURLOPT_SSL_VERIFYPEER", 64L),
        Map.entry("CURLOPT_SSL_VERIFYHOST", 81L), Map.entry("CURLOPT_HTTP_VERSION", 84L),
        Map.entry("CURLOPT_ACCEPT_ENCODING", 10102L), Map.entry("CURLOPT_ENCODING", 10102L),
        Map.entry("CURLOPT_PROXY", 10004L), Map.entry("CURLOPT_NOPROXY", 10177L),
        Map.entry("CURLOPT_CUSTOMREQUEST", 10036L), Map.entry("CURLOPT_POSTFIELDS", 10015L),
        Map.entry("CURLOPT_POST", 47L), Map.entry("CURLOPT_HTTPGET", 80L),
        Map.entry("CURLINFO_RESPONSE_CODE", 2097154L), Map.entry("CURLINFO_HTTP_CODE", 2097154L),
        Map.entry("CURLINFO_HTTP_VERSION", 2097198L), Map.entry("CURLINFO_EFFECTIVE_URL", 1048577L),
        Map.entry("CURLINFO_CONTENT_TYPE", 1048594L),
        Map.entry("CURL_HTTP_VERSION_1_1", 2L), Map.entry("CURL_HTTP_VERSION_2_0", 3L));
    public static final class Handle extends PhpValues.HeapNode implements TruffleObject, AutoCloseable {
        final Request request;
        final NativeAccess nativeAccess;
        final int provider;
        long pointer;
        volatile boolean busy;
        Scheduler.Future pending;
        boolean returnTransfer;
        volatile int errno;
        volatile Object body;
        CurlMultiApi.Handle multi;
        boolean multiOutput;
        Handle(Request request, int provider) {
            super(request.heap);
            this.request = request;
            this.nativeAccess = request.context.nativeAccess;
            this.provider = provider;
            pointer = ((Number) nativeAccess.call("builtin:curl", "gp_curl_create_provider", "(SINT32):UINT64", new Object[] {provider})).longValue();
            if (pointer == 0) throw new PhpError("Could not allocate cURL handle");
        }
        Object call(String function, String signature, Object... values) {
            if (pointer == 0) throw new PhpError("Error", "cURL handle is closed");
            Object[] args = new Object[values.length + 1]; args[0] = pointer;
            System.arraycopy(values, 0, args, 1, values.length);
            return nativeAccess.call("builtin:curl", "gp_curl_" + function, signature, args);
        }
        byte[] readBody() {
            if (pointer == 0) throw new PhpError("Error", "cURL handle is closed");
            if (org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode()) return NativeBuiltins.curlBody(pointer);
            int length = ((Number) call("size", "(UINT64):SINT32")).intValue();
            byte[] bytes = new byte[length];
            call("copy", "(UINT64,[UINT8],SINT32):VOID", bytes, length);
            return bytes;
        }
        void idle() {
            if (pointer == 0) throw new PhpError("Error", "cURL handle is closed");
            if (busy) throw new PhpError("Error", "cURL handle is in use by another coroutine");
            if (multi != null) throw new PhpError("Error", "Remove the cURL handle from its multi before reconfiguring or executing it");
        }
        @Override void children(java.util.function.Consumer<PhpValues.HeapNode> visit) {}
        @Override void discard() { close(); }
        @Override public void close() {
            if (pointer == 0) return;
            call("close", "(UINT64):VOID");
            pointer = 0;
            request.resources.remove(this);
        }
    }
    /** Owns the handle until native detachment and guest resumption. Shutdown also
     * closes this resource after the reactor has drained all pending operations. */
    private static final class Transfer implements AutoCloseable {
        final Activation caller;
        final Handle handle;
        boolean closed;
        Transfer(Activation caller, Handle handle) {
            this.caller = caller; this.handle = handle;
            PhpValues.retain(handle);
            caller.request.resources.add(this);
            handle.body = false;
            handle.busy = true;
        }
        Object finish(Object result) {
            try {
                if (result instanceof Failure) return result;
                handle.errno = result == null ? 0 : ((Number) result).intValue();
                if (handle.errno != 0) return false;
                handle.body = PhpString.fromBytes(handle.readBody());
                if (handle.returnTransfer) return handle.body;
                caller.request.output.writeBytes(PhpString.bytes(handle.body));
                return true;
            } finally { close(); }
        }
        @Override public void close() {
            if (closed) return;
            if (handle.pending != null) {
                // Owner-thread cURL cancellation removes the transfer synchronously.
                // Never expose a reusable handle while C still owns its operation.
                if (!handle.pending.completion.isDone()) caller.request.scheduler.cancel(handle.pending);
                if (!handle.pending.completion.isDone()) throw new PhpError("cURL transfer did not detach during cancellation");
                if (!handle.pending.completion.isCompletedExceptionally()) {
                    Object status = handle.pending.completion.getNow(null);
                    handle.errno = status == null ? 0 : ((Number) status).intValue();
                }
            }
            closed = true;
            handle.pending = null;
            handle.busy = false;
            caller.request.resources.remove(this);
            try { PhpValues.release(handle); }
            finally { caller.request.heap.collectCycles(); }
        }
    }
    private static Object execute(Activation caller, Handle handle) {
        handle.idle();
        if (caller.synchronousCallback) throw new PhpError("Cannot suspend a native callback");
        var transfer = new Transfer(caller, handle);
        try {
            handle.pending = caller.request.libuv(caller).submit("gp_reactor_curl", ",UINT64", handle.pointer);
            handle.pending.observed = true;
            if (handle.pending.completion.isDone()) return transfer.finish(Scheduler.result(handle.pending));
            return new Scheduler.Await(handle.pending, null, transfer::finish);
        } catch (RuntimeException error) { transfer.close(); throw error; }
    }
    static Handle handle(Object value) {
        if (!(value instanceof Handle handle)) throw new PhpError("TypeError", "Expected CurlHandle");
        return handle;
    }
    static int provider(Request request, Object value) {
        String name = value == null ? request.context.environment.getEnvironment().getOrDefault("GRAALPHP_CURL_PROVIDER", "impersonate") : Operations.string(value);
        return switch (name) {
            case "standard" -> 0;
            case "impersonate" -> 1;
            default -> throw new PhpError("ValueError", "cURL provider must be standard or impersonate");
        };
    }
    public static Object function(Activation caller, String name, Object[] values, IndirectCallNode call) {
        if (!name.startsWith("curl_") && !name.startsWith("__curl_")) return AsyncApi.UNHANDLED;
        if (name.equals("curl_init")) {
            if (values.length > 2) throw new PhpError("Too many curl_init arguments");
            var handle = new Handle(caller.request, provider(caller.request, values.length > 1 ? values[1] : null));
            caller.request.resources.add(handle);
            if (values.length >= 1 && values[0] != null) option(handle, 10002, values[0]);
            return caller.track(PhpValues.own(handle));
        }
        if (name.equals("curl_version")) {
            if (values.length > 1) throw new PhpError("Too many curl_version arguments");
            int provider = provider(caller.request, values.length == 0 ? null : values[0]);
            String version = Operations.string(caller.request.context.nativeAccess.call("builtin:curl", "gp_curl_version_provider", "(SINT32):STRING", new Object[] {provider}));
            return NetworkApi.array(caller, Map.of("version", version, "provider", provider == 0 ? "standard" : "curl-impersonate/2.2.2"));
        }
        if (values.length == 0) throw new PhpError("Missing cURL handle");
        Handle handle = handle(values[0]);
        switch (name) {
            case "curl_setopt":
                require(values, 3); handle.idle();
                return option(handle, Operations.number(values[1]).intValue(), values[2]);
            case "curl_setopt_array":
                require(values, 2); handle.idle();
                for (Object key : PhpValues.keys(values[1])) {
                    Object value = PhpValues.element(values[1], key);
                    try { if (!option(handle, Operations.number(key).intValue(), PhpValues.unwrap(value))) return false; }
                    finally { PhpValues.drop(value); }
                }
                return true;
            case "curl_impersonate":
                if (values.length < 2 || values.length > 3) throw new PhpError("curl_impersonate expects handle, profile and optional default_headers");
                handle.idle();
                handle.errno = ((Number) handle.call("impersonate", "(UINT64,STRING,SINT32):SINT32", Operations.string(values[1]), values.length < 3 || Operations.truth(values[2]) ? 1 : 0)).intValue();
                return handle.errno == 0;
            case "curl_exec":
                require(values, 1); return execute(caller, handle);
            case "curl_errno": require(values, 1); return (long) handle.errno;
            case "curl_error":
                require(values, 1);
                if (handle.multi != null) return CurlMultiApi.query(caller, handle, 10, 0, call);
                handle.idle(); return handle.call("error", "(UINT64):STRING");
            // PHP 8 retains CurlHandle identity; native cleanup follows guest ownership.
            case "curl_close": require(values, 1); return null;
            case "curl_getinfo":
                if (handle.multi != null) {
                    if (values.length == 1) return Operations.invokeFunction(caller, caller.request.context.asyncFunction("curl_multi_getinfo"),
                            new Argument[] {new Argument(handle.multi, null), new Argument(handle, null)}, call);
                    require(values, 2);
                    int option = Operations.number(values[1]).intValue();
                    return CurlMultiApi.query(caller, handle, CurlMultiApi.infoOperation(option), option, call);
                }
                handle.idle();
                if (values.length == 1) return NetworkApi.array(caller, Map.of("http_code", info(handle, 2097154), "http_version", info(handle, 2097198),
                        "url", info(handle, 1048577), "content_type", info(handle, 1048594)));
                require(values, 2);
                return info(handle, Operations.number(values[1]).intValue());
            default: throw new PhpError("Unimplemented cURL function " + name);
        }
    }
    private static Object info(Handle handle, int info) {
        return switch (info) {
            case 2097154, 2097198 -> handle.call("info_long", "(UINT64,SINT32):SINT64", info);
            case 1048577, 1048594 -> handle.call("info_string", "(UINT64,SINT32):STRING", info);
            default -> throw new PhpError("Unsupported curl_getinfo option " + info);
        };
    }
    private static boolean option(Handle handle, int option, Object value) {
        if (option == 19913) { handle.returnTransfer = Operations.truth(value); return true; }
        int status;
        switch (option) {
            case 10002, 10065, 10018, 10102, 10004, 10177, 10036, 10015 -> {
                String text = Operations.string(value);
                if (text.indexOf(0) >= 0) throw new PhpError("ValueError", "This cURL string option does not support NUL");
                status = ((Number) handle.call("option_string", "(UINT64,SINT32,STRING):SINT32", option == 10015 ? 10165 : option, text)).intValue();
            }
            case 52, 68, 155, 156, 13, 78, 64, 81, 84, 47, 80 -> {
                long number = Operations.number(value).longValue();
                if (number < 0 || number > Integer.MAX_VALUE) throw new PhpError("ValueError", "cURL option outside supported range");
                status = ((Number) handle.call("option_long", "(UINT64,SINT32,SINT64):SINT32", option, number)).intValue();
            }
            case 10023 -> {
                status = ((Number) handle.call("header", "(UINT64,STRING):SINT32", "")).intValue();
                for (Object key : PhpValues.keys(value)) {
                    Object item = PhpValues.element(value, key);
                    try {
                        String header = Operations.string(item);
                        if (header.contains("\r") || header.contains("\n") || header.indexOf(0) >= 0) throw new PhpError("Invalid cURL header");
                        status = ((Number) handle.call("header", "(UINT64,STRING):SINT32", header)).intValue();
                        if (status != 0) break;
                    } finally { PhpValues.drop(item); }
                }
            }
            default -> throw new PhpError("ValueError", "Unsupported CURLOPT value " + option);
        }
        handle.errno = status;
        return status == 0;
    }
    private static void require(Object[] values, int count) { if (values.length != count) throw new PhpError("Invalid cURL argument count"); }
}
