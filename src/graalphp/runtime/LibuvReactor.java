package graalphp.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.util.HashMap;
import java.util.Map;
import static graalphp.runtime.Execution.*;

/** PHP, libuv and native completions share one owner. Embedded hosts use an OS-readiness waiter only. */
public final class LibuvReactor implements AutoCloseable {
    private final NativeAccess nativeAccess;
    private final com.oracle.truffle.api.nodes.RootNode location;
    private final Map<Long, Scheduler.Future> pending = new HashMap<>();
    private final Thread owner = Thread.currentThread();
    private final Object wakeupLock = new Object();
    private final Completion completion;
    private final long handle;
    private final Thread poller;
    private long nextToken;
    private long nextCurlGroup;
    private volatile boolean closed;

    public LibuvReactor(Activation caller) {
        nativeAccess = caller.request.context.nativeAccess;
        nativeAccess.bind("builtin:runtime", "gp_bytes_copy", "(UINT64,[UINT8],SINT32):VOID");
        completion = new Completion(pending, nativeAccess, owner);
        location = ((RootCallTarget) caller.function.target()).getRootNode();
        handle = ((Number) nativeAccess.call("builtin:runtime", "gp_reactor_create",
                "(ENV,(SINT64,SINT32,UINT64,SINT64):VOID,SINT32):UINT64",
                new Object[] {completion, caller.request.hosted ? 1 : 0})).longValue();
        if (handle == 0) throw new PhpError("Could not initialize libuv reactor");
        if (caller.request.hosted) {
            poller = caller.request.context.environment.newTruffleThreadBuilder(() -> {
                try {
                    while (true) {
                        int status = ((Number) nativeAccess.call("builtin:runtime", "gp_reactor_embed_wait", "(UINT64):SINT32", new Object[] {handle})).intValue();
                        if (status == 0) return;
                        if (status < 0) throw new PhpError("libuv backend wait failed: " + status);
                        caller.request.scheduler.notifyHost();
                    }
                } catch (PhpError error) {
                    caller.request.scheduler.enqueue(() -> { throw error; });
                }
            }).build();
            poller.setName("graalphp-libuv-host-poller");
            poller.start();
        } else poller = null;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new PhpError("libuv must execute on the PHP owner thread");
    }
    public void step(long timeoutNanos) {
        checkOwner();
        if (closed) return;
        long milliseconds = timeoutNanos <= 0 ? 0 : 1 + (timeoutNanos - 1) / 1_000_000;
        nativeAccess.call("builtin:runtime", "gp_reactor_step", "(UINT64,UINT64):SINT32", new Object[] {handle, milliseconds});
    }
    public void armHost() {
        checkOwner();
        if (!closed && poller != null)
            nativeAccess.call("builtin:runtime", "gp_reactor_embed_arm", "(UINT64):VOID", new Object[] {handle});
    }
    /** Only foreign threads wake the loop; owner submissions never pay for a syscall. */
    public void wakeup() {
        if (Thread.currentThread() == owner) return;
        synchronized (wakeupLock) {
            if (!closed) nativeAccess.call("builtin:runtime", "gp_reactor_wakeup", "(UINT64):SINT32", new Object[] {handle});
        }
    }
    public Scheduler.Future timer(long milliseconds) {
        if (milliseconds < 0) throw new PhpError("Negative timer duration");
        return submit("gp_reactor_timer", ",UINT64", milliseconds);
    }
    public Scheduler.Future submit(String function, String parameters, Object... values) {
        checkOwner();
        if (closed) throw new PhpError("libuv reactor is closed");
        long token = ++nextToken;
        var future = new Scheduler.Future();
        future.cancelOnAbort = true;
        future.cancelAction = () -> cancel(token);
        pending.put(token, future);
        Object[] arguments = new Object[values.length + 2];
        arguments[0] = handle; arguments[1] = token;
        System.arraycopy(values, 0, arguments, 2, values.length);
        int status;
        try { status = ((Number) nativeAccess.call("builtin:runtime", function, "(UINT64,SINT64" + parameters + "):SINT32", arguments)).intValue(); }
        catch (RuntimeException error) { pending.remove(token); throw error; }
        if (status != 0) { pending.remove(token); throw new PhpError("libuv submission failed: " + status); }
        return future;
    }
    public void closeSocket(long socket) {
        checkOwner();
        if (!closed) nativeAccess.call("builtin:runtime", "gp_tcp_close", "(UINT64,SINT64,SINT64):SINT32", new Object[] {handle, 0L, socket});
    }
    public long allocateCurlGroup() { checkOwner(); return ++nextCurlGroup; }
    public void closeCurlGroup(long group) {
        checkOwner();
        if (closed) return;
        var future = submit("gp_reactor_curl_multi", ",SINT64,SINT32,UINT64,SINT64", group, 8, 0L, 0L);
        // Detachment happens inline on the owner. Never wait for remote I/O in a destructor.
        if (!future.completion.isDone()) throw new PhpError("cURL multi detach did not complete synchronously");
        Scheduler.result(future);
    }
    private void cancel(long token) {
        checkOwner();
        if (!closed && pending.containsKey(token)) nativeAccess.call("builtin:runtime", "gp_reactor_cancel", "(UINT64,SINT64):SINT32", new Object[] {handle, token});
    }
    @Override public void close() {
        checkOwner();
        synchronized (wakeupLock) {
            if (closed) return;
            closed = true;
        }
        nativeAccess.call("builtin:runtime", "gp_reactor_stop", "(UINT64):SINT32", new Object[] {handle});
        if (poller != null) {
            TruffleSafepoint.setBlockedThreadInterruptible(location, worker -> worker.join(5000), poller);
            if (poller.isAlive()) throw new PhpError("libuv host poller exceeded shutdown deadline");
        }
        int status = ((Number) nativeAccess.call("builtin:runtime", "gp_reactor_destroy", "(ENV,UINT64):SINT32", new Object[] {handle})).intValue();
        if (status != 0) throw new PhpError("libuv loop shutdown failed: " + status);
    }

    @ExportLibrary(InteropLibrary.class)
    public static final class Completion implements TruffleObject {
        private final Map<Long, Scheduler.Future> pending;
        private final NativeAccess nativeAccess;
        private final Thread owner;
        Completion(Map<Long, Scheduler.Future> pending, NativeAccess nativeAccess, Thread owner) {
            this.pending = pending; this.nativeAccess = nativeAccess; this.owner = owner;
        }
        @ExportMessage boolean isExecutable() { return true; }
        @ExportMessage @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        Object execute(Object[] values) {
            if (Thread.currentThread() != owner) throw new PhpError("Native completion escaped the PHP owner thread");
            var future = pending.remove(((Number) values[0]).longValue());
            if (future != null) {
                int status = ((Number) values[1]).intValue();
                if (status == 0) {
                    long value = ((Number) values[2]).longValue();
                    long length = ((Number) values[3]).longValue();
                    if (length >= 0) {
                        byte[] bytes = new byte[Math.toIntExact(length)];
                        if (length > 0) nativeAccess.call("builtin:runtime", "gp_bytes_copy", "(UINT64,[UINT8],SINT32):VOID", new Object[] {value, bytes, bytes.length});
                        future.completion.complete(bytes);
                    } else future.completion.complete(value == 0 ? null : value);
                }
                else future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "libuv operation cancelled or failed: " + status));
            }
            return 0L;
        }
    }
}