package graalphp.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.interop.TruffleObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static graalphp.runtime.Execution.*;

/** Request event loop, nested task scopes and explicit ownership across continuations. */
public final class Scheduler implements AutoCloseable {
    public sealed interface Effect permits Delay, Await, Cooperate, GeneratorYield, GeneratorDelegate {}
    public record Cooperate() implements Effect {}
    public record GeneratorYield(Object key, Object value, boolean automatic, boolean reference) implements Effect {}
    public record GeneratorDelegate(Object source) implements Effect {}
    public record Delay(long milliseconds) implements Effect {}
    /** Optional synchronous completion runs on the owner just before guest resumption,
     * including cancellation delivery. It must preserve ownership of its input. */
    public static final class Await implements Effect, AutoCloseable {
        private final Future future;
        private final Future cancellation;
        private final java.util.function.UnaryOperator<Object> finish;
        private boolean released;

        public Await(Future future) { this(future, null, null); }
        public Await(Future future, Future cancellation) { this(future, cancellation, null); }
        public Await(Future future, Future cancellation, java.util.function.UnaryOperator<Object> finish) {
            this.future = future;
            this.cancellation = cancellation;
            this.finish = finish;
            PhpValues.retain(future);
            if (cancellation != future) PhpValues.retain(cancellation);
        }
        @Override @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void close() {
            if (released) return;
            released = true;
            PhpValues.release(future);
            if (cancellation != future) PhpValues.release(cancellation);
        }
    }
    private record Parent(ContinuationResult continuation, Activation activation) {}
    private record Timer(long deadline, Runnable action) {}

    /** Guest futures own results in the PHP graph. Native signals have no guest heap. */
    public static final class Future extends PhpValues.HeapNode implements TruffleObject {
        public final CompletableFuture<Object> completion = new CompletableFuture<>();
        public Task task;
        public boolean observed;
        public Runnable cancelAction;
        public boolean cancelOnAbort;
        public boolean cancellationRequested;
        Runnable abandon;
        private Future boundState;
        private FutureLink link;

        public Future() { this(null); }
        Future(PhpValues.Heap heap) { super(heap); }

        Future state() { return boundState == null ? this : boundState; }

        void bind(Future state) {
            PhpValues.retain(state);
            var previous = boundState;
            boundState = state;
            PhpValues.release(previous);
        }

        /** Transfers an owned result, or retains a borrowed result for a guest future. */
        void completeValue(Object value) {
            if (heap == null) {
                if (!completion.complete(value)) PhpValues.drop(value);
                return;
            }
            Object raw = PhpValues.unwrap(value);
            var result = PhpValues.managed(raw);
            try {
                if (result != null && result.heap != heap) throw new PhpError("Future result belongs to another heap");
                PhpValues.retain(raw);
                if (!completion.complete(raw)) PhpValues.release(raw);
            } finally { PhpValues.drop(value); }
        }

        void follow(Future source) {
            if (link != null || completion.isDone()) throw new PhpError("Future is already initialized");
            PhpValues.retain(source);
            link = new FutureLink(source, this);
            source.completion.whenComplete(link::deliver);
        }

        void unlink() {
            if (link == null) return;
            var source = link.detach();
            link = null;
            PhpValues.release(source);
        }

        @Override void children(java.util.function.Consumer<PhpValues.HeapNode> visit) {
            if (boundState != null) visit.accept(boundState);
            if (link != null) PhpValues.visitChild(link.source, visit);
            if (completion.isDone() && !completion.isCompletedExceptionally()) {
                PhpValues.visitChild(completion.getNow(null), visit);
            }
        }

        @Override void discard() {
            boundState = null;
            if (link != null) {
                link.detach();
                link = null;
            }
            if (completion.isDone() && !completion.isCompletedExceptionally()) completion.obtrudeValue(null);
            var dispose = abandon;
            abandon = null;
            cancelAction = null;
            if (dispose != null) dispose.run();
        }
    }

    /** Detaching both ends leaves a pending source with no reference to a discarded target. */
    private static final class FutureLink {
        private Future source;
        private Future target;
        FutureLink(Future source, Future target) { this.source = source; this.target = target; }
        private void deliver(Object value, Throwable failure) {
            var destination = target;
            if (destination == null) return;
            try {
                if (failure == null) destination.completeValue(PhpValues.unwrap(value));
                else destination.completion.completeExceptionally(failure);
            } finally {
                destination.cancelAction = null;
                destination.unlink();
            }
        }
        private Future detach() {
            var previous = source;
            source = null;
            target = null;
            return previous;
        }
    }

