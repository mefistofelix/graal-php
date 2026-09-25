package graalphp;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-loop adapter. Construct on the UI/owner thread; each post must enqueue, never invoke inline. */
public final class EmbeddedPhp {
    public interface Alarm { void cancel(); }
    public interface Driver {
        void post(Runnable work);
        Alarm schedule(long delayNanos, Runnable wakeup);
    }
    private final Driver driver;
    private final Context context;
    private final Value request;
    private final AtomicBoolean posted = new AtomicBoolean();
    private final AtomicBoolean alarmFired = new AtomicBoolean();
    private final Thread owner = Thread.currentThread();
    private Alarm alarm;
    private long armedDeadline = Long.MAX_VALUE;
    private volatile boolean closed;
    public final CompletableFuture<Object> completion = new CompletableFuture<>();

    public EmbeddedPhp(Context.Builder builder, Source source, Driver driver) {
        this.driver = driver;
        context = builder.environment("GRAALPHP_HOSTED", "1").build();
        try {
            context.getPolyglotBindings().putMember("graalphp.wakeup", (Runnable) this::signal);
            request = context.eval(source);
        } catch (RuntimeException error) { context.close(); throw error; }
        signal();
    }
    private void signal() {
        if (!closed && posted.compareAndSet(false, true)) driver.post(this::pump);
    }
    private void pump() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Driver changed the PHP owner thread");
        posted.set(false);
        if (closed) return;
        try {
            if (alarmFired.getAndSet(false)) armedDeadline = Long.MAX_VALUE;
            boolean runnable = request.invokeMember("pump", 8).asBoolean();
            if (!request.invokeMember("pending").asBoolean()) {
                Object value = request.invokeMember("result").as(Object.class);
                finish(value, null); return;
            }
            if (runnable) { signal(); return; }
            long deadline = request.invokeMember("deadline").asLong();
            if (deadline != armedDeadline) {
                if (alarm != null) alarm.cancel();
                armedDeadline = deadline;
                alarm = deadline == Long.MAX_VALUE ? null : driver.schedule(Math.max(0, deadline - System.nanoTime()), () -> {
                    // The driver may fire alarms on a timer thread; all pumping still goes through post.
                    alarmFired.set(true);
                    signal();
                });
            }
        } catch (RuntimeException error) { finish(null, error); }
    }
    private void finish(Object value, RuntimeException error) {
        closed = true;
        if (alarm != null) alarm.cancel();
        try { request.invokeMember("close"); context.close(); }
        catch (RuntimeException cleanup) { if (error == null) error = cleanup; else error.addSuppressed(cleanup); }
        if (error == null) completion.complete(value);
        else completion.completeExceptionally(error);
    }
    public void cancel() {
        driver.post(() -> { if (!closed) { request.invokeMember("cancel"); signal(); } });
    }
}
