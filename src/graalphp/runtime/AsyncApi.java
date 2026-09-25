package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import static graalphp.runtime.Execution.*;

/** Public names and contracts follow true-async/php-async v0.10.0. */
public final class AsyncApi {
    private AsyncApi() {}
    public static final Object UNHANDLED = new Object();

    public static final class ContextValue implements TruffleObject, AutoCloseable {
        final ContextValue parent;
        final PhpValues.Scope storage;
        final Map<Object, PhpValues.Location> values = new HashMap<>();
        ContextValue(Request request, ContextValue parent) {
            this.parent = parent;
            storage = new PhpValues.Scope(request.heap);
            request.resources.add(this);
        }
        PhpValues.Location lookup(Object key, boolean local) {
            for (var context = this; context != null; context = local ? null : context.parent) {
                var location = context.values.get(key);
                if (location != null) return location;
            }
            return null;
        }
        @Override public void close() { storage.close(); values.clear(); }
    }

    public static final class FutureState implements TruffleObject {
        final Scheduler.Future future;
        FutureState(Request request) { future = new Scheduler.Future(); request.scheduler.keep(future); }
    }

    public static ContextValue context(Request request, Scheduler.Scope scope) {
        if (scope.context == null) scope.context = new ContextValue(request,
                scope.parent == null ? null : context(request, scope.parent));
        return scope.context;
    }

    @TruffleBoundary
    public static Object allocate(Activation caller, String name) {
        var request = caller.request;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "async\\mutex" -> new AsyncMutex();
            case "async\\threadchannel" -> new ThreadChannel();
            case "async\\scope" -> request.scheduler.newScope(null);
            case "async\\channel" -> {
                var channel = new AsyncChannel(request);
                request.resources.add(channel);
                yield channel;
            }
            case "async\\futurestate" -> new FutureState(request);
            case "async\\future" -> {
                var future = new Scheduler.Future();
                request.scheduler.keep(future);
                yield future;
            }
            default -> UNHANDLED;
        };
    }

    @TruffleBoundary
    public static Object function(Activation caller, String name, Argument[] args,
                                  com.oracle.truffle.api.nodes.IndirectCallNode call) {
        var scheduler = caller.request.scheduler;
        if (CallArguments.hasNames(args)) args = namedFunction(name, args);
        switch (name.toLowerCase(Locale.ROOT)) {
            case "async\\spawn":
                arity(name, args, 1);
                return scheduler.spawn(ObjectModel.callable(caller, value(args, 0)), copy(args, 1),
                        caller.task.context.branch(), false, caller.task.scope);
            case "async\\spawn_with":
                arity(name, args, 2);
                if (!(value(args, 0) instanceof Scheduler.Scope scope)) throw new PhpError("Expected ScopeProvider");
                return scheduler.spawn(ObjectModel.callable(caller, value(args, 1)), copy(args, 2),
                        caller.task.context.branch(), false, scope);
            case "async\\delay":
                arity(name, args, 1);
                long milliseconds = Operations.number(value(args, 0)).longValue();
                if (milliseconds < 0) throw new PhpError("Delay must not be negative");
                return new Scheduler.Delay(milliseconds);
            case "async\\suspend": return new Scheduler.Delay(0);
            case "async\\await":
                arity(name, args, 1);
                return Scheduler.awaitValue(caller, future(value(args, 0)), optionalFuture(args, 1));
            case "async\\await_all": case "async\\await_all_or_fail":
            case "async\\await_any_or_fail": case "async\\await_first_success":
            case "async\\await_any_of": case "async\\await_any_of_or_fail":
                return AsyncAwait.await(caller, name.substring(name.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT), args);
            case "async\\protect":
                arity(name, args, 1);
                if (!(value(args, 0) instanceof PhpValues.PhpObject closure)
                        || !(closure.descriptor instanceof ObjectModel.ClosureData)) {
                    throw new PhpError("TypeError", "Async\\protect(): Argument #1 ($closure) must be of type Closure");
                }
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("protected_call"), args, call);
            case "async\\timeout":
                arity(name, args, 1);
                return scheduler.timeout(Operations.number(value(args, 0)).longValue());
            case "async\\current_coroutine": return caller.task.future;
            case "async\\current_context": return context(caller.request, caller.task.scope);
            case "async\\root_context": return context(caller.request, scheduler.rootScope);
            case "async\\request_context": return null; // CLI has no server-assigned request scope.
            case "async\\coroutine_context":
                if (caller.task.privateContext == null) caller.task.privateContext = new ContextValue(caller.request, null);
                return caller.task.privateContext;
            case "async\\available_parallelism": return (long) Runtime.getRuntime().availableProcessors();
            default: return UNHANDLED;
        }
    }

    @TruffleBoundary
    public static Object staticMethod(Activation caller, String type, String name, Argument[] args) {
        if (type.equalsIgnoreCase("Async\\Scope") && name.equalsIgnoreCase("inherit")) {
            args = CallArguments.builtin(name, args, java.util.List.of("scope"), 0, (Object) null);
            var parent = args.length == 0 || value(args, 0) == null ? caller.task.scope : (Scheduler.Scope) value(args, 0);
            return caller.request.scheduler.newScope(parent);
        }
        if (type.equalsIgnoreCase("Async\\Future")) {
            CallArguments.positionalOnly(type + "::" + name, args);
            var future = new Scheduler.Future();
            switch (name.toLowerCase(Locale.ROOT)) {
                case "completed" -> future.completion.complete(args.length == 0 ? null : PhpValues.own(value(args, 0)));
                case "failed" -> {
                    arity(name, args, 1);
                    future.completion.completeExceptionally(error(value(args, 0)));
                }
                default -> { return UNHANDLED; }
            }
            caller.request.scheduler.keep(future);
            return future;
        }
        return UNHANDLED;
    }

    @TruffleBoundary
    public static Object method(Activation caller, Object receiver, String name, Argument[] args) {
        receiver = PhpValues.unwrap(receiver);
        name = name.toLowerCase(Locale.ROOT);
        var scheduler = caller.request.scheduler;
        if (receiver instanceof Scheduler.Future || receiver instanceof Scheduler.Scope || receiver instanceof AsyncChannel
                || receiver instanceof ContextValue || receiver instanceof FutureState || receiver instanceof PhpError) {
            CallArguments.positionalOnly(className(receiver) + "::" + name, args);
        }
        if (receiver instanceof ThreadChannel channel) {
            switch (name) {
                case "__construct":
                    args = CallArguments.builtin(name, args, java.util.List.of("capacity"), 0, 16L);
                    long capacity = Operations.number(value(args, 0)).longValue();
                    if (capacity < 1 || capacity > Integer.MAX_VALUE) throw new PhpError("ValueError", "Invalid ThreadChannel capacity");
                    channel.initialize((int) capacity); return null;
                case "send":
                    args = CallArguments.builtin(name, args, java.util.List.of("value", "cancellationToken"), 1, (Object) null);
                    var sent = channel.send(value(args, 0)); scheduler.keep(sent);
                    return Scheduler.awaitValue(caller, sent, optionalFuture(args, 1));
                case "recv":
                    args = CallArguments.builtin(name, args, java.util.List.of("cancellationToken"), 0, (Object) null);
                    var received = channel.receive(); scheduler.keep(received);
                    return Scheduler.awaitValue(caller, received, optionalFuture(args, 0));
                default:
                    CallArguments.builtin(name, args, java.util.List.of(), 0);
                    return switch (name) {
                        case "close" -> { channel.close(); yield null; }
                        case "isclosed" -> channel.isClosed();
                        case "capacity" -> (long) channel.capacity();
                        case "count" -> (long) channel.count();
                        case "isempty" -> channel.count() == 0;
                        case "isfull" -> channel.count() == channel.capacity();
                        default -> throw new PhpError("Error", "Undefined method Async\\ThreadChannel::" + name);
                    };
            }
        }
        if (receiver instanceof PhpError error) {
            return switch (name) {
                case "getmessage" -> error.getMessage();
                case "getprevious" -> error.previous;
                case "getcode" -> 0L;
                default -> UNHANDLED;
            };
        }
        if (receiver instanceof Scheduler.Scope scope) {
            switch (name) {
                case "__construct": return null;
                case "providescope": return scope;
                case "spawn":
                    arity(name, args, 1);
                    return scheduler.spawn(ObjectModel.callable(caller, value(args, 0)), copy(args, 1),
                            caller.task.context.branch(), false, scope);
                case "awaitcompletion":
                    arity(name, args, 1);
                    var token = future(value(args, 0));
                    token.observed = true;
                    if (scope.disposed) return null;
                    if (scope.cancelled) throw new PhpError("Async\\AsyncCancellation", "The scope has been cancelled");
                    scheduler.checkScopeWait(caller.task, scope);
                    return Scheduler.awaitValue(caller, scheduler.scopeCompletion(scope), token);
                case "awaitaftercancellation":
                    if (args.length > 0 && value(args, 0) != null) throw new PhpError("Scope error handlers are not implemented");
                    var cancellation = optionalFuture(args, 1);
                    if (cancellation != null) cancellation.observed = true;
                    if (scope.disposed) return null;
                    if (!scope.cancelled) throw new PhpError("Async\\AsyncException", "Attempt to await a Scope that has not been cancelled");
                    scheduler.checkScopeWait(caller.task, scope);
                    return Scheduler.awaitValue(caller, scheduler.scopeCompletion(scope), cancellation);
                case "cancel":
                    var reason = args.length == 0 || value(args, 0) == null
                            ? new PhpError("Async\\AsyncCancellation", "Scope cancelled") : error(value(args, 0));
                    if (!reason.matches("Async\\AsyncCancellation")) throw new PhpError("TypeError", "Expected AsyncCancellation");
                    scheduler.cancelScope(scope, reason);
                    return null;
                case "getchildscopes":
                    return caller.track(PhpValues.own(caller.locals.array(scheduler.children(scope).toArray())));
                case "dispose":
                    scope.disposed = true;
                    scheduler.cancelScope(scope, new PhpError("Async\\AsyncCancellation", "Scope disposed"));
                    return null;
                case "isfinished": return scheduler.finished(scope);
                case "isclosed": return scope.disposed;
                case "iscancelled": return scope.cancelled;
                default: throw new PhpError("Unimplemented TrueAsync Scope::" + name);
            }
        }
        if (receiver instanceof ContextValue context) {
            arity(name, args, 1);
            Object key = value(args, 0);
            if (!(key instanceof String || key instanceof TruffleObject)) throw new PhpError("Context key must be string|object");
            boolean local = name.endsWith("local");
            var location = context.lookup(key, local);
            switch (name) {
                case "find": case "findlocal": return location == null ? null : caller.track(PhpValues.own(location.read()));
                case "get": case "getlocal":
                    if (location == null) throw new PhpError("Async\\ContextException", "Context key not found");
                    return caller.track(PhpValues.own(location.read()));
                case "has": case "haslocal": return location != null;
                case "set":
                    arity(name, args, 2);
                    if (context.values.containsKey(key) && (args.length < 3 || !Operations.truth(value(args, 2)))) {
                        throw new PhpError("Async\\AsyncException", "Context key already exists and replace is false");
                    }
                    var destination = context.values.get(key);
                    if (destination == null) {
                        context.storage.variable(key);
                        destination = context.storage.variable(null);
                        context.values.put(key, destination);
                    }
                    destination.set(value(args, 1));
                    return context;
                case "unset":
                    var removed = context.values.remove(key);
                    if (removed != null) removed.unset();
                    return context;
                default: throw new PhpError("Unimplemented TrueAsync Context::" + name);
            }
        }
        if (receiver instanceof AsyncChannel channel) {
            if (channel.request != caller.request) throw new PhpError("Use ThreadChannel for cross-thread messaging");
            switch (name) {
                case "__construct":
                    channel.capacity = args.length == 0 ? 0 : Math.toIntExact(Operations.number(value(args, 0)).longValue());
                    if (channel.capacity < 0) throw new PhpError("ValueError", "Channel capacity must not be negative");
                    if (args.length > 1) throw new PhpError("Channel deadlock timeout options are not implemented");
                    return null;
                case "send":
                    arity(name, args, 1);
                    return channel.send(value(args, 0), optionalFuture(args, 1));
                case "sendasync":
                    arity(name, args, 1);
                    return channel.trySend(value(args, 0));
                case "recv": return new Scheduler.Await(channel.receive(), optionalFuture(args, 0));
                case "recvasync": return channel.receive();
                case "close": channel.closeChannel(); return null;
                case "isclosed": return channel.closed;
                case "capacity": return (long) channel.capacity;
                case "count": return (long) channel.count();
                case "isempty": return channel.count() == 0;
                case "isfull": return channel.count() >= channel.capacity;
                default: throw new PhpError("Unimplemented TrueAsync Channel::" + name);
            }
        }
        if (receiver instanceof FutureState state) {
            switch (name) {
                case "__construct": return null;
                case "complete":
                    arity(name, args, 1);
                    if (state.future.completion.isDone()) throw new PhpError("FutureState is already completed");
                    state.future.completion.complete(PhpValues.own(value(args, 0)));
                    return null;
                case "error":
                    arity(name, args, 1);
                    if (!state.future.completion.completeExceptionally(error(value(args, 0)))) throw new PhpError("FutureState is already completed");
                    return null;
                case "iscompleted": return state.future.completion.isDone();
                case "ignore": state.future.observed = true; return null;
                default: throw new PhpError("Unimplemented TrueAsync FutureState::" + name);
            }
        }
        if (receiver instanceof Scheduler.Future future) {
            var task = future.task;
            switch (name) {
                case "__construct":
                    arity(name, args, 1);
                    if (!(value(args, 0) instanceof FutureState state)) throw new PhpError("Future requires FutureState");
                    state.future.completion.whenComplete((value, error) -> {
                        if (error == null) future.completion.complete(PhpValues.own(PhpValues.unwrap(value)));
                        else future.completion.completeExceptionally(error);
                    });
                    return null;
                case "await": return Scheduler.awaitValue(caller, future, optionalFuture(args, 0));
                case "map": case "catch": case "finally":
                    arity(name, args, 1);
                    String operation = switch (name) {
                        case "map" -> "map";
                        case "catch" -> "catch_error";
                        default -> task == null ? "finally_run" : "coroutine_finally";
                    };
                    return AsyncBuiltins.chain(caller, future, value(args, 0), operation);
                case "cancel":
                    PhpError reason = args.length == 0 || value(args, 0) == null ? null : error(value(args, 0));
                    if (reason != null && !reason.matches("Async\\AsyncCancellation")) throw new PhpError("TypeError", "Expected AsyncCancellation");
                    scheduler.cancel(future, reason);
                    return null;
                case "ignore": future.observed = true; return future;
                case "iscompleted": return future.completion.isDone();
                case "iscancelled": return (task == null ? future.cancellationRequested : task.cancelled) && future.completion.isDone();
                case "iscancellationrequested": return !future.completion.isDone()
                        && (task == null ? future.cancellationRequested : task.cancelled);
                case "isstarted": return task != null && task.started;
                case "isqueued": return task != null && !task.started;
                case "isrunning": return task != null && task.running;
                case "issuspended": return task != null && task.started && !task.running && !future.completion.isDone();
                case "getcontext":
                    if (task == null) throw new PhpError("Error", "Undefined method Async\\Future::getContext()");
                    if (task.privateContext == null) task.privateContext = new ContextValue(caller.request, null);
                    return task.privateContext;
                case "getresult":
                    if (!future.completion.isDone()) return null;
                    future.observed = true;
                    return caller.track(PhpValues.own(PhpValues.unwrap(Scheduler.result(future))));
                case "getexception":
                    if (!future.completion.isCompletedExceptionally()) return null;
                    try { Scheduler.result(future); return null; } catch (PhpError error) { return error; }
                default: throw new PhpError("Unimplemented TrueAsync awaitable method " + name);
            }
        }
        return UNHANDLED;
    }

    public static String className(Object value) {
        if (value instanceof Scheduler.Future future) return future.task == null ? "Async\\Future" : "Async\\Coroutine";
        if (value instanceof Scheduler.Scope) return "Async\\Scope";
        if (value instanceof AsyncChannel) return "Async\\Channel";
        if (value instanceof ContextValue) return "Async\\Context";
        if (value instanceof FutureState) return "Async\\FutureState";
        if (value instanceof AsyncMutex) return "Async\\Mutex";
        if (value instanceof ThreadChannel) return "Async\\ThreadChannel";
        if (value instanceof PhpError error) return error.type;
        return null;
    }

    private static Object value(Argument[] args, int index) { return PhpValues.unwrap(args[index].value()); }
    private static void arity(String name, Argument[] args, int minimum) {
        if (args.length < minimum) throw new PhpError("Too few arguments for " + name);
    }
    private static PhpError error(Object value) {
        if (value instanceof PhpError error) return error;
        throw new PhpError("Expected Throwable");
    }
    public static Scheduler.Future future(Object value) {
        if (value instanceof Scheduler.Future future) return future;
        if (value instanceof AsyncChannel channel) return channel.receive();
        if (value instanceof ThreadChannel channel) return channel.receive();
        throw new PhpError("Expected Async\\Completable");
    }
    private static Scheduler.Future optionalFuture(Argument[] args, int index) {
        return args.length <= index || value(args, index) == null ? null : future(value(args, index));
    }
    private static Argument[] copy(Argument[] args, int start) {
        var result = new Argument[args.length - start];
        for (int i = start; i < args.length; i++) result[i - start] = new Argument(PhpValues.own(value(args, i)), args[i].location(), args[i].name());
        return result;
    }

    private static Argument[] namedFunction(String name, Argument[] args) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "async\\await" -> CallArguments.builtin(name, args, java.util.List.of("awaitable", "cancellation"), 1, (Object) null);
            case "async\\delay", "async\\timeout" -> CallArguments.builtin(name, args, java.util.List.of("ms"), 1);
            case "async\\protect" -> CallArguments.builtin(name, args, java.util.List.of("closure"), 1);
            case "async\\await_all", "async\\await_any_of" -> CallArguments.builtin(name, args,
                    name.endsWith("await_all") ? java.util.List.of("triggers", "cancellation", "preserveKeyOrder", "fillNull")
                            : java.util.List.of("count", "triggers", "cancellation", "preserveKeyOrder", "fillNull"),
                    name.endsWith("await_all") ? 1 : 2, null, true, false);
            case "async\\await_all_or_fail", "async\\await_any_of_or_fail" -> CallArguments.builtin(name, args,
                    name.endsWith("await_all_or_fail") ? java.util.List.of("triggers", "cancellation", "preserveKeyOrder")
                            : java.util.List.of("count", "triggers", "cancellation", "preserveKeyOrder"),
                    name.endsWith("await_all_or_fail") ? 1 : 2, null, true);
            case "async\\await_any_or_fail", "async\\await_first_success" -> CallArguments.builtin(name, args,
                    java.util.List.of("triggers", "cancellation"), 1, (Object) null);
            default -> { CallArguments.positionalOnly(name, args); yield args; }
        };
    }
}