    public static final class Scope implements TruffleObject {
        final Scope parent;
        final long deadline;
        final java.util.Set<Future> members = new java.util.LinkedHashSet<>();
        int activeMembers; // Includes descendants; updated only by the owner.
        boolean cancelled;
        boolean hostCancelled;
        PhpError failure;
        boolean allowZombies;
        boolean draining;
        PhpError completionFailure;
        Timer disposalTimer;
        Object exceptionHandler;
        Object childScopeExceptionHandler;
        public boolean disposed;
        public AsyncApi.ContextValue context;
        final List<Future> waiters = new ArrayList<>();
        final List<ScopeDrain> drainWaiters = new ArrayList<>();
        Scope(Scope parent, long deadline, boolean allowZombies) {
            this.parent = parent;
            this.deadline = deadline;
            this.allowZombies = allowZombies;
        }
        void setExceptionHandler(Object callback, boolean children) {
            if (disposed) throw new PhpError("Async\\AsyncException", "Scope object has been disposed");
            PhpValues.retain(callback);
            if (children) {
                PhpValues.release(childScopeExceptionHandler);
                childScopeExceptionHandler = callback;
            } else {
                PhpValues.release(exceptionHandler);
                exceptionHandler = callback;
            }
        }
        void releaseHandlers() {
            PhpValues.release(exceptionHandler);
            PhpValues.release(childScopeExceptionHandler);
            exceptionHandler = null;
            childScopeExceptionHandler = null;
        }
    }

    private static final class ScopeDrain {
        final Future future = new Future();
        final Object handler;
        ScopeDrain(Object handler) { this.handler = handler; PhpValues.retain(handler); }
        void release() { PhpValues.release(handler); }
    }

    /** Runs error handlers on the coroutine which failed, even across a suspension. */
    final class ScopeFailure implements TruffleObject {
        final Future source;
        final Scope origin;
        final PhpError original;
        Scope scope;
        PhpError error;
        ScopeDrain drain;
        List<ScopeDrain> drains;
        int nextDrain;
        boolean triedHandler;
        boolean handled;

        ScopeFailure(Task task, PhpError original) {
            source = task.future;
            origin = scope = task.scope;
            this.original = error = original;
        }

        Object nextHandler() {
            drain = null;
            while (scope != null) {
                if (!triedHandler) {
                    triedHandler = true;
                    Object handler = scope != origin && scope.childScopeExceptionHandler != null
                            ? scope.childScopeExceptionHandler : scope.exceptionHandler;
                    if (handler != null) return handler;
                }
                if (drains == null) {
                    handled |= !scope.cancelled && !scope.waiters.isEmpty();
                    disposeScope(scope, new PhpError("Async\\AsyncCancellation", "Scope was cancelled"),
                            scope.allowZombies, error, source);
                    drains = List.copyOf(scope.drainWaiters);
                }
                while (nextDrain < drains.size()) {
                    drain = drains.get(nextDrain++);
                    if (drain.future.completion.isDone()) continue;
                    if (drain.handler != null) return drain.handler;
                    drain.future.completion.completeExceptionally(error);
                    handled = true;
                }
                if (handled) return null;
                scope = scope.parent;
                triedHandler = false;
                drain = null;
                drains = null;
                nextDrain = 0;
            }
            if (!handled) cancelScope(rootScope, error, source);
            return null;
        }

        void delivered() {
            handled = true;
            if (drain == null) scope = null;
        }

        void rejected(PhpError failure) {
            if (drain != null) {
                drain.future.completion.completeExceptionally(failure);
                handled = true;
            } else if (failure != error) {
                error = failure.previous == null ? new PhpError(failure.type, failure.getMessage(), error) : failure;
            }
        }
    }

    public static final class Task {
        public final Future future;
        public final Scope scope;
        public ContextNode context;
        public Activation activation;
        public AsyncApi.ContextValue privateContext;
        public boolean started;
        public boolean running;
        private final ArrayDeque<Parent> parents = new ArrayDeque<>();
        private ContinuationResult waiting;
        private long ticket;
        public boolean cancelled;
        private boolean cancellationDelivered;
        private PhpError cancellation;
        public int protectionDepth;
        public int checkpoints = 128;
        private WaitRegistration registration;
        private ScopeFailure scopeFailure;
        GeneratorApi.State generator;
        Task(ContextNode context, Scope scope, PhpValues.Heap heap) {
            this.context = context;
            this.scope = scope;
            future = new Future(heap);
            future.task = this;
        }
        public void checkCancellation() {
            boolean interrupted = Thread.currentThread().isInterrupted();
            boolean deadlineExceeded = System.nanoTime() >= scope.deadline;
            if (interrupted || deadlineExceeded) cancelled = true;
            // protect shields cooperative cancellation, not the host request's execution budget.
            if (cancelled && !cancellationDelivered && (protectionDepth == 0 || interrupted || deadlineExceeded)) {
                cancellationDelivered = true;
                if (deadlineExceeded) throw new PhpError("Async\\AsyncCancellation", "Request deadline exceeded");
                throw cancellation == null ? new PhpError("Async\\AsyncCancellation", "Coroutine cancelled") : cancellation;
            }
        }
    }

