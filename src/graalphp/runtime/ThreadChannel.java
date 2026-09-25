package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import java.util.ArrayDeque;

/** Shared channel metadata. Full guest graphs must wait for the LOCAL-to-SHARED publication barrier. */
public final class ThreadChannel implements TruffleObject {
    private record Message(Object value) {}
    private record Sender(Message message, Scheduler.Future future) {}
    private final ArrayDeque<Message> buffer = new ArrayDeque<>();
    private final ArrayDeque<Sender> senders = new ArrayDeque<>();
    private final ArrayDeque<Scheduler.Future> receivers = new ArrayDeque<>();
    private int capacity = 16;
    private boolean closed;

    public synchronized void initialize(int capacity) {
        if (capacity < 1) throw new PhpError("ValueError", "ThreadChannel capacity must be at least 1");
        if (!buffer.isEmpty() || !senders.isEmpty() || !receivers.isEmpty() || closed) throw new PhpError("Error", "ThreadChannel is already in use");
        this.capacity = capacity;
    }
    public synchronized int capacity() { return capacity; }
    public synchronized int count() { return buffer.size(); }
    public synchronized boolean isClosed() { return closed; }

    public static void publish(Object value) {
        if (value instanceof SharedCounter counter) { counter.promote(); return; }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof AsyncMutex || value instanceof ThreadChannel) return;
        throw new PhpError("TypeError", "Sharing PHP arrays/objects requires the pending LOCAL-to-SHARED graph barrier");
    }

    public synchronized Scheduler.Future send(Object value) {
        publish(value);
        var future = new Scheduler.Future();
        if (closed) future.completion.completeExceptionally(closedError());
        else if (!receivers.isEmpty()) {
            receivers.removeFirst().completion.complete(value);
            future.completion.complete(null);
        } else if (buffer.size() < capacity) {
            buffer.addLast(new Message(value));
            future.completion.complete(null);
        } else {
            var sender = new Sender(new Message(value), future);
            senders.addLast(sender);
            future.cancelOnAbort = true;
            future.cancelAction = () -> cancelSend(sender);
        }
        return future;
    }
    private synchronized void cancelSend(Sender sender) {
        if (senders.remove(sender)) sender.future.completion.completeExceptionally(cancelled());
    }

    public synchronized Scheduler.Future receive() {
        var future = new Scheduler.Future();
        if (!buffer.isEmpty()) {
            future.completion.complete(buffer.removeFirst().value);
            if (!senders.isEmpty()) {
                var sender = senders.removeFirst();
                buffer.addLast(sender.message);
                sender.future.completion.complete(null);
            }
        } else if (closed) future.completion.completeExceptionally(closedError());
        else {
            receivers.addLast(future);
            future.cancelOnAbort = true;
            future.cancelAction = () -> cancelReceive(future);
        }
        return future;
    }
    private synchronized void cancelReceive(Scheduler.Future future) {
        if (receivers.remove(future)) future.completion.completeExceptionally(cancelled());
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var sender : senders) sender.future.completion.completeExceptionally(closedError());
        for (var receiver : receivers) receiver.completion.completeExceptionally(closedError());
        senders.clear(); receivers.clear();
    }
    private static PhpError closedError() { return new PhpError("Async\\ThreadChannelException", "Thread channel is closed"); }
    private static PhpError cancelled() { return new PhpError("Async\\AsyncCancellation", "Thread channel operation cancelled"); }
}
