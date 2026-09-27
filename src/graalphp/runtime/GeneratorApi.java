package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.List;
import java.util.Locale;
import static graalphp.runtime.Execution.*;

/** PHP generators are paused Truffle continuations driven only by their consumer. */
public final class GeneratorApi {
    private GeneratorApi() {}

    public static final Object AUTO_KEY = new Object();
    public static final String SOURCE = """
        function generator_delegate_values($source) {
            foreach ($source as $key => $value) yield $key => $value;
        }
        """;

    public static final String TYPES = """
        final class Generator implements Iterator {
            public function current(): mixed {}
            public function next(): void {}
            public function key(): mixed {}
            public function valid(): bool {}
            public function rewind(): void {}
            public function send(mixed $value): mixed {}
            public function throw(object $exception): mixed {}
            public function getReturn(): mixed {}
        }
        """;

    private enum Operation { CURRENT, KEY, VALID, REWIND, NEXT, SEND, THROW }

    public static final class State implements TruffleObject, AutoCloseable {
        final Request request;
        final Scheduler.Task task;
        PhpValues.PhpObject object;
        final PhpValues.Scope storage;
        final PhpValues.Location current;
        final PhpValues.Location key;
        final PhpValues.Location returned;
        final boolean byReference;
        PhpValues.Location currentReference;
        Scheduler.Future pending;
        Operation operation;
        Object resume;
        boolean skipFirst;
        boolean started;
        boolean atYield;
        boolean completed;
        boolean returnedNormally;
        boolean closed;
        State delegate;
        Object delegateOwner;
        long yieldedCount;
        long nextKey;

        State(Request request, Scheduler.Task task) {
            this.request = request;
            this.task = task;
            storage = new PhpValues.Scope(request.heap);
            current = storage.variable(null);
            key = storage.variable(null);
            returned = storage.variable(null);
            byReference = task.activation.function.returnsReference();
            task.generator = this;
        }

        private PhpValues.Location current() { return current; }
        private PhpValues.Location key() { return key; }
        private PhpValues.Location returned() { return returned; }

        Object read(Activation caller, PhpValues.Location location) {
            return caller.track(PhpValues.own(location.readOrNull()));
        }

        Scheduler.Future request(Operation operation, Object resume, boolean skipFirst) {
            if (pending != null) throw new PhpError("Error", "Generator is already running");
            var future = new Scheduler.Future();
            future.task = task;
            future.cancelOnAbort = true;
            pending = future;
            this.operation = operation;
            this.resume = resume;
            this.skipFirst = skipFirst;
            if (!started) {
                started = true;
                request.scheduler.startGenerator(task);
            } else if (delegate != null) {
                driveDelegate();
            } else {
                request.scheduler.resumeGenerator(task, takeResume());
            }
            return future;
        }

        private Object takeResume() {
            Object value = resume;
            resume = null;
            return value;
        }

        public void yielded(Scheduler.GeneratorYield yielded) {
            try {
                Object key = yielded.automatic() ? nextKey++ : PhpValues.unwrap(yielded.key());
                if (!yielded.automatic() && key instanceof Long number && number >= nextKey)
                    nextKey = number == Long.MAX_VALUE ? Long.MAX_VALUE : number + 1;
                Object rawValue;
                if (yielded.reference() && yielded.value() instanceof PhpValues.Location location) {
                    currentReference = location;
                    rawValue = location.readOrNull();
                } else {
                    rawValue = PhpValues.unwrap(yielded.value());
                    currentReference = yielded.reference() ? storage.variable(rawValue) : null;
                }
                current().set(rawValue);
                this.key().set(key);
                atYield = true;
                yieldedCount++;
            } finally {
                PhpValues.drop(yielded.key());
                PhpValues.drop(yielded.value());
            }
            if (pending == null) return;
            if (skipFirst) {
                skipFirst = false;
                atYield = false;
                request.scheduler.resumeGenerator(task, takeResume());
                return;
            }
            settle(null);
        }

        public void delegated(Scheduler.GeneratorDelegate effect) {
            Object source = effect.source();
            try {
                Object raw = PhpValues.unwrap(source);
                if (isGenerator(raw)) {
                    delegateOwner = source;
                    source = null;
                    delegate = (State) ((PhpValues.PhpObject) raw).descriptor;
                } else {
                    if (!IterationApi.iterable(raw))
                        throw new PhpError("Error", "Can use yield from only with arrays and Traversables");
                    var function = request.context.asyncFunction("generator_delegate_values");
                    var argument = new Argument(PhpValues.own(raw), null);
                    Object adapter;
                    try {
                        adapter = GeneratorApi.create(task.activation,
                                new ObjectModel.Invocation(function, null, null, null), new Argument[] {argument});
                    } finally {
                        argument.close();
                    }
                    task.activation.escape(adapter);
                    delegateOwner = adapter;
                    delegate = (State) ((PhpValues.PhpObject) PhpValues.unwrap(adapter)).descriptor;
                }
            } finally {
                PhpValues.drop(source);
            }
            driveDelegate();
        }