    private final Request request;
    private final Thread owner = Thread.currentThread();
    private final ArrayDeque<Runnable> ready = new ArrayDeque<>();
    private final LinkedBlockingQueue<Runnable> incoming = new LinkedBlockingQueue<>();
    private final java.util.Set<CompletableFuture<Object>> dispatches = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(Comparator.comparingLong(Timer::deadline));
    private final java.util.Set<Task> tasks = new java.util.LinkedHashSet<>();
    private final List<Future> external = new ArrayList<>();
    private final List<Scope> scopes = new ArrayList<>();
    private final java.util.Set<Listener> listeners = new java.util.HashSet<>();
    public final Scope rootScope;
    private volatile boolean closed;
    private volatile Runnable wakeup = () -> {};
    private long cleanupDeadline = Long.MAX_VALUE;
    private int activeMembers; // Also counts detached scopes, which have no rootScope ancestor.
    private int generatorCleanup;

    public Scheduler(Request request) {
        this.request = request;
        String configured = request.context.environment.getEnvironment().get("GRAALPHP_TIMEOUT_MS");
        long milliseconds = configured == null ? 30000 : Long.parseLong(configured);
        if (milliseconds <= 0) throw new PhpError("GRAALPHP_TIMEOUT_MS must be positive");
        rootScope = new Scope(null, deadline(milliseconds), true);
        scopes.add(rootScope);
    }

    private static long deadline(long milliseconds) {
        long now = System.nanoTime();
        long duration = TimeUnit.MILLISECONDS.toNanos(milliseconds);
        return duration > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
    }

    public Future spawn(Function function, Argument[] args, ContextNode context, boolean topLevel) {
        return spawn(new ObjectModel.Invocation(function, null, null, null), args, context, topLevel, rootScope);
    }

    public Future spawn(ObjectModel.Invocation invocation, Argument[] args, ContextNode context, boolean topLevel) {
        return spawn(invocation, args, context, topLevel, rootScope);
    }

    public Future spawn(ObjectModel.Invocation invocation, Argument[] args, ContextNode context, boolean topLevel, Scope scope) {
        return spawn(invocation, args, context, topLevel, scope, null);
    }

    Future spawnGuest(ObjectModel.Invocation invocation, Argument[] args, ContextNode context, Scope scope) {
        return spawn(invocation, args, context, false, scope, request.heap);
    }

