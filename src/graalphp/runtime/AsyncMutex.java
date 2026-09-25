package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import java.util.ArrayDeque;

/** A FIFO mutex with logical ownership; waiting never blocks a scheduler's physical thread. */
public final class AsyncMutex implements TruffleObject {
    private record Waiter(Object owner, Scheduler.Future future) {}
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private Waiter holder;
    private boolean accepted;

    public synchronized boolean tryLock(Object owner) {
        if (holder != null) return false;
        holder = new Waiter(owner, null);
        accepted = true;
        return true;
    }

    public synchronized Scheduler.Future acquire(Object owner) {
        if (holder != null && holder.owner == owner) throw new PhpError("Async\\AsyncException", "Mutex is not recursive");
        var future = new Scheduler.Future();
        var waiter = new Waiter(owner, future);
        if (holder == null) grant(waiter);
        else waiters.addLast(waiter);
        return future;
    }

    public synchronized void accept(Object owner, Scheduler.Future future) {
        if (holder == null || holder.owner != owner || holder.future != future || accepted) {
            throw new PhpError("Async\\AsyncException", "Invalid mutex acquisition");
        }
        accepted = true;
    }

    /** Undo a reservation even if it completed just before the coroutine was cancelled. */
    public synchronized void abort(Object owner, Scheduler.Future future) {
        if (holder != null && holder.owner == owner && holder.future == future && !accepted) release();
        else waiters.removeIf(waiter -> waiter.owner == owner && waiter.future == future);
        future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Mutex acquisition cancelled"));
    }

    public synchronized void unlock(Object owner) {
        if (holder == null || holder.owner != owner || !accepted) throw new PhpError("Async\\AsyncException", "Mutex can only be unlocked by its owner");
        release();
    }

    public synchronized boolean isLocked() { return holder != null; }

    private void grant(Waiter waiter) {
        holder = waiter;
        accepted = false;
        waiter.future.completion.complete(null);
    }
    private void release() {
        holder = null;
        accepted = false;
        if (!waiters.isEmpty()) grant(waiters.removeFirst());
    }
}