        private void driveDelegate() {
            if (delegate == null) return;
            if (delegate.completed) {
                finishDelegate(null);
                return;
            }
            Operation delegatedOperation = operation;
            Object delegatedResume = takeResume();
            boolean delegatedSkip = skipFirst;
            skipFirst = false;

            if (!delegatedSkip && delegate.atYield
                    && (delegatedOperation == Operation.CURRENT || delegatedOperation == Operation.KEY
                    || delegatedOperation == Operation.VALID || delegatedOperation == Operation.REWIND)) {
                PhpValues.drop(delegatedResume);
                exposeDelegate();
                return;
            }
            if (delegate.atYield) delegate.atYield = false;
            Scheduler.Future future;
            try {
                future = delegate.request(delegatedOperation, delegatedResume, delegatedSkip);
            } catch (RuntimeException error) {
                PhpValues.drop(delegatedResume);
                throw error;
            }
            future.completion.whenComplete((value, error) -> request.scheduler.enqueue(() -> {
                if (error != null) {
                    Throwable cause = error;
                    while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                        cause = cause.getCause();
                    finishDelegate(cause instanceof PhpError php ? php : new PhpError(String.valueOf(cause)));
                } else if (delegate != null && delegate.completed) {
                    finishDelegate(null);
                } else {
                    exposeDelegate();
                }
            }));
        }

        private void exposeDelegate() {
            if (delegate == null || delegate.completed) {
                finishDelegate(null);
                return;
            }
            current().set(delegate.current().readOrNull());
            currentReference = byReference
                    ? delegate.currentReference != null ? delegate.currentReference : storage.variable(delegate.current().readOrNull())
                    : null;
            key().set(delegate.key().readOrNull());
            atYield = true;
            yieldedCount++;
            settle(null);
        }

        private void finishDelegate(PhpError failure) {
            State finished = delegate;
            Object owner = delegateOwner;
            delegate = null;
            delegateOwner = null;
            atYield = false;
            Object returned = null;
            if (failure == null && finished != null && finished.completed)
                returned = PhpValues.own(finished.returned().readOrNull());
            PhpValues.drop(owner);
            request.scheduler.resumeGenerator(task, failure == null ? returned : new Failure(failure));
        }

        public void completed(Object result, PhpError failure) {
            completed = true;
            atYield = false;
            current().set(null);
            key().set(null);
            currentReference = null;
            boolean forcedClose = closed && failure != null && !failure.catchable
                    && failure.type.equals("GeneratorExit");
            if (forcedClose) {
                task.future.completion.complete(null);
            } else if (failure == null) {
                returnedNormally = true;
                returned().set(PhpValues.unwrap(result));
                task.future.completion.complete(PhpValues.unwrap(result));
            } else {
                task.future.completion.completeExceptionally(failure);
            }
            PhpValues.drop(result);
            if (pending != null) settle(forcedClose ? new PhpError("Generator closed") : failure);
            if (closed) {
                storage.close();
                request.scheduler.generatorClosed();
            }
        }

        private void settle(PhpError failure) {
            var future = pending;
            var completedOperation = operation;
            pending = null;
            operation = null;
            skipFirst = false;
            PhpValues.drop(resume);
            resume = null;
            if (failure != null) {
                future.completion.completeExceptionally(failure);
                return;
            }
            Object result = switch (completedOperation) {
                case CURRENT, SEND, THROW -> completed ? null : current().readOrNull();
                case KEY -> completed ? null : key().readOrNull();
                case VALID -> !completed;
                case REWIND, NEXT -> null;
            };
            future.completion.complete(result);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            PhpValues.drop(resume);
            PhpValues.drop(delegateOwner);
            resume = null;
            delegateOwner = null;
            delegate = null;
            if (pending != null && !pending.completion.isDone())
                pending.completion.completeExceptionally(new PhpError("Generator closed"));
            pending = null;
            if (!started || completed) {
                request.scheduler.closeGenerator(task);
                storage.close();
            } else {
                request.scheduler.closeGenerator(task);
            }
        }
    }

    public static Object create(Activation caller, ObjectModel.Invocation invocation, Argument[] arguments) {
        var task = caller.request.scheduler.createGenerator(caller, invocation, arguments);
        var state = new State(caller.request, task);
        var object = new PhpValues.PhpObject(caller.request.heap, state);
        state.object = object;
        Object owned = PhpValues.own(object);
        String declared = invocation.function().returnType();
        if (declared != null) {
            String called = invocation.calledClass() == null
                    ? invocation.function().owner() : invocation.calledClass().definition.name();
            String type = TypeRelations.contextual(declared, caller.request, invocation.function().owner(), called);
            if (!TypeRelations.accepts(object, type)) {
                task.activation.close();
                PhpValues.drop(owned);
                throw PhpError.fatal("Generator return type must be a supertype of Generator, " + declared + " given");
            }
        }
        return caller.track(owned);
    }