    private Future spawn(ObjectModel.Invocation invocation, Argument[] args, ContextNode context, boolean topLevel, Scope scope,
                         PhpValues.Heap heap) {
        if (scope.cancelled || scope.disposed) {
            for (var argument : args) argument.close();
            throw new PhpError("Async\\AsyncException", "Cannot spawn a coroutine in a closed scope");
        }
        var function = invocation.function();
        var task = new Task(context, scope, heap);
        try {
            task.activation = new Activation(request, function, task, topLevel, args);
            ObjectModel.bind(task.activation, invocation);
        } catch (RuntimeException error) {
            if (task.activation != null) task.activation.close();
            throw error;
        } finally { for (var argument : args) argument.close(); }
        PhpValues.retain(task.future);
        tasks.add(task);
        addMember(scope, task.future);
        enqueue(() -> {
            task.started = true;
            task.running = true;
            try { task.checkCancellation(); advance(task, function.target().call(task.activation), null); }
            catch (PhpError error) { advance(task, null, error); }
            finally { task.running = false; }
        });
        return task.future;
    }
    Task createGenerator(Activation caller, ObjectModel.Invocation invocation, Argument[] args) {
        var task = new Task(caller.task.context, caller.task.scope, null);
        task.activation = new Activation(request, invocation.function(), task, false, args, Diagnostics.origin(caller));
        ObjectModel.bind(task.activation, invocation);
        tasks.add(task);
        return task;
    }
    void startGenerator(Task task) {
        enqueue(() -> {
            if (task.future.completion.isDone()) return;
            task.started = true;
            task.running = true;
            try { advance(task, task.activation.function.target().call(task.activation), null); }
            catch (PhpError error) { advance(task, null, error); }
            finally { task.running = false; }
        });
    }
    void resumeGenerator(Task task, Object value) {
        if (task.waiting == null) { PhpValues.drop(value); throw new PhpError("Generator is not suspended"); }
        resumeLater(task, task.ticket, value);
    }
    void closeGenerator(Task task) {
        if (!task.started) {
            if (task.activation != null) task.activation.close();
            task.future.completion.complete(null);
            return;
        }
        if (task.future.completion.isDone()) return;
        generatorCleanup++;
        var continuation = task.waiting;
        if (continuation == null) {
            generatorCleanup--;
            return;
        }
        task.waiting = null;
        task.running = true;
        try {
            advance(task, continuation.continueWith(new Failure(PhpError.generatorClose())), null);
        } catch (PhpError error) {
            advance(task, null, error);
        } finally {
            task.running = false;
        }
    }
    void generatorClosed() {
        if (generatorCleanup > 0) generatorCleanup--;
    }
    public Scope newScope(Scope parent) {
        var scope = new Scope(parent, rootScope.deadline, parent != null && parent.allowZombies);
        scopes.add(scope);
        return scope;
    }
    public List<Scope> children(Scope parent) {
        return scopes.stream().filter(scope -> scope.parent == parent).toList();
    }
    public Future scopeCompletion(Scope scope) {
        var future = new Future();
        future.observed = true;
        future.cancelOnAbort = true;
        future.cancelAction = () -> {
            scope.waiters.remove(future);
            future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Operation has been cancelled"));
        };
        if (scope.activeMembers == 0) future.completion.complete(null);
        else scope.waiters.add(future);
        return future;
    }

    Future scopeDrain(Scope scope, Object handler) {
        if (scope.activeMembers == 0) return scopeCompletion(scope);
        var registration = new ScopeDrain(handler);
        var future = registration.future;
        future.observed = true;
        future.cancelOnAbort = true;
        future.cancelAction = () -> {
            if (scope.drainWaiters.remove(registration)) registration.release();
            future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Operation has been cancelled"));
        };
        scope.drainWaiters.add(registration);
        return future;
    }
    public void checkScopeWait(Task caller, Scope scope) {
        for (var current = caller.scope; current != null; current = current.parent) {
            if (current == scope) throw new PhpError("Async\\AsyncException",
                    "Cannot await completion of scope from a coroutine that belongs to the same scope or its children");
        }
    }
    public boolean finished(Scope scope) {
        return scope.draining || scope.activeMembers == 0;
    }
    public Future timeout(long milliseconds) {
        if (milliseconds < 0) throw new PhpError("Timeout must not be negative");
        var future = new Future();
        timers.add(new Timer(deadline(milliseconds), () -> future.completion.completeExceptionally(
                new PhpError("Async\\TimeoutException", "Operation timed out"))));
        wakeup.run();
        return future;
    }
    /** Detaching releases the guest graph even if the source future never completes. */
    public final class Listener implements AutoCloseable {
        private java.util.function.BiConsumer<Object, PhpError> callback;
        private Future source;
        private Listener(Future future, java.util.function.BiConsumer<Object, PhpError> callback) {
            source = future;
            PhpValues.retain(source);
            this.callback = callback;
        }
        private void deliver(Object value, Throwable error) {
            var action = callback;
            var pin = source;
            source = null;
            close();
            try {
                if (action != null) action.accept(value, error == null ? null
                        : error instanceof PhpError php ? php : new PhpError(error.getMessage()));
            } finally { PhpValues.release(pin); }
        }
        @Override public void close() {
            callback = null;
            listeners.remove(this);
            var pin = source;
            source = null;
            PhpValues.release(pin);
        }
    }

    public Listener listen(Future future, java.util.function.BiConsumer<Object, PhpError> callback) {
        future.observed = true;
        var listener = new Listener(future, callback);
        listeners.add(listener);
        future.completion.whenComplete((value, error) -> enqueue(() -> listener.deliver(value, error)));
        return listener;
    }

    public void enqueue(Runnable action) {
        if (closed) return;
        if (Thread.currentThread() == owner) ready.add(action);
        else {
            incoming.add(action);
            request.wakeIO();
        }
        wakeup.run();
    }
    private boolean hasReady() { return !ready.isEmpty() || !incoming.isEmpty(); }
    public void notifyHost() { if (!closed) wakeup.run(); }
    public void setWakeup(Runnable wakeup) {
        this.wakeup = wakeup;
        if (hasReady()) wakeup.run();
    }

    public static PhpError operationCancelled(Future token) {
        return new PhpError("Async\\OperationCanceledException", "Operation has been cancelled", failure(token));
    }

    /** An already resolved source wins over cancellation and does not yield. */
    public static Object awaitValue(Activation caller, Future future, Future cancellation) {
        future.observed = true;
        if (!future.completion.isDone()
                && (cancellation == null || cancellation == future || !cancellation.completion.isDone())) {
            return new Await(future, cancellation == future ? null : cancellation);
        }
        // An awaitable synthesized from a channel may have no other owner yet.
        if (future.completion.isDone()) {
            try { return caller.track(PhpValues.own(PhpValues.unwrap(result(future)))); }
            finally {
                discardUnowned(future);
                if (cancellation != future) discardUnowned(cancellation);
            }
        }
        cancellation.observed = true;
        try {
            if (future.cancelOnAbort) caller.request.scheduler.cancel(future);
            throw operationCancelled(cancellation);
        } finally {
            discardUnowned(future);
            discardUnowned(cancellation);
        }
    }

    static void discardUnowned(Future future) {
        if (future != null && future.heap != null && future.owners == 0) {
            PhpValues.retain(future);
            PhpValues.release(future);
        }
    }

    public void registerExternal(Scope scope, Future future) {
        addMember(scope, future);
        external.add(future);
        future.completion.whenComplete((value, error) -> {
            if (Thread.currentThread() == owner) memberFinished(scope);
            else enqueue(() -> memberFinished(scope));
        });
    }

    private void addMember(Scope scope, Future future) {
        scope.members.add(future);
        activeMembers++;
        for (var current = scope; current != null; current = current.parent) current.activeMembers++;
    }

    private void memberFinished(Scope scope) {
        activeMembers--;
        for (var current = scope; current != null; current = current.parent) current.activeMembers--;
        for (var current = scope; current != null; current = current.parent) updateScope(current);
    }

    public CompletableFuture<Object> dispatch(java.util.concurrent.Callable<Object> action) {
        var result = new CompletableFuture<Object>();
        if (closed || request.fatalFailure != null) {
            result.completeExceptionally(request.fatalFailure == null ? new PhpError("Request dispatcher is closed") : request.fatalFailure);
            return result;
        }
        dispatches.add(result);
        result.whenComplete((value, error) -> dispatches.remove(result));
        if (closed || request.fatalFailure != null) {
            result.completeExceptionally(request.fatalFailure == null ? new PhpError("Request dispatcher is closed") : request.fatalFailure);
            return result;
        }
        enqueue(() -> {
            if (result.isDone()) return;
            try { result.complete(action.call()); }
            catch (Exception error) { result.completeExceptionally(error); }
        });
        return result;
    }

    public void abortDispatches(PhpError reason) {
        for (var result : dispatches) result.completeExceptionally(reason);
    }

    public Object run(Function main) {
        return runUntil(start(main));
    }
    public Future start(Function main) {
        var root = spawn(main, new Argument[0], new ContextNode(null, java.util.Map.of()), true);
        root.observed = true;
        return root;
    }

    public Object runUntil(Future root) {
        root.observed = true;
        while (pending()) {
            pump(root, 64);
            if (!pending() || hasReady()) continue;
            long timeout = Math.max(0, nextDeadline() - System.nanoTime());
            if (request.awaitIO(timeout)) continue;
            var location = ((com.oracle.truffle.api.RootCallTarget) root.task.activation.function.target()).getRootNode();
            Runnable action = com.oracle.truffle.api.TruffleSafepoint.setBlockedThreadInterruptibleFunction(location,
                    queue -> queue.poll(timeout, TimeUnit.NANOSECONDS), incoming);
            if (action != null) runAction(root, action);
        }
        return finish(root);
    }

