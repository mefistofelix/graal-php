package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import java.util.ArrayDeque;
import static graalphp.runtime.Execution.*;

/** TrueAsync channel semantics on the cooperative request dispatcher. */
public final class AsyncChannel implements TruffleObject, AutoCloseable {
    private record Message(Object value) {}
    private record Sender(Message message, Scheduler.Future future) {}
    public final Request request;
    private final ArrayDeque<Message> buffer = new ArrayDeque<>();
    private final ArrayDeque<Sender> senders = new ArrayDeque<>();
    private final ArrayDeque<Scheduler.Future> receivers = new ArrayDeque<>();
    public int capacity;
    public boolean closed;

    public AsyncChannel(Request request) { this.request = request; }
    public int count() { return buffer.size(); }

    public boolean trySend(Object value) {
        if (closed) return false;
        if (!receivers.isEmpty()) {
            receivers.removeFirst().completion.complete(PhpValues.own(value));
            return true;
        }
        if (buffer.size() >= capacity) return false;
        buffer.addLast(new Message(PhpValues.own(value)));
        return true;
    }

    public Object send(Object value, Scheduler.Future cancellation) {
        if (closed) throw closedError();
        if (trySend(value)) return null;
        var future = new Scheduler.Future();
        var sender = new Sender(new Message(PhpValues.own(value)), future);
        senders.addLast(sender);
        future.cancelOnAbort = true;
        future.cancelAction = () -> {
            if (senders.remove(sender)) PhpValues.drop(sender.message.value);
            future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Channel send cancelled"));
        };
        request.scheduler.keep(future);
        return new Scheduler.Await(future, cancellation);
    }

    public Scheduler.Future receive() {
        var future = new Scheduler.Future();
        request.scheduler.keep(future);
        if (!buffer.isEmpty()) {
            future.completion.complete(buffer.removeFirst().value);
            if (!senders.isEmpty()) {
                var sender = senders.removeFirst();
                buffer.addLast(sender.message);
                sender.future.completion.complete(null);
            }
        } else if (!senders.isEmpty()) {
            var sender = senders.removeFirst();
            future.completion.complete(sender.message.value);
            sender.future.completion.complete(null);
        } else if (closed) future.completion.completeExceptionally(closedError());
        else {
            receivers.addLast(future);
            future.cancelOnAbort = true;
            future.cancelAction = () -> {
                receivers.remove(future);
                future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Channel receive cancelled"));
            };
        }
        return future;
    }

    private PhpError closedError() { return new PhpError("Async\\ChannelException", "Channel is closed"); }
    public void closeChannel() {
        if (closed) return;
        closed = true;
        for (var sender : senders) {
            PhpValues.drop(sender.message.value);
            sender.future.completion.completeExceptionally(closedError());
        }
        senders.clear();
        for (var receiver : receivers) receiver.completion.completeExceptionally(closedError());
        receivers.clear();
    }
    @Override public void close() {
        closeChannel();
        for (var message : buffer) PhpValues.drop(message.value);
        buffer.clear();
    }
}
