package graalphp.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.interop.TruffleObject;
import graalphp.frontend.Ir;
import graalphp.truffle.PhpContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Execution {
    private Execution() {}
    public record Function(String name, List<Ir.Parameter> parameters, CallTarget target, Path file,
                           String owner, String returnType) {}
    public record Unit(Path path, String content, Function main, Map<String, Function> functions,
                       Map<String, ObjectModel.Definition> classes) {}
    public record Argument(Object value, PhpValues.Location location, String name) implements AutoCloseable {
        public Argument(Object value, PhpValues.Location location) { this(value, location, null); }
        @Override public void close() { PhpValues.drop(value); }
    }
    public record NestedCall(ContinuationResult continuation, Activation activation) {}
    public record Failure(PhpError error) {}
    public static final class ContextNode {
        private static final Object NULL = new Object();
        public final ContextNode parent;
        private final java.util.concurrent.ConcurrentHashMap<String, Object> values;
        public ContextNode(ContextNode parent, Map<String, Object> values) {
            this.parent = parent; this.values = new java.util.concurrent.ConcurrentHashMap<>(values);
        }
        public Object read(String key) {
            for (var node = this; node != null; node = node.parent) {
                Object value = node.values.get(key);
                if (value != null) return value == NULL ? null : value;
            }
            return null;
        }
        public ContextNode with(String key, Object value) {
            values.put(key, value == null ? NULL : value); return this;
        }
        public ContextNode branch() { return new ContextNode(this, Map.of()); }
        public void promote() {
            for (var node = this; node != null; node = node.parent) {
                for (var value : node.values.values()) if (value instanceof SharedCounter counter) counter.promote();
            }
        }
    }

    /** Request data never lives in immutable code metadata or a thread-local. */
    public static final class Request implements AutoCloseable {
        public final PhpContext context;
        public final CodeRepository.Generation generation;
        public final PhpValues.Heap heap = new PhpValues.Heap();
        public final PhpValues.Scope globals = new PhpValues.Scope(heap);
        public final Map<String, PhpValues.Location> variables = new HashMap<>();
        public final Map<String, Function> functions = new HashMap<>();
        public final Map<String, ObjectModel.Definition> classes = new HashMap<>();
        public final Map<String, ObjectModel.RuntimeClass> resolvedClasses = new HashMap<>();
        private final Set<String> resolvingClasses = new HashSet<>();
        public final Set<Path> included = new HashSet<>();
        public final ClassLoading autoload = new ClassLoading();
        public final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        public final List<AutoCloseable> resources = new ArrayList<>();
        public final Scheduler scheduler;
        public final boolean hosted;
        public final boolean nativeReactor;
        private volatile LibuvReactor libuv;
        private final Map<String, WorkerPool> workers = new HashMap<>();

        public Request(PhpContext context, CodeRepository.Generation generation) {
            this.context = context; this.generation = generation;
            hosted = "1".equals(context.environment.getEnvironment().get("GRAALPHP_HOSTED"));
            nativeReactor = "libuv".equals(context.environment.getEnvironment().get("GRAALPHP_REACTOR"));
            scheduler = new Scheduler(this);
        }
        public LibuvReactor libuv(Activation caller) {
            if (libuv == null) libuv = new LibuvReactor(caller);
            return libuv;
        }
        public void pollIO() { if (libuv != null) libuv.step(0); }
        public void armHostIO() { if (libuv != null) libuv.armHost(); }
        public void wakeIO() { if (libuv != null) libuv.wakeup(); }
        public boolean awaitIO(long timeoutNanos) {
            if (libuv == null) return false;
            libuv.step(timeoutNanos);
            return true;
        }
        public PhpValues.Location global(String name) { return variables.computeIfAbsent(name, key -> globals.variable(null)); }
        public WorkerPool workers(Activation caller) {
            return workers(caller, "default");
        }
        public WorkerPool workers(Activation caller, String name) {
            if (name.isBlank()) throw new PhpError("Thread pool name must not be empty");
            return workers.computeIfAbsent(name, key -> new WorkerPool(caller, key));
        }
        public WorkerPool workers(Activation caller, String name, WorkerPool.Configuration defaults) {
            return workers.computeIfAbsent(name, key -> new WorkerPool(caller, key, defaults));
        }
        public int poolSize(String name) {
            var pool = workers.get(name);
            if (pool == null) throw new PhpError("ValueError", "Pool is not defined: " + name);
            return pool.workerCount();
        }
        public WorkerPool definePool(Activation caller, String name, WorkerPool.Configuration configuration) {
            if (name.isBlank()) throw new PhpError("ValueError", "Thread pool name must not be empty");
            var existing = workers.get(name);
            if (existing != null) {
                if (!existing.configuration.equals(configuration)) throw new PhpError("ValueError", "Pool is already defined with different limits: " + name);
                return existing;
            }
            var pool = new WorkerPool(caller, name, configuration);
            workers.put(name, pool);
            return pool;
        }
        public Function function(String name) {
            var function = functions.get(name.toLowerCase(java.util.Locale.ROOT));
            if (function == null) throw new PhpError("Undefined function " + name);
            return function;
        }
        public ObjectModel.RuntimeClass type(String name) {
            String key = name.toLowerCase(java.util.Locale.ROOT);
            var resolved = resolvedClasses.get(key);
            if (resolved != null) return resolved;
            var definition = classes.get(key);
            if (definition == null) throw new PhpError("Error", "Class \"" + name + "\" not found");
            if (!resolvingClasses.add(key)) throw new PhpError("Cyclic class inheritance: " + name);
            try {
                var parent = definition.parent() == null ? null : type(definition.parent());
                resolved = new ObjectModel.RuntimeClass(this, definition, parent);
                resolvedClasses.put(key, resolved);
                return resolved;
            } finally { resolvingClasses.remove(key); }
        }
        public void install(Unit unit) {
            for (var name : unit.functions.keySet()) if (functions.containsKey(name)) throw new PhpError("Cannot redeclare " + name);
            for (var name : unit.classes.keySet()) if (classes.containsKey(name)) throw new PhpError("Cannot redeclare class " + name);
            for (var entry : unit.functions.entrySet()) {
                if (functions.putIfAbsent(entry.getKey(), entry.getValue()) != null) throw new PhpError("Cannot redeclare " + entry.getKey());
            }
            classes.putAll(unit.classes);
            if (unit.path != null) included.add(unit.path);
        }
        @Override public void close() {
            PhpError failure = null;
            // Native calls must finish before their callbacks and captured guest values are released.
            for (var pool : workers.values()) {
                try { pool.close(); }
                catch (PhpError error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            // A stuck C call can still invoke its callback. Preserve its roots on failed shutdown.
            if (failure != null) throw failure;
            if (libuv != null) libuv.close();
            for (var resource : List.copyOf(resources).reversed()) {
                try { resource.close(); }
                catch (Exception error) {
                    if (failure == null) failure = new PhpError("Resource shutdown failed: " + error.getMessage());
                    else failure.addSuppressed(error);
                }
            }
            resources.clear();
            try { scheduler.close(); }
            finally { try { autoload.close(); } finally { globals.close(); } }
            if (failure != null) throw failure;
        }
    }

    public static final class Activation implements AutoCloseable {
        public final Request request;
        public final Function function;
        public final PhpValues.Scope locals;
        public final Map<String, PhpValues.Location> variables = new HashMap<>();
        public final Set<PhpValues.Owned> ownedTemporaries = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        public final List<AutoCloseable> resources = new ArrayList<>();
        public Scheduler.Task task;
        public Activation includedScope;
        public ObjectModel.RuntimeClass calledClass;
        public boolean synchronousCallback;
        public final boolean topLevel;
        private boolean closed;

        public Activation(Request request, Function function, Scheduler.Task task, boolean topLevel, Argument[] arguments) {
            this.request = request; this.function = function; this.task = task; this.topLevel = topLevel;
            locals = new PhpValues.Scope(request.heap);
            try {
                arguments = CallArguments.bind(function, arguments);
                for (int i = 0; i < function.parameters.size(); i++) {
                    var parameter = function.parameters.get(i);
                    if (parameter.variadic()) {
                        var array = variable(parameter.name());
                        array.set(locals.emptyArray());
                        for (int j = i; j < arguments.length; j++) {
                            if (parameter.reference()) {
                                if (arguments[j].location == null) throw new PhpError("Variadic reference requires a variable");
                                (arguments[j].name == null ? array.append() : array.element(arguments[j].name)).bind(arguments[j].location);
                            } else (arguments[j].name == null ? array.append() : array.element(arguments[j].name))
                                    .set(ObjectModel.checkType(arguments[j].value, parameter.type()));
                        }
                        break;
                    }
                    if (i >= arguments.length || arguments[i] == null) {
                        if (parameter.defaultValue() == null) throw new PhpError("Too few arguments for " + function.name);
                        Object value = ObjectModel.constant(request, parameter.defaultValue());
                        try { variable(parameter.name()).set(ObjectModel.checkType(value, parameter.type())); }
                        finally { PhpValues.drop(value); }
                        continue;
                    }
                    var argument = arguments[i];
                    if (parameter.reference()) {
                        if (argument.location == null) throw new PhpError("Argument " + parameter.name() + " must be a variable");
                        argument.close();
                        variable(parameter.name()).bind(argument.location);
                    } else variable(parameter.name()).set(ObjectModel.checkType(argument.value, parameter.type()));
                }
            } catch (RuntimeException error) { locals.close(); throw error; }
        }
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public PhpValues.Location variable(String name) {
            if (includedScope != null) return includedScope.variable(name);
            if (topLevel || Set.of("_GET", "_POST", "_SERVER", "_COOKIE", "_FILES", "_ENV", "_REQUEST", "_SESSION").contains(name)) return request.global(name);
            return variables.computeIfAbsent(name, key -> locals.variable(null));
        }
        public Object track(Object value) {
            if (value instanceof PhpValues.Owned owned) owned.track(ownedTemporaries);
            return value;
        }
        public Object escape(Object value) {
            if (value instanceof PhpValues.Owned owned) owned.track(null);
            return value;
        }
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void global(String name) { variable(name).bind(request.global(name)); }
        @Override public void close() {
            if (closed) return; closed = true;
            PhpError failure = null;
            for (var resource : resources) {
                try { resource.close(); }
                catch (Exception error) {
                    if (failure == null) failure = error instanceof PhpError php ? php : new PhpError(error.getMessage());
                    else failure.addSuppressed(error);
                }
            }
            try { List.copyOf(ownedTemporaries).forEach(PhpValues::drop); }
            finally { locals.close(); }
            if (failure != null) throw failure;
        }
    }

    /** An iterator is an activation resource, so suspension and finally preserve its lifetime. */
    public static final class Cursor implements AutoCloseable {
        private final PhpValues.ReferenceIterator references;
        private final Object snapshot;
        private final List<Object> keys;
        private int position;
        public Object key;
        public Cursor(Object source, boolean reference) {
            if (reference) {
                references = ((PhpValues.Location) source).iterateReferences(); snapshot = null; keys = null;
            } else {
                references = null; snapshot = source; keys = PhpValues.keys(source);
            }
        }
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public boolean next(PhpValues.Location value, PhpValues.Location keyLocation) {
            if (references != null) {
                if (!references.next(value)) return false;
                key = references.key;
            } else {
                if (position == keys.size()) return false;
                key = keys.get(position++);
                var read = PhpValues.element(snapshot, key);
                try { value.set(PhpValues.unwrap(read)); } finally { PhpValues.drop(read); }
            }
            if (keyLocation != null) keyLocation.set(key);
            return true;
        }
        @Override public void close() { if (references != null) references.close(); else PhpValues.drop(snapshot); }
    }
}