    /** A host loop drives finite, non-blocking slices on the request's owning thread. */
    public boolean pump(Future root, int budget) {
        if (budget < 1) throw new PhpError("Pump budget must be positive");
        request.pollIO();
        for (int i = 0; i < budget; i++) {
            long now = System.nanoTime();
            if (!rootScope.cancelled && now >= rootScope.deadline) cancelScope(rootScope, new PhpError("Request deadline exceeded"));
            if (rootScope.cancelled && cleanupDeadline == Long.MAX_VALUE) cleanupDeadline = deadline(5000);
            if (now >= cleanupDeadline) throw new PhpError("Cleanup timed out after cancellation");
            for (var scope : scopes) if (!scope.hostCancelled && now >= scope.deadline) cancelScope(scope, new PhpError("Scope deadline exceeded"));
            while (!timers.isEmpty() && timers.peek().deadline <= now) ready.add(timers.remove().action);
            // Cross-thread work joins the local FIFO even while PHP stays runnable.
            if (!incoming.isEmpty()) incoming.drainTo(ready);
            var action = ready.poll();
            if (action == null) break;
            runAction(root, action);
        }
        request.pollIO();
        if (request.hosted && !hasReady() && pending()) request.armHostIO();
        return hasReady();
    }
    private void runAction(Future root, Runnable action) {
        if (request.fatalFailure != null) throw request.fatalFailure;
        action.run();
        if (request.fatalFailure != null) throw request.fatalFailure;
        if (root.completion.isCompletedExceptionally() && !rootScope.cancelled) cancelScope(rootScope, failure(root));
    }
    public long nextDeadline() {
        if (hasReady()) return System.nanoTime();
        long next = cleanupDeadline;
        for (var scope : scopes) if (!scope.hostCancelled) next = Math.min(next, scope.deadline);
        if (!timers.isEmpty()) next = Math.min(next, timers.peek().deadline);
        return next;
    }
    public Object finish(Future root) {
        if (pending()) throw new PhpError("Request still has pending work");
        for (var future : rootScope.members) {
            if (!future.observed && future.completion.isCompletedExceptionally()) result(future);
        }
        if (rootScope.failure != null) throw rootScope.failure;
        return result(root);
    }

    public boolean pending() {
        return activeMembers != 0 || generatorCleanup != 0;
    }

    private static PhpError failure(Future future) {
        try { result(future); return null; }
        catch (PhpError error) { return error; }
    }

    public static Object result(Future future) {
        try { return future.completion.join(); }
        catch (java.util.concurrent.CompletionException error) {
            if (error.getCause() instanceof PhpError php) throw php;
            throw new PhpError(String.valueOf(error.getCause()));
        }
    }

    public void cancel(Future future) { cancel(future, null); }

    public void cancel(Future future, PhpError reason) {
        if (future.completion.isDone()) return;
        future.cancellationRequested = true;
        if (future.task != null) cancel(future.task, reason);
        else if (future.cancelAction != null) future.cancelAction.run();
        else future.completion.completeExceptionally(reason == null
                ? new PhpError("Async\\AsyncCancellation", "Operation has been cancelled") : reason);
        if (future.completion.isDone()) future.unlink();
    }

    public void cancel(Task task) { cancel(task, null); }

    private void cancel(Task task, PhpError reason) {
        if (task.future.completion.isDone() || task.cancellationDelivered) return;
        task.cancelled = true;
        if (task.cancellation == null) task.cancellation = reason == null
                ? new PhpError("Async\\AsyncCancellation", "Coroutine cancelled") : reason;
        if (task.protectionDepth != 0) return;
        if (task.waiting != null) {
            if (task.registration != null) {
                Future source = task.registration.effect.future;
                if (source.cancelOnAbort) cancel(source);
                task.registration.close();
            }
            if (task.waiting.getResult() instanceof Await effect && effect.future.cancelOnAbort) cancel(effect.future);
            task.cancellationDelivered = true;
            resumeLater(task, ++task.ticket, new Failure(task.cancellation));
        }
    }

    public void cancelScope(Scope scope, PhpError error) {
        cancelScope(scope, error, null);
    }

    private void cancelScope(Scope scope, PhpError error, Future source) {
        if (scope.hostCancelled) return;
        scope.hostCancelled = true;
        scope.cancelled = true;
        scope.failure = error;
        clearDisposalTimer(scope);
        for (var member : List.copyOf(scope.members)) if (member != source) cancel(member, error);
        for (var child : scopes) if (child.parent == scope) cancelScope(child, error, source);
    }

    void disposeScope(Scope scope, PhpError error, boolean safely) {
        disposeScope(scope, error, safely, safely ? null : error, null);
    }

    private void disposeScope(Scope scope, PhpError cancellation, boolean safely, PhpError completion, Future source) {
        if (scope.disposed || scope.cancelled) return;
        clearDisposalTimer(scope);
        if (scope.activeMembers == 0) {
            scope.disposed = true;
            scope.releaseHandlers();
            return;
        }
        scope.cancelled = true;
        scope.draining = true;
        scope.completionFailure = completion;
        for (var child : scopes) if (child.parent == scope)
            disposeScope(child, cancellation, safely, safely ? null : cancellation, source);
        if (!safely) for (var member : List.copyOf(scope.members)) if (member != source) cancel(member, cancellation);
        completeScopeWaiters(scope.waiters, scope.completionFailure);
    }