    public static boolean isGenerator(Object value) {
        value = PhpValues.unwrap(value);
        return value instanceof PhpValues.PhpObject object && object.descriptor instanceof State;
    }

    public static boolean isA(Object value, String type) {
        if (!isGenerator(value)) return false;
        return List.of("Generator", "Iterator", "Traversable").stream().anyMatch(name -> name.equalsIgnoreCase(type));
    }

    public static boolean byReference(Object value) {
        value = PhpValues.unwrap(value);
        return value instanceof PhpValues.PhpObject object && object.descriptor instanceof State state && state.byReference;
    }

    public static PhpValues.Location currentReference(Object value) {
        value = PhpValues.unwrap(value);
        if (!(value instanceof PhpValues.PhpObject object) || !(object.descriptor instanceof State state)
                || state.currentReference == null)
            throw new PhpError("Error", "Generator does not expose a reference at the current yield");
        return state.currentReference;
    }

    private static Scheduler.Await await(PhpValues.PhpObject object, Scheduler.Future future) {
        Object keepalive = PhpValues.own(object);
        future.completion.whenComplete((value, error) -> PhpValues.drop(keepalive));
        return new Scheduler.Await(future);
    }

    public static Object method(Activation caller, Object receiver, String name, Argument[] arguments, IndirectCallNode call) {
        Object raw = PhpValues.unwrap(receiver);
        if (!(raw instanceof PhpValues.PhpObject object) || !(object.descriptor instanceof State state))
            return AsyncApi.UNHANDLED;
        name = name.toLowerCase(Locale.ROOT);
        if (name.equals("__construct")) throw new PhpError("Error", "The Generator class is reserved for internal use and cannot be manually instantiated");
        return switch (name) {
            case "current" -> {
                CallArguments.builtin(name, arguments, List.of(), 0);
                if (state.completed) yield null;
                if (state.atYield) yield state.read(caller, state.current());
                yield await(object, state.request(Operation.CURRENT, null, false));
            }
            case "key" -> {
                CallArguments.builtin(name, arguments, List.of(), 0);
                if (state.completed) yield null;
                if (state.atYield) yield state.read(caller, state.key());
                yield await(object, state.request(Operation.KEY, null, false));
            }
            case "valid" -> {
                CallArguments.builtin(name, arguments, List.of(), 0);
                if (state.completed) yield false;
                if (state.atYield) yield true;
                yield await(object, state.request(Operation.VALID, null, false));
            }
            case "rewind" -> {
                CallArguments.builtin(name, arguments, List.of(), 0);
                if (state.completed || state.started && !state.atYield || state.atYield && state.yieldedCount != 1)
                    throw new PhpError("Exception", "Cannot rewind a generator that was already run");
                if (state.atYield) yield null;
                yield await(object, state.request(Operation.REWIND, null, false));
            }
            case "next" -> {
                CallArguments.builtin(name, arguments, List.of(), 0);
                if (state.completed) yield null;
                boolean skip = !state.started;
                if (state.atYield) state.atYield = false;
                yield await(object, state.request(Operation.NEXT, null, skip));
            }
            case "send" -> {
                var args = CallArguments.builtin(name, arguments, List.of("value"), 1);
                if (state.completed) yield null;
                Object value = PhpValues.own(PhpValues.unwrap(args[0].value()));
                boolean skip = !state.started;
                if (state.atYield) state.atYield = false;
                yield await(object, state.request(Operation.SEND, value, skip));
            }
            case "throw" -> {
                var args = CallArguments.builtin(name, arguments, List.of("exception"), 1);
                Object exception = PhpValues.unwrap(args[0].value());
                if (!(exception instanceof PhpError error))
                    throw new PhpError("TypeError", "Generator::throw(): Argument #1 ($exception) must be of type Throwable");
                if (state.completed) throw error;
                boolean skip = !state.started;
                if (state.atYield) state.atYield = false;
                yield await(object, state.request(Operation.THROW, new Failure(error), skip));
            }
            case "getreturn" -> {
                CallArguments.builtin(name, arguments, List.of(), 0);
                if (!state.completed || !state.returnedNormally)
                    throw new PhpError("Exception", "Cannot get return value of a generator that hasn't returned");
                yield state.read(caller, state.returned());
            }
            default -> throw new PhpError("Error", "Call to undefined method Generator::" + name + "()");
        };
    }
}
