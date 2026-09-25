package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** One reader, ordered bounded writes, and one-shot deadlines over the request's libuv loop. */
public final class TcpConnection implements TruffleObject, AutoCloseable {
    public final LibuvReactor reactor;
    public final long id;
    public final int readTimeoutMs, writeTimeoutMs;
    private byte[] input = new byte[0];
    private boolean reading;
    private volatile boolean closed;
    private int pendingWriteBytes;
    private final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private boolean dispatching;

    public TcpConnection(LibuvReactor reactor, long id, int readTimeoutMs, int writeTimeoutMs) {
        this.reactor = reactor; this.id = id; this.readTimeoutMs = readTimeoutMs; this.writeTimeoutMs = writeTimeoutMs;
    }
    public void dispatch(Runnable action) {
        synchronized (work) {
            work.add(action);
            if (dispatching) return;
            dispatching = true;
        }
        for (;;) {
            Runnable next;
            synchronized (work) {
                next = work.poll();
                if (next == null) { dispatching = false; return; }
            }
            next.run();
        }
    }
    public CompletableFuture<byte[]> readExact(int count) {
        if (count < 0 || count > 16 * 1024 * 1024) return CompletableFuture.failedFuture(new PhpError("Read exceeds buffer limit"));
        return read(count, false);
    }
    public CompletableFuture<byte[]> readHeaders() { return read(32768, true); }
    private synchronized CompletableFuture<byte[]> read(int limit, boolean headers) {
        if (reading) return CompletableFuture.failedFuture(new PhpError("Concurrent reads on a TCP stream"));
        if (closed) return CompletableFuture.failedFuture(new PhpError("TCP stream closed"));
        reading = true;
        var result = new CompletableFuture<byte[]>();
        var released = result.whenComplete((value, error) -> { synchronized (this) { reading = false; } });
        dispatch(() -> readMore(result, limit, headers));
        return released;
    }
    private void readMore(CompletableFuture<byte[]> result, int limit, boolean headers) {
        try {
            int end = headers ? headerEnd(input) : input.length >= limit ? limit : -1;
            if (end >= 0) {
                if (headers && end > limit) throw new PhpError("HTTP headers exceed limit");
                byte[] value = Arrays.copyOf(input, end);
                input = Arrays.copyOfRange(input, end, input.length);
                result.complete(value);
                return;
            }
            if (headers && input.length >= limit) throw new PhpError("HTTP headers exceed limit");
            var read = reactor.submit("gp_tcp_read", ",SINT64", id);
            withDeadline(read.completion, readTimeoutMs).whenComplete((value, error) -> dispatch(() -> {
                if (error != null) { result.completeExceptionally(error); return; }
                byte[] bytes = (byte[]) value;
                if (bytes.length == 0) { result.completeExceptionally(new EndOfStream()); return; }
                int previous = input.length;
                input = Arrays.copyOf(input, previous + bytes.length);
                System.arraycopy(bytes, 0, input, previous, bytes.length);
                readMore(result, limit, headers);
            }));
        } catch (RuntimeException error) { result.completeExceptionally(error); }
    }
    private static int headerEnd(byte[] bytes) {
        for (int i = 3; i < bytes.length; i++) {
            if (bytes[i - 3] == 13 && bytes[i - 2] == 10 && bytes[i - 1] == 13 && bytes[i] == 10) return i + 1;
        }
        return -1;
    }
    public synchronized CompletableFuture<Object> write(byte[] bytes) {
        if (closed) return CompletableFuture.failedFuture(new PhpError("TrueAsync\\WebSocketClosedException", "Connection closed"));
        if (bytes.length > 16 * 1024 * 1024 || pendingWriteBytes > 16 * 1024 * 1024 - bytes.length) {
            return CompletableFuture.failedFuture(new PhpError("TrueAsync\\WebSocketBackpressureException", "Outbound buffer limit exceeded"));
        }
        pendingWriteBytes += bytes.length;
        try {
            var write = reactor.submit("gp_tcp_write", ",SINT64,[UINT8],SINT32", id, bytes, bytes.length);
            var result = withDeadline(write.completion, writeTimeoutMs);
            return result.whenComplete((value, error) -> { synchronized (this) { pendingWriteBytes -= bytes.length; } });
        } catch (RuntimeException error) { pendingWriteBytes -= bytes.length; throw error; }
    }
    private <T> CompletableFuture<T> withDeadline(CompletableFuture<T> operation, long milliseconds) {
        if (milliseconds == 0) return operation;
        var deadline = reactor.timer(milliseconds);
        var result = new CompletableFuture<T>();
        operation.whenComplete((value, error) -> {
            if (error == null) result.complete(value); else result.completeExceptionally(error);
            deadline.cancelAction.run();
        });
        deadline.completion.whenComplete((ignored, error) -> {
            if (error == null && result.completeExceptionally(new PhpError("TrueAsync\\HttpServerRuntimeException", "Socket deadline exceeded"))) close();
        });
        return result;
    }
    public static <T> Scheduler.Future awaitable(CompletableFuture<T> completion, Runnable cancel) {
        var future = new Scheduler.Future();
        future.cancelOnAbort = true;
        future.cancelAction = cancel;
        completion.whenComplete((value, error) -> {
            if (error == null) future.completion.complete(value);
            else {
                Throwable failure = cause(error);
                future.completion.completeExceptionally(failure instanceof PhpError ? failure : new PhpError("I/O failed: " + failure));
            }
        });
        return future;
    }
    public static Throwable cause(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        reactor.closeSocket(id);
    }
    public static final class EndOfStream extends RuntimeException {}
}