    void disposeScopeAfter(Scope scope, long milliseconds) {
        if (scope.disposed || scope.cancelled || scope.activeMembers == 0 && children(scope).isEmpty()) return;
        long when = deadline(milliseconds);
        if (scope.disposalTimer != null && scope.disposalTimer.deadline <= when) return;
        clearDisposalTimer(scope);
        scope.disposalTimer = new Timer(when, () -> disposeScope(scope,
                new PhpError("Async\\AsyncCancellation", "Scope has been disposed due to timeout"), scope.allowZombies));
        timers.add(scope.disposalTimer);
        wakeup.run();
    }

    private void clearDisposalTimer(Scope scope) {
        if (scope.disposalTimer != null) {
            timers.remove(scope.disposalTimer);
            scope.disposalTimer = null;
        }
    }

    private void updateScope(Scope scope) {
        if (scope.activeMembers != 0) return;
        if (scope.draining) {
            scope.disposed = true;
            scope.releaseHandlers();
        }
        completeScopeWaiters(scope.waiters, scope.completionFailure);
        for (var waiter : scope.drainWaiters) {
            waiter.future.completion.complete(null);
            waiter.release();
        }
        scope.drainWaiters.clear();
    }

    private static void completeScopeWaiters(List<Future> waiters, PhpError error) {
        for (var waiter : waiters) {
            if (error == null) waiter.completion.complete(null);
            else waiter.completion.completeExceptionally(error);
        }
        waiters.clear();
    }

