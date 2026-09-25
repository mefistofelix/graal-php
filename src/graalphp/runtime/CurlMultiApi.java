package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static graalphp.runtime.Execution.*;

/** Public PHP multi handles. All CURLM operations run on the native loop owner. */
public final class CurlMultiApi {
    private CurlMultiApi() {}
    public static final String SOURCE = """
        function curl_multi_command($multi, $operation, $value = 0, $argument = 0) {
            $mutex = __cm_mutex($multi);
            $mutex->lock();
            try {
                __protect_enter();
                $pending = null;
                try {
                    $pending = __cm_submit($multi, $operation, $value, $argument);
                    __cm_wait($pending);
                    return __cm_finish($pending);
                } finally {
                    if ($pending !== null) __cm_end($pending);
                    __protect_leave();
                }
            } finally { $mutex->unlock(); }
        }
        function curl_multi_execute($multi, &$running) {
            $state = __cm_command($multi, 3);
            $running = $state[1];
            return $state[0];
        }
        function curl_multi_read($multi, &$queued = null) {
            $state = __cm_command($multi, 4);
            if ($state === false) return false;
            $queued = $state['queued'];
            unset($state['queued']);
            return $state;
        }
        function curl_multi_select_wait($multi, $timeout) {
            $pending = __cm_submit($multi, 5, $timeout, 0);
            try {
                __cm_wait($pending);
                return __cm_finish($pending);
            } finally {
                Async\\protect(function() use ($pending) { __cm_wait($pending); });
            }
        }
        function curl_multi_getinfo($multi, $handle) {
            return [
                'http_code' => __cm_command($multi, 11, $handle, 2097154),
                'http_version' => __cm_command($multi, 11, $handle, 2097198),
                'url' => __cm_command($multi, 12, $handle, 1048577),
                'content_type' => __cm_command($multi, 12, $handle, 1048594)
            ];
        }
        """;
    public static final Map<String, Long> CONSTANTS = Map.ofEntries(
        Map.entry("CURLM_OK", 0L), Map.entry("CURLM_BAD_HANDLE", 1L), Map.entry("CURLM_BAD_EASY_HANDLE", 2L),
        Map.entry("CURLM_OUT_OF_MEMORY", 3L), Map.entry("CURLM_INTERNAL_ERROR", 4L),
        Map.entry("CURLM_BAD_SOCKET", 5L), Map.entry("CURLM_UNKNOWN_OPTION", 6L),
        Map.entry("CURLM_ADDED_ALREADY", 7L), Map.entry("CURLM_CALL_MULTI_PERFORM", -1L),
        Map.entry("CURLMSG_DONE", 1L), Map.entry("CURLE_OK", 0L), Map.entry("CURLE_UNSUPPORTED_PROTOCOL", 1L),
        Map.entry("CURLE_OPERATION_TIMEDOUT", 28L), Map.entry("CURLE_ABORTED_BY_CALLBACK", 42L),
        Map.entry("CURLMOPT_PIPELINING", 3L), Map.entry("CURLMOPT_MAXCONNECTS", 6L),
        Map.entry("CURLMOPT_MAX_HOST_CONNECTIONS", 7L), Map.entry("CURLMOPT_MAX_PIPELINE_LENGTH", 8L),
        Map.entry("CURLMOPT_CONTENT_LENGTH_PENALTY_SIZE", 30009L), Map.entry("CURLMOPT_CHUNK_LENGTH_PENALTY_SIZE", 30010L),
        Map.entry("CURLMOPT_MAX_TOTAL_CONNECTIONS", 13L), Map.entry("CURLMOPT_MAX_CONCURRENT_STREAMS", 16L),
        Map.entry("CURLPIPE_NOTHING", 0L), Map.entry("CURLPIPE_HTTP1", 1L), Map.entry("CURLPIPE_MULTIPLEX", 2L));

    public static final class Handle extends PhpValues.HeapNode implements TruffleObject, AutoCloseable {
        final Request request;
        final LibuvReactor reactor;
        final long group;
        final int provider;
        final AsyncMutex mutex = new AsyncMutex();
        final LinkedHashMap<Long, CurlApi.Handle> attached = new LinkedHashMap<>();
        int errno;
        boolean disposed;
        Handle(Activation caller, int provider) {
            super(caller.request.heap);
            request = caller.request;
            reactor = request.libuv(caller);
            this.provider = provider;
            group = reactor.allocateCurlGroup() * 2 + provider;
        }
        void detach(CurlApi.Handle easy) {
            attached.values().remove(easy);
            easy.multi = null;
            PhpValues.release(easy);
        }
        @Override void children(java.util.function.Consumer<PhpValues.HeapNode> visit) { attached.values().forEach(visit); }
        @Override void discard() {
            if (disposed) return;
            reactor.closeCurlGroup(group);
            disposed = true;
            attached.values().forEach(easy -> easy.multi = null);
            attached.clear(); // Heap traversal has already released the child edges.
            request.resources.remove(this);
        }
        @Override public void close() {
            if (disposed) return;
            reactor.closeCurlGroup(group);
            disposed = true;
            for (var easy : List.copyOf(attached.values())) detach(easy);
            request.resources.remove(this);
        }
    }
    private static final class Operation implements TruffleObject {
        final Handle multi;
        final int operation;
        final CurlApi.Handle easy;
        final Scheduler.Future raw;
        final boolean reserved;
        final Scheduler.Future ready = new Scheduler.Future();
        Operation(Handle multi, int operation, CurlApi.Handle easy, Scheduler.Future raw, boolean reserved) {
            this.multi = multi; this.operation = operation; this.easy = easy; this.raw = raw;
            this.reserved = reserved;
            ready.cancelOnAbort = operation == 5;
            ready.cancelAction = raw.cancelAction;
            raw.completion.whenComplete((value, error) -> {
                if (error == null) ready.completion.complete(null);
                else ready.completion.completeExceptionally(error);
            });
        }
    }
    private static Handle multi(Object value) {
        if (!(PhpValues.unwrap(value) instanceof Handle multi)) throw new PhpError("TypeError", "Expected CurlMultiHandle");
        if (multi.disposed) throw new PhpError("Error", "CurlMultiHandle is closed");
        return multi;
    }
    private static Argument[] args(String name, Argument[] arguments, int required, String... names) {
        return CallArguments.builtin(name, arguments, List.of(names), required);
    }
    private static Object invoke(Activation caller, String function, Argument[] args, IndirectCallNode call) {
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction(function), args, call);
    }
    private static Object command(Activation caller, Handle multi, int operation, Object value, long argument, IndirectCallNode call) {
        return invoke(caller, "curl_multi_command", new Argument[] {new Argument(multi, null), new Argument((long) operation, null),
            new Argument(value, null), new Argument(argument, null)}, call);
    }
    static Object query(Activation caller, CurlApi.Handle easy, int operation, long option, IndirectCallNode call) {
        return command(caller, easy.multi, operation, easy, option, call);
    }
    static int infoOperation(int option) {
        return switch (option) {
            case 2097154, 2097198 -> 11;
            case 1048577, 1048594 -> 12;
            default -> throw new PhpError("Unsupported curl_getinfo option " + option);
        };
    }
    public static Object function(Activation caller, String name, Argument[] arguments, IndirectCallNode call) {
        if (!name.startsWith("curl_multi_") && !name.startsWith("__cm_")) return AsyncApi.UNHANDLED;
        if (name.equals("__cm_command")) return invoke(caller, "curl_multi_command", arguments, call);
        if (name.startsWith("__cm_")) {
            Object[] values = Arrays.stream(arguments).map(arg -> PhpValues.unwrap(arg.value())).toArray();
            return switch (name) {
                case "__cm_mutex" -> multi(values[0]).mutex;
                case "__cm_submit" -> submit(caller, multi(values[0]), Operations.number(values[1]).intValue(), values[2], Operations.number(values[3]).longValue());
                case "__cm_wait" -> Scheduler.awaitValue(caller, ((Operation) values[0]).ready, null);
                case "__cm_finish" -> finish(caller, (Operation) values[0]);
                case "__cm_end" -> {
                    var pending = (Operation) values[0];
                    if (pending.reserved) pending.easy.busy = false;
                    yield null;
                }
                default -> throw new PhpError("Unknown cURL multi operation");
            };
        }
        switch (name) {
            case "curl_multi_init": {
                var ordered = CallArguments.builtin(name, arguments, List.of("provider"), 0, (Object) null);
                var multi = new Handle(caller, CurlApi.provider(caller.request, ordered[0].value()));
                caller.request.resources.add(multi);
                return caller.track(PhpValues.own(multi));
            }
            case "curl_multi_strerror": {
                var ordered = args(name, arguments, 1, "error_code");
                int code = Operations.number(ordered[0].value()).intValue();
                return caller.request.context.nativeAccess.call("builtin:curl", "gp_curl_multi_strerror", "(SINT32):STRING", new Object[] {code});
            }
            case "curl_multi_getcontent": {
                var easy = CurlApi.handle(PhpValues.unwrap(args(name, arguments, 1, "handle")[0].value()));
                if (!easy.returnTransfer) return null;
                if (easy.multi != null) return query(caller, easy, 9, 0, call);
                easy.idle();
                return body(easy);
            }
            case "curl_multi_exec":
                return invoke(caller, "curl_multi_execute", args(name, arguments, 2, "multi_handle", "still_running"), call);
            case "curl_multi_info_read": {
                // Preserve omission of the optional by-reference argument.
                var ordered = CallArguments.builtin(name, arguments, List.of("multi_handle", "queued_messages"), 1, (Object) null);
                if (ordered[1].location() == null && ordered[1].value() == null && arguments.length == 1)
                    ordered = new Argument[] {ordered[0]};
                return invoke(caller, "curl_multi_read", ordered, call);
            }
            case "curl_multi_select": {
                var ordered = CallArguments.builtin(name, arguments, List.of("multi_handle", "timeout"), 1, 1.0);
                multi(ordered[0].value());
                double timeout = Operations.number(ordered[1].value()).doubleValue();
                if (!Double.isFinite(timeout) || timeout < 0 || timeout > Integer.MAX_VALUE / 1000.0)
                    throw new PhpError("ValueError", "curl_multi_select timeout is outside the supported range");
                return invoke(caller, "curl_multi_select_wait", new Argument[] {ordered[0], new Argument((long) (timeout * 1000), null)}, call);
            }
            case "curl_multi_add_handle": case "curl_multi_remove_handle": {
                var ordered = args(name, arguments, 2, "multi_handle", "handle");
                var multi = multi(ordered[0].value());
                var easy = CurlApi.handle(PhpValues.unwrap(ordered[1].value()));
                return command(caller, multi, name.equals("curl_multi_add_handle") ? 1 : 2, easy, 0, call);
            }
            case "curl_multi_close": case "curl_multi_errno": case "curl_multi_get_handles": {
                var multi = multi(args(name, arguments, 1, "multi_handle")[0].value());
                if (name.equals("curl_multi_close")) return command(caller, multi, 7, 0L, 0, call);
                if (name.equals("curl_multi_errno")) return (long) multi.errno;
                var result = caller.locals.variable(caller.locals.emptyArray());
                for (var easy : multi.attached.values()) result.append().set(easy);
                return caller.track(PhpValues.own(result.read()));
            }
            case "curl_multi_setopt": {
                var ordered = args(name, arguments, 3, "multi_handle", "option", "value");
                var multi = multi(ordered[0].value());
                long option = Operations.number(ordered[1].value()).longValue();
                if (!Set.of(3L, 6L, 7L, 8L, 30009L, 30010L, 13L, 16L).contains(option)) {
                    multi.errno = 6;
                    throw new PhpError("ValueError", "Unsupported CURLMOPT value " + option);
                }
                long value = Operations.number(ordered[2].value()).longValue();
                if (option < 30000 && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE))
                    throw new PhpError("ValueError", "cURL multi option outside supported range");
                return command(caller, multi, 6, option, value, call);
            }
            default: throw new PhpError("Unimplemented cURL multi function " + name);
        }
    }
    private static Operation submit(Activation caller, Handle multi, int operation, Object value, long argument) {
        if (caller.synchronousCallback) throw new PhpError("Cannot suspend a native callback");
        CurlApi.Handle easy = value instanceof CurlApi.Handle handle ? handle : null;
        long nativeValue = easy == null ? Operations.number(value).longValue() : easy.pointer;
        Long immediate = null;
        boolean reserve = false;
        if (operation == 1) {
            if (easy == null) throw new PhpError("TypeError", "Expected CurlHandle");
            if (easy.provider != multi.provider) immediate = 2L;
            else if (easy.multi != null) immediate = 7L;
            else {
                easy.idle();
                easy.busy = true;
                reserve = true;
            }
        } else if (operation == 2 && easy.multi != multi) {
            immediate = easy.multi == null ? 0L : 2L;
        } else if (operation >= 9 && easy.multi != multi) {
            throw new PhpError("Error", "cURL handle was removed while waiting for the multi");
        }
        try {
            Scheduler.Future raw;
            if (immediate != null) {
                raw = new Scheduler.Future();
                raw.completion.complete(immediate);
            } else raw = multi.reactor.submit("gp_reactor_curl_multi", ",SINT64,SINT32,UINT64,SINT64", multi.group, operation, nativeValue, argument);
            return new Operation(multi, operation, easy, raw, reserve);
        } catch (RuntimeException error) {
            if (reserve) easy.busy = false;
            throw error;
        }
    }
    private static ByteBuffer buffer(Object value) { return ByteBuffer.wrap((byte[]) value).order(ByteOrder.nativeOrder()); }
    private static Object body(CurlApi.Handle easy) {
        return PhpString.fromBytes(easy.readBody());
    }
    private static void completed(Activation caller, CurlApi.Handle easy) {
        if (easy.multiOutput) return;
        easy.multiOutput = true;
        if (!easy.returnTransfer) caller.request.output.writeBytes(PhpString.bytes(body(easy)));
    }
    private static Object finish(Activation caller, Operation pending) {
        Object raw = pending.raw.completion.join();
        var multi = pending.multi;
        var easy = pending.easy;
        switch (pending.operation) {
            case 1: {
                int status = raw == null ? 0 : ((Number) raw).intValue();
                if (status == 0) {
                    easy.busy = false;
                    easy.multi = multi;
                    easy.multiOutput = false;
                    easy.errno = 0;
                    multi.attached.put(easy.pointer, easy);
                    PhpValues.retain(easy);
                } else if (easy.multi == null) easy.busy = false;
                multi.errno = status;
                return (long) status;
            }
            case 2: {
                int status = raw == null ? 0 : ((Number) raw).intValue();
                if (status == 0 && easy.multi == multi) multi.detach(easy);
                multi.errno = status;
                return (long) status;
            }
            case 3: {
                var data = buffer(raw);
                multi.errno = (int) data.getLong();
                long running = data.getLong();
                while (data.hasRemaining()) {
                    var done = multi.attached.get(data.getLong());
                    data.getLong(); // curl_errno is published by info_read, as in PHP.
                    completed(caller, done);
                }
                var result = caller.locals.variable(caller.locals.emptyArray());
                result.append().set((long) multi.errno);
                result.append().set(running);
                return caller.track(PhpValues.own(result.read()));
            }
            case 4: {
                if (raw == null) return false;
                var data = buffer(raw);
                var done = multi.attached.get(data.getLong());
                done.errno = (int) data.getLong();
                completed(caller, done);
                var result = caller.locals.variable(caller.locals.emptyArray());
                result.element("msg").set(1L);
                result.element("result").set((long) done.errno);
                result.element("handle").set(done);
                result.element("queued").set(data.getLong());
                return caller.track(PhpValues.own(result.read()));
            }
            case 5: {
                long result = raw == null ? 0L : ((Number) raw).longValue();
                int status = (int) (result >>> 32);
                if (status != 0) multi.errno = status;
                return status == 0 ? result & 0xffffffffL : -1L;
            }
            case 6:
                multi.errno = raw == null ? 0 : ((Number) raw).intValue();
                return multi.errno == 0;
            case 7:
                for (var attached : List.copyOf(multi.attached.values())) multi.detach(attached);
                return null;
            case 9: case 10: case 12: return PhpString.fromBytes((byte[]) raw);
            case 11: return raw == null ? 0L : raw;
            default: throw new PhpError("Unknown cURL multi result");
        }
    }
}
