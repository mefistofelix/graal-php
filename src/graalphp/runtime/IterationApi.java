package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import graalphp.frontend.Ir;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import static graalphp.runtime.Execution.*;

/** Iterator protocols use ordinary resumable PHP calls; arrays retain their existing cursor semantics. */
public final class IterationApi {
    private IterationApi() {}
    public static final String TYPES = """
        interface Traversable {}
        interface Iterator extends Traversable {
            public function current(): mixed;
            public function next(): void;
            public function key(): mixed;
            public function valid(): bool;
            public function rewind(): void;
        }
        interface IteratorAggregate extends Traversable { public function getIterator(): Traversable; }
        interface Countable { public function count(): int; }
        #[Attribute(1)]
        final class Attribute {
            public const TARGET_CLASS = 1;
            public const TARGET_FUNCTION = 2;
            public const TARGET_METHOD = 4;
            public const TARGET_PROPERTY = 8;
            public const TARGET_CLASS_CONSTANT = 16;
            public const TARGET_PARAMETER = 32;
            public const TARGET_ALL = 63;
            public const IS_REPEATABLE = 64;
            public int $flags;
            public function __construct(int $flags = 63) { $this->flags = $flags; }
        }
        #[Attribute(4)] final class ReturnTypeWillChange {}
        """;

    public static final String SOURCE = """
        function iterator_step($state) {
            if (__iter_started($state)) {
                __iter_call($state, 'next');
            } else {
                while (__iter_aggregate($state)) {
                    __iter_select($state, __iter_call($state, 'getIterator'));
                }
                __iter_ready($state);
                __iter_call($state, 'rewind');
            }
            if (!__iter_call($state, 'valid')) return false;
            $value = __iter_want_value($state) ? __iter_call($state, 'current') : null;
            $key = __iter_want_key($state) ? __iter_call($state, 'key') : null;
            __iter_assign($state, $value, $key);
            return true;
        }
        function iterator_count_values($state) {
            try {
                $count = 0;
                while (__iter_next($state)) $count++;
                return $count;
            } finally { __iter_close($state); }
        }
        function iterator_array_values($state, $preserve) {
            try {
                $values = [];
                while (__iter_next($state)) {
                    if ($preserve) $values[__iter_key_value($state)] = __iter_current_value($state);
                    else $values[] = __iter_current_value($state);
                }
                return $values;
            } finally { __iter_close($state); }
        }
        function iterator_apply_values($state) {
            try {
                $class = __iter_callback_class($state);
                if ($class !== null) __class_load($class);
                __iter_resolve_callback($state);
                $count = 0;
                while (__iter_next($state)) {
                    $count++;
                    if (!__iter_callback($state)) break;
                }
                return $count;
            } finally { __iter_close($state); }
        }
        function iterator_object_count($value, $origin) { return __count_result($value->count(), $origin); }
        function unpack_values($value) { return iterator_to_array($value, true); }
        function unpack_argument_values($state, $collector) {
            try {
                while (__iter_next($state)) {
                    __unpack_collect($collector, __iter_key_value($state), __iter_current_value($state));
                }
                return $collector;
            } finally { __iter_close($state); }
        }
        """;

    public static boolean tentativeType(String name) {
        return List.of("Iterator", "IteratorAggregate", "Countable").contains(name);
    }

    static void validate(ObjectModel.Definition definition, ObjectModel.RuntimeClass parent,
                         List<ObjectModel.RuntimeClass> interfaces) {
        if (definition.kind() != Ir.TypeKind.CLASS && definition.kind() != Ir.TypeKind.ENUM) return;
        boolean iterator = parent != null && parent.isA("Iterator") || interfaces.stream().anyMatch(type -> type.isA("Iterator"));
        boolean aggregate = parent != null && parent.isA("IteratorAggregate") || interfaces.stream().anyMatch(type -> type.isA("IteratorAggregate"));
        boolean traversable = parent != null && parent.isA("Traversable") || interfaces.stream().anyMatch(type -> type.isA("Traversable"));
        if (iterator && aggregate) throw PhpError.fatal("Class " + definition.name() + " cannot implement both Iterator and IteratorAggregate at the same time");
        if (traversable && !iterator && !aggregate && !definition.abstractType())
            throw PhpError.fatal("Class " + definition.name() + " must implement interface Traversable as part of either Iterator or IteratorAggregate");
    }