    private final class WaitRegistration implements AutoCloseable {
        private final Task task;
        private final long ticket;
        private final Await effect;
        private Listener source;
        private Listener cancellation;
        private boolean active = true;
        WaitRegistration(Task task, long ticket, Await effect) {
            this.task = task;
            this.ticket = ticket;
            this.effect = effect;
            try {
                source = listen(effect.future, (value, error) -> settle(value, error, false));
                if (effect.cancellation != null && effect.cancellation != effect.future) {
                    cancellation = listen(effect.cancellation, (value, error) -> settle(null,
                            operationCancelled(effect.cancellation), true));
                }
            } finally { effect.close(); }
        }
        private void settle(Object value, PhpError error, boolean abort) {
            if (!active) return;
            if (abort && effect.future.cancelOnAbort) cancel(effect.future);
            close();
            resumeLater(task, ticket, error == null ? PhpValues.own(PhpValues.unwrap(value)) : new Failure(error));
        }
        @Override public void close() {
            active = false;
            if (source != null) source.close();
            if (cancellation != null) cancellation.close();
            if (task.registration == this) task.registration = null;
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void advance(Task task, Object result, PhpError failure) {
        while (true) {
            if (failure == null && result instanceof ContinuationResult continuation) {
                if (continuation.getResult() instanceof NestedCall call) {
                    task.parents.push(new Parent(continuation, task.activation));
                    task.activation = call.activation();
                    result = call.continuation();
                    continue;
                }
                task.waiting = continuation;
                long ticket = ++task.ticket;
                if (task.cancelled && !task.cancellationDelivered && task.protectionDepth == 0) {
                    if (continuation.getResult() instanceof Await effect) {
                        try {
                            if (effect.future.cancelOnAbort) cancel(effect.future);
                        } finally { effect.close(); }
                    }
                    task.cancellationDelivered = true;
                    resumeLater(task, ticket, new Failure(task.cancellation == null
                            ? new PhpError("Async\\AsyncCancellation", "Coroutine cancelled") : task.cancellation));
                    return;
                }
                if (task.generator != null) {
                    if (continuation.getResult() instanceof GeneratorYield yielded) {
                        task.generator.yielded(yielded);
                        return;
                    }
                    if (continuation.getResult() instanceof GeneratorDelegate delegated) {
                        task.generator.delegated(delegated);
                        return;
                    }
                }
                switch (continuation.getResult()) {
                    case GeneratorYield ignored -> throw new PhpError("yield outside a generator task");
                    case GeneratorDelegate ignored -> throw new PhpError("yield from outside a generator task");
                    case Cooperate ignored -> resumeLater(task, ticket, null);
                    case Delay delay -> {
                        if (request.nativeReactor && delay.milliseconds > 0) {
                            var future = request.libuv(task.activation).timer(delay.milliseconds);
                            task.registration = new WaitRegistration(task, ticket, new Await(future));
                        } else {
                            timers.add(new Timer(deadline(Math.max(0, delay.milliseconds)), () -> resumeLater(task, ticket, null)));
                            wakeup.run();
                        }
                    }
                    case Await effect -> task.registration = new WaitRegistration(task, ticket, effect);
                    default -> throw new PhpError("Unsupported suspension effect");
                }
                return;
            }
            if (failure == null) task.activation.escape(result);
            try { task.activation.close(); }
            catch (PhpError cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            if (task.parents.isEmpty()) {
                if (task.generator != null) {
                    task.generator.completed(result, failure);
                    return;
                }
                if (task.scopeFailure != null) {
                    PhpValues.drop(result);
                    result = null;
                    failure = task.scopeFailure.original;
                    task.scopeFailure = null;
                    task.future.observed = true; // The failure was delivered through its scope.
                } else if (failure != null && failure.catchable && !task.future.observed
                        && task.future.heap != null && !task.scope.hostCancelled
                        && !failure.matches("Async\\AsyncCancellation")) {
                    task.scopeFailure = new ScopeFailure(task, failure);
                    var handler = request.context.asyncFunction("report_scope_failure");
                    task.activation = new Activation(request, handler, task, false,
                            new Argument[] {new Argument(task.scopeFailure, null)});
                    try { result = handler.target().call(task.activation); failure = null; }
                    catch (PhpError error) { result = null; failure = error; cancelScope(rootScope, error, task.future); }
                    continue;
                }
                if (failure == null) task.future.completeValue(result);
                else { PhpValues.drop(result); task.future.completion.completeExceptionally(failure); }
                if (task.future.heap != null) {
                    tasks.remove(task);
                    if (failure == null || task.future.observed || failure.matches("Async\\AsyncCancellation"))
                        task.scope.members.remove(task.future);
                    task.activation = null;
                    PhpValues.release(task.future);
                }
                memberFinished(task.scope);
                return;
            }
            var parent = task.parents.pop();
            task.activation = parent.activation;
            try { result = parent.continuation.continueWith(failure == null ? result : new Failure(failure)); failure = null; }
            catch (PhpError error) { failure = error; result = null; }
        }
    }

    private final class Resume implements Runnable {
        private final Task task;
        private final long ticket;
        private final Object value;
        Resume(Task task, long ticket, Object value) { this.task = task; this.ticket = ticket; this.value = value; }
        @Override public void run() {
            if (task.waiting == null || task.ticket != ticket || task.future.completion.isDone()) { PhpValues.drop(value); return; }
            var continuation = task.waiting;
            task.waiting = null;
            task.running = true;
            try {
                Object resumed = value;
                if (continuation.getResult() instanceof Await effect && effect.finish != null) {
                    try { resumed = effect.finish.apply(value); }
                    catch (PhpError error) { resumed = new Failure(error); }
                }
                // Inject cleanup/conversion errors into the guest continuation so its
                // catch/finally blocks run, just like an error from await itself.
                advance(task, continuation.continueWith(resumed), null);
            }
            catch (PhpError error) { advance(task, null, error); }
            finally { task.running = false; }
        }
    }
    private void resumeLater(Task task, long ticket, Object value) {
        if (closed) PhpValues.drop(value);
        else enqueue(new Resume(task, ticket, value));
    }

    void finishObjects() {
        var finalizers = request.heap.finalizers;
        if (finalizers == null || closed || pending() || rootScope.cancelled || request.fatalFailure != null) return;
        finalizers.scheduleShutdown();
        if (!finalizers.ready) return;
        var function = request.context.asyncFunction("drain_object_finalizers");
        var scope = newScope(null);
        do {
            var cleanup = spawn(new ObjectModel.Invocation(function, null, null, null), new Argument[0],
                    new ContextNode(null, java.util.Map.of()), false, scope);
            runUntil(cleanup);
            finalizers.scheduleShutdown();
        } while (finalizers.ready);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        abortDispatches(new PhpError("Request dispatcher is closed"));
        for (var listener : List.copyOf(listeners)) listener.close();
        for (var action : ready) if (action instanceof Resume resume) PhpValues.drop(resume.value);
        for (var task : tasks) {
            if (task.waiting != null && task.waiting.getResult() instanceof Await effect) effect.close();
            if (task.activation != null) task.activation.close();
            for (var parent : task.parents) parent.activation.close();
            if (task.future.heap != null) PhpValues.release(task.future);
            else if (!task.future.completion.isCompletedExceptionally()) PhpValues.drop(task.future.completion.getNow(null));
        }
        for (var future : external) if (!future.completion.isCompletedExceptionally()) PhpValues.drop(future.completion.getNow(null));
        for (var scope : scopes) {
            scope.releaseHandlers();
            for (var waiter : scope.drainWaiters) waiter.release();
            scope.drainWaiters.clear();
        }
        timers.clear();
        ready.clear();
        incoming.clear();
    }
}
