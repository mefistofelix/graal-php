package graalphp.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.interop.TruffleObject;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit shared capsule; full mutable PHP graphs are not implicitly thread-safe. */
public final class SharedCounter implements TruffleObject {
    public final Assumption local = Truffle.getRuntime().createAssumption("counter is thread-local");
    private final Thread owner = Thread.currentThread();
    private long value;
    private AtomicLong shared;
    public SharedCounter(long value) { this.value = value; }
    public synchronized void promote() {
        if (!local.isValid()) return;
        if (Thread.currentThread() != owner) throw new PhpError("Promote before crossing a thread boundary");
        shared = new AtomicLong(value);
        local.invalidate();
    }
    public long add(long delta) {
        if (!local.isValid()) return shared.addAndGet(delta);
        if (Thread.currentThread() != owner) throw new PhpError("Unpromoted counter crossed a thread boundary");
        value += delta; return value;
    }
}
