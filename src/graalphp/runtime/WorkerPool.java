package graalphp.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import static graalphp.runtime.Execution.*;

/** Bounded, request-owned offload. Idle workers continue servicing Truffle safepoints. */
public final class WorkerPool implements AutoCloseable {
    public record Configuration(int min, int max, int queueCapacity) {
        public Configuration {
            if (min < 0 || max < 1 || min > max || max > 65536 || queueCapacity < 1 || queueCapacity > 65536) {
                throw new PhpError("ValueError", "Pool requires 0 <= min <= max <= 65536 and 1 <= queueCapacity <= 65536");
            }
        }
    }
    private final class Job {
        final Scheduler.Future future = new Scheduler.Future();
        final Callable<Object> work;
        volatile Thread runner;
        volatile boolean cancelled;
        Job(Callable<Object> work) { this.work = work; }
        synchronized void cancel() {
            cancelled = true;
            if (runner != null) runner.interrupt();
            else future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Worker cancelled"));
        }
        void run() {
            Object value = null;
            Exception failure = null;
            synchronized (this) {
                if (!cancelled) runner = Thread.currentThread();
            }
            try {
                if (!cancelled) value = work.call();
            } catch (Exception error) {
                failure = error;
            } finally {
                synchronized (this) { runner = null; }
                Thread.interrupted();
                // Publish availability before completion wakes the submitting scheduler.
                synchronized (WorkerPool.this) { outstanding--; }
            }
            if (cancelled) future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Worker cancelled"));
            else if (failure != null) future.completion.completeExceptionally(failure instanceof PhpError ? failure : new PhpError(failure.getMessage()));
            else future.completion.complete(value);
        }
    }

    private final Request request;
    private final RootNode location;
    public final Configuration configuration;
    private final String name;
    private final java.util.ArrayDeque<Job> queue = new java.util.ArrayDeque<>();
    private final ArrayList<Thread> threads = new ArrayList<>();
    private volatile boolean closing;
    private int outstanding;

    public WorkerPool(Activation caller, String name) {
        this(caller, name, new Configuration(0,
                setting(caller.request.context.environment.getEnvironment(), "GRAALPHP_WORKERS", 4),
                setting(caller.request.context.environment.getEnvironment(), "GRAALPHP_WORK_QUEUE", 256)));
    }

    public WorkerPool(Activation caller, String name, Configuration configuration) {
        request = caller.request;
        this.name = name;
        this.configuration = configuration;
        location = ((RootCallTarget) caller.function.target()).getRootNode();
        for (int i = 0; i < configuration.min; i++) startWorker();
    }

    private static int setting(java.util.Map<String, String> environment, String name, int fallback) {
        String text = environment.get(name);
        if (text == null) return fallback;
        try {
            int value = Integer.parseInt(text);
            if (value > 0 && value <= 65536) return value;
        } catch (NumberFormatException ignored) { }
        throw new PhpError("Invalid " + name);
    }

    public synchronized Scheduler.Future submit(Activation caller, Callable<Object> work) {
        if (closing) throw new PhpError("Worker pool is closed");
        var job = new Job(work);
        job.future.cancelAction = job::cancel;
        if (queue.size() >= configuration.queueCapacity) throw new PhpError("Worker queue capacity exceeded");
        queue.addLast(job);
        outstanding++;
        request.scheduler.registerExternal(caller.task.scope, job.future);
        if (threads.size() < Math.min(configuration.max, outstanding)) startWorker();
        notifyAll();
        return job.future;
    }

    private void startWorker() {
        Thread thread = request.context.environment.newTruffleThreadBuilder(this::run).build();
        thread.setName("graalphp-" + name + "-" + (threads.size() + 1));
        threads.add(thread);
        thread.start();
    }
    public synchronized int workerCount() { return threads.size(); }

    private synchronized Job take() throws InterruptedException {
        while (queue.isEmpty() && !closing) wait();
        return queue.pollFirst();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void run() {
        while (true) {
            Job job = TruffleSafepoint.setBlockedThreadInterruptibleFunction(location,
                    pool -> pool.take(), this);
            if (job == null) return;
            job.run();
        }
    }

    @Override public void close() {
        synchronized (this) { closing = true; notifyAll(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var thread : threads) {
            long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            TruffleSafepoint.setBlockedThreadInterruptible(location, worker -> worker.join(remaining), thread);
            if (thread.isAlive()) {
                threads.forEach(Thread::interrupt);
                throw new PhpError("Worker did not finish within shutdown deadline");
            }
        }
    }
}