    private static boolean implementsType(Object value, String name) {
        return GeneratorApi.isA(value, name)
                || value instanceof PhpValues.PhpObject object && object.descriptor instanceof ObjectModel.RuntimeClass type && type.isA(name);
    }
    public static boolean iterable(Object value) { return value instanceof PhpValues.PhpArray || implementsType(value, "Traversable"); }
    public static boolean countable(Object value) { return value instanceof PhpValues.PhpArray || implementsType(value, "Countable"); }

    /** The original activation owns loop bindings; helpers may suspend without changing their scope. */
    public static final class State implements AutoCloseable, TruffleObject {
        final Activation owner;
        final boolean reference;
        Object retained;
        Cursor array;
        PhpValues.FieldCursor fields;
        PhpValues.PhpObject iterator;
        PhpValues.Scope scratch;
        PhpValues.Scope referenceSource;
        PhpValues.Location valueTarget;
        PhpValues.Location keyTarget;
        PhpValues.Location callbackArguments;
        Object callback;
        ObjectModel.Invocation callbackInvocation;
        boolean started;
        boolean closed;
        final Set<PhpValues.PhpObject> aggregating = Collections.newSetFromMap(new IdentityHashMap<>());

        State(Activation owner, Object source, boolean reference) {
            this.owner = owner;
            this.reference = reference;
            if (reference && !(source instanceof PhpValues.Location)) {
                referenceSource = new PhpValues.Scope(owner.request.heap);
                Object owned = source;
                source = referenceSource.variable(PhpValues.unwrap(owned));
                PhpValues.drop(owned);
            }
            Object value = reference ? ((PhpValues.Location) source).readOrNull() : PhpValues.unwrap(source);
            if (value instanceof PhpValues.PhpArray) {
                array = new Cursor(source, reference);
            } else if (value instanceof PhpValues.PhpObject object) {
                retained = reference ? PhpValues.own(object) : source;
                if (implementsType(value, "Traversable")) iterator = object;
                else if (object.descriptor instanceof ObjectModel.RuntimeClass) fields = object.iterateFields();
            } else if (ObjectModel.className(value) != null) {
                retained = reference ? PhpValues.own(value) : source;
            } else {
                if (!reference) PhpValues.drop(source);
                Diagnostics.origin(owner).warning("foreach() argument must be of type array|object, " + EnumApi.valueType(value) + " given");
            }
        }

        void targets(PhpValues.Location value, PhpValues.Location key) { valueTarget = value; keyTarget = key; }
        void buffered(boolean keys) {
            scratch = new PhpValues.Scope(owner.request.heap);
            valueTarget = scratch.variable(null);
            if (keys) keyTarget = scratch.variable(null);
        }
        void callback(Object value, Object arguments) {
            if (scratch == null) scratch = new PhpValues.Scope(owner.request.heap);
            callback = PhpValues.own(value);
            callbackArguments = scratch.variable(arguments);
        }
        void select(Object value) {
            if (!implementsType(value, "Traversable"))
                throw new PhpError("Exception", "Objects returned by " + ObjectModel.className(iterator)
                        + "::getIterator() must be traversable or implement interface Iterator");
            Object next = PhpValues.own(value);
            PhpValues.drop(retained);
            retained = next;
            iterator = (PhpValues.PhpObject) value;
        }
        Object advance(Activation caller, IndirectCallNode call) {
            if (closed) return false;
            if (array != null) return array.next(valueTarget, keyTarget);
            if (fields != null) {
                while (fields.next()) {
                    String name = visibleName(owner, (ObjectModel.RuntimeClass) fields.object().descriptor, fields.name(), false);
                    if (name == null) continue;
                    if (valueTarget != null) {
                        if (reference) valueTarget.bind(fields.location());
                        else valueTarget.set(fields.location().read());
                    }
                    if (keyTarget != null) keyTarget.set(name);
                    return true;
                }
                return false;
            }
            if (iterator == null) return false;
            return Operations.invokeFunction(caller, caller.request.context.asyncFunction("iterator_step"),
                    new Argument[] {new Argument(this, null)}, call);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                if (array != null) array.close();
                if (fields != null) fields.close();
                if (scratch != null) scratch.close();
                if (referenceSource != null) referenceSource.close();
            } finally {
                PhpValues.drop(callback);
                PhpValues.drop(retained);
                retained = callback = null;
                array = null;
                fields = null;
                iterator = null;
                callbackInvocation = null;
                scratch = referenceSource = null;
                valueTarget = keyTarget = callbackArguments = null;
                aggregating.clear();
            }
        }
    }

    @TruffleBoundary
    public static State open(Activation caller, Object source, boolean reference) {
        var state = new State(caller, source, reference);
        caller.resources.add(state);
        return state;
    }
    @TruffleBoundary
    public static Object next(Activation caller, State state, PhpValues.Location value, PhpValues.Location key, IndirectCallNode call) {
        state.targets(value, key);
        return state.advance(caller, call);
    }
    @TruffleBoundary
    public static void close(State state) {
        state.owner.resources.remove(state);
        state.close();
    }

    @TruffleBoundary
    public static Object materialize(Activation caller, Object value, IndirectCallNode call) {
        Object raw = PhpValues.unwrap(value);
        if (raw instanceof PhpValues.PhpArray) return value;
        if (!iterable(raw)) {
            PhpValues.drop(value);
            throw new PhpError("Error", "Only arrays and Traversables can be unpacked");
        }
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("unpack_values"),
                new Argument[] {new Argument(value, null)}, call);
    }

    @TruffleBoundary
    public static Object materializeArguments(Activation caller, Object value, IndirectCallNode call) {
        Object raw = PhpValues.unwrap(value);
        if (raw instanceof PhpValues.PhpArray) return value;
        if (!iterable(raw)) {
            PhpValues.drop(value);
            throw new PhpError("TypeError", "Only arrays and Traversables can be unpacked");
        }
        var state = open(caller, value, false);
        state.buffered(true);
        var collector = new CallArguments.Collected(caller);
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("unpack_argument_values"),
                new Argument[] {new Argument(state, null), new Argument(collector, null)}, call);
    }

    @TruffleBoundary
    public static Object function(Activation caller, String name, Argument[] arguments, IndirectCallNode call) {
        switch (name) {
            case "is_iterable", "is_countable": {
                var args = CallArguments.builtin(name, arguments, List.of("value"), 1);
                Object value = PhpValues.unwrap(args[0].value());
                return name.equals("is_iterable") ? iterable(value) : countable(value);
            }
            case "count", "sizeof": {
                var args = CallArguments.builtin(name, arguments, List.of("value", "mode"), 1, 0L);
                Object value = checked(caller, args[0].value(), "Countable|array");
                long mode = (long) scalar(caller, name, 2, "mode", args[1].value(), "int");
                if (mode != 0 && mode != 1) throw new PhpError("ValueError", name + "(): Argument #2 ($mode) must be either COUNT_NORMAL or COUNT_RECURSIVE");
                if (value instanceof PhpValues.PhpArray) {
                    if (mode == 0) return (long) PhpValues.keys(value).size();
                    return countArray(value, Diagnostics.origin(caller), Collections.newSetFromMap(new IdentityHashMap<>()));
                }
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("iterator_object_count"),
                        new Argument[] {new Argument(value, null), new Argument(Diagnostics.origin(caller), null)}, call);
            }
            case "iterator_count": {
                var args = CallArguments.builtin(name, arguments, List.of("iterator"), 1);
                Object value = checked(caller, args[0].value(), "Traversable|array");
                if (value instanceof PhpValues.PhpArray) return (long) PhpValues.keys(value).size();
                var state = open(caller, PhpValues.own(value), false);
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("iterator_count_values"),
                        new Argument[] {new Argument(state, null)}, call);
            }
            case "iterator_to_array": {
                var args = CallArguments.builtin(name, arguments, List.of("iterator", "preserve_keys"), 1, true);
                Object value = checked(caller, args[0].value(), "Traversable|array");
                boolean keys = (boolean) scalar(caller, name, 2, "preserve_keys", args[1].value(), "bool");
                if (value instanceof PhpValues.PhpArray) {
                    if (keys) return caller.track(PhpValues.own(value));
                    try (var scope = new PhpValues.Scope(caller.request.heap)) {
                        var result = scope.variable(scope.emptyArray());
                        for (Object key : PhpValues.keys(value)) PhpValues.copyElement(value, key, result.append());
                        return caller.track(PhpValues.own(result.read()));
                    }
                }
                var state = open(caller, PhpValues.own(value), false);
                state.buffered(keys);
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("iterator_array_values"),
                        new Argument[] {new Argument(state, null), new Argument(keys, null)}, call);
            }
            case "iterator_apply": {
                var args = CallArguments.builtin(name, arguments, List.of("iterator", "callback", "args"), 2, (Object) null);
                Object value = checked(caller, args[0].value(), "Traversable");
                Object callback = PhpValues.unwrap(args[1].value());
                Object callbackArgs = PhpValues.unwrap(args[2].value());
                if (callbackArgs != null) checked(caller, callbackArgs, "array");
                var state = open(caller, PhpValues.own(value), false);
                state.callback(callback, callbackArgs == null ? PhpValues.PhpArray.empty(caller.request.heap) : callbackArgs);
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("iterator_apply_values"),
                        new Argument[] {new Argument(state, null)}, call);
            }
            case "get_object_vars", "get_mangled_object_vars": {
                var args = CallArguments.builtin(name, arguments, List.of("object"), 1);
                Object value = checked(caller, args[0].value(), "object");
                try (var scope = new PhpValues.Scope(caller.request.heap)) {
                    var result = scope.variable(scope.emptyArray());
                    if (value instanceof PhpValues.PhpObject object && object.descriptor instanceof ObjectModel.RuntimeClass type) {
                        try (var fields = object.iterateFields()) {
                            while (fields.next()) {
                                String key = visibleName(caller, type, fields.name(), name.equals("get_mangled_object_vars"));
                                if (key != null) result.element(key).copyValueFrom(fields.location());
                            }
                        }
                    }
                    return caller.track(PhpValues.own(result.read()));
                }
            }
            case "__iter_started": {
                var state = (State) arguments[0].value();
                boolean old = state.started;
                state.started = true;
                return old;
            }
            case "__iter_aggregate": {
                var state = (State) arguments[0].value();
                if (!implementsType(state.iterator, "IteratorAggregate")) return false;
                if (!state.aggregating.add(state.iterator) || state.aggregating.size() > 512)
                    throw new PhpError("Error", "Recursive IteratorAggregate chain");
                return true;
            }
            case "__iter_select": {
                ((State) arguments[0].value()).select(PhpValues.unwrap(arguments[1].value()));
                return null;
            }
            case "__iter_ready": {
                var state = (State) arguments[0].value();
                if (state.reference && !GeneratorApi.byReference(state.iterator))
                    throw new PhpError("Exception", GeneratorApi.isGenerator(state.iterator)
                            ? "You can only iterate a generator by-reference if it declared that it yields by-reference"
                            : "An iterator cannot be used with foreach by reference");
                state.aggregating.clear();
                return null;
            }
            case "__iter_call": {
                var state = (State) arguments[0].value();
                return Operations.invokeMember(caller, "method", (String) arguments[1].value(), PhpValues.own(state.iterator), new Argument[0], call);
            }
            case "__iter_want_value": return ((State) arguments[0].value()).valueTarget != null;
            case "__iter_want_key": return ((State) arguments[0].value()).keyTarget != null;
            case "__iter_assign": {
                var state = (State) arguments[0].value();
                if (state.valueTarget != null) {
                    if (state.reference && GeneratorApi.isGenerator(state.iterator))
                        state.valueTarget.bind(GeneratorApi.currentReference(state.iterator));
                    else state.valueTarget.set(PhpValues.unwrap(arguments[1].value()));
                }
                if (state.keyTarget != null) state.keyTarget.set(PhpValues.unwrap(arguments[2].value()));
                return null;
            }
            case "__iter_next": return ((State) arguments[0].value()).advance(caller, call);
            case "__iter_current_value": return caller.track(PhpValues.own(((State) arguments[0].value()).valueTarget.read()));
            case "__iter_key_value": return caller.track(PhpValues.own(((State) arguments[0].value()).keyTarget.read()));
            case "__unpack_collect": {
                ((CallArguments.Collected) arguments[0].value()).add(arguments[1].value(), arguments[2].value());
                return null;
            }
            case "__iter_close": close((State) arguments[0].value()); return null;
            case "__iter_callback_class": {
                String type = ClassLoading.callableClass(PhpValues.unwrap(((State) arguments[0].value()).callback));
                return type == null || List.of("self", "static", "parent").contains(type) ? null : type;
            }
            case "__iter_resolve_callback": {
                var state = (State) arguments[0].value();
                String type = ClassLoading.callableClass(PhpValues.unwrap(state.callback));
                if (type != null && List.of("self", "parent", "static").contains(type.toLowerCase(java.util.Locale.ROOT)))
                    Diagnostics.deprecated(state.owner, "Use of \"" + type.toLowerCase(java.util.Locale.ROOT) + "\" in callables is deprecated");
                try { state.callbackInvocation = ObjectModel.callable(state.owner, PhpValues.unwrap(state.callback)); }
                catch (PhpError invalid) { throw new PhpError("TypeError", "iterator_apply(): Argument #2 ($callback) must be a valid callback"); }
                return null;
            }
            case "__iter_callback": {
                var state = (State) arguments[0].value();
                var keys = PhpValues.keys(state.callbackArguments.read());
                var args = new Argument[keys.size()];
                for (int index = 0; index < args.length; index++) {
                    Object key = keys.get(index);
                    var location = state.callbackArguments.element(key);
                    args[index] = new Argument(PhpValues.own(location.read()), location, key instanceof String text ? text : null);
                }
                try { return ObjectModel.invoke(caller, state.callbackInvocation, args, call); }
                finally { for (var argument : args) argument.close(); }
            }
            case "__count_result": return countResult((Diagnostics.Origin) arguments[1].value(), PhpValues.unwrap(arguments[0].value()));
            default: return AsyncApi.UNHANDLED;
        }
    }

    private static Object scalar(Activation caller, String function, int index, String parameter, Object value, String type) {
        if (PhpValues.unwrap(value) == null && !caller.function.strictTypes()) {
            Diagnostics.deprecated(caller, function + "(): Passing null to parameter #" + index + " ($" + parameter + ") of type " + type + " is deprecated");
            return type.equals("bool") ? false : 0L;
        }
        return checked(caller, value, type);
    }
    private static Object checked(Activation caller, Object value, String type) {
        return TypeRelations.check(value, type, caller.function.strictTypes(), Diagnostics.origin(caller));
    }
    private static long countArray(Object value, Diagnostics.Origin origin, Set<Object> path) {
        if (!path.add(value)) { origin.warning("count(): Recursion detected"); return 0; }
        if (path.size() > 512) throw new PhpError("Error", "Maximum recursive count depth exceeded");
        try {
            var keys = PhpValues.keys(value);
            long count = keys.size();
            for (Object key : keys) {
                Object element = PhpValues.element(value, key);
                try {
                    if (PhpValues.unwrap(element) instanceof PhpValues.PhpArray nested) count += countArray(nested, origin, path);
                } finally { PhpValues.drop(element); }
            }
            return count;
        } finally { path.remove(value); }
    }
    private static long countResult(Diagnostics.Origin origin, Object value) {
        if (value == null || value instanceof Boolean) return Operations.truth(value) ? 1 : 0;
        if (value instanceof Number number) return number.longValue();
        if (value instanceof PhpValues.PhpArray) return PhpValues.keys(value).isEmpty() ? 0 : 1;
        if (value instanceof String text) {
            var number = java.util.regex.Pattern.compile("^[\\s]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)").matcher(text);
            if (!number.find()) return 0;
            return Operations.number(number.group(1)).longValue();
        }
        if (value instanceof PhpString) return 0;
        origin.warning("Object of class " + ObjectModel.className(value) + " could not be converted to int");
        return 1;
    }

    private static String visibleName(Activation caller, ObjectModel.RuntimeClass type, String storage, boolean mangled) {
        int separator = storage.indexOf('\0');
        if (separator >= 0) {
            if (separator == 0) return null; // Runtime roots are not PHP object properties.
            if (mangled) return "\0" + storage;
            return storage.substring(0, separator).equals(caller.function.owner()) ? storage.substring(separator + 1) : null;
        }
        for (var current = type; current != null; current = current.parent) {
            for (var property : current.properties) {
                if (property.shared() || !property.name().equals(storage) || property.visibility().equals("private")) continue;
                if (property.visibility().equals("public")) return storage;
                if (mangled) return "\0*\0" + storage;
                if (caller.function.owner() == null) return null;
                var lexical = caller.request.type(caller.function.owner());
                return lexical.isA(current.definition.name()) || current.isA(lexical.definition.name()) ? storage : null;
            }
        }
        return storage;
    }
}
