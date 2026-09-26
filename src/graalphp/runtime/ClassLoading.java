package graalphp.runtime;

import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static graalphp.runtime.Execution.*;

/** Request-local SPL queue. Resumable control flow is lowered by the ordinary PHP compiler. */
public final class ClassLoading implements AutoCloseable {
    public static final String SOURCE = """
        function class_load($name, $explicit = false) {
            if (!$explicit && __class_exists($name)) return true;
            $attempt = __autoload_begin($name, $explicit);
            if ($attempt === null) return false;
            try {
                while (($loader = __autoload_next($attempt)) !== null) {
                    __autoload_invoke($loader, $name);
                    if (__class_exists($name)) return true;
                }
                return false;
            } finally { __autoload_end($attempt); }
        }
        function class_require($name) {
            if (!__class_load($name)) __class_missing($name);
            return $name;
        }
        function class_autoload_call($name) { __class_load($name, true); }
        function class_register($callback, $prepend) {
            $name = __callable_class($callback);
            if ($name !== null) __class_require($name);
            return __autoload_register($callback, $prepend);
        }
        function class_declare($definition) {
            $index = 0;
            while (($name = __class_dependency($definition, $index++)) !== null) __class_require($name);
            return __class_publish($definition);
        }
        function class_exists_query($name, $kind) {
            __class_load($name);
            return __class_kind($name, $kind);
        }
        function class_information($name, $operation, $argument = null) {
            __class_load($name);
            return __class_information($name, $operation, $argument);
        }
        function class_callable($state, $name) {
            try {
                __class_require($name);
                return __class_call($state);
            } finally { __class_call_close($state); }
        }
        """;

    // Only names already represented by this runtime, not the full PHP/SPL class catalog.
    private static final Set<String> BUILTINS = Set.of(
            "closure", "exception", "runtimeexception", "logicexception", "invalidargumentexception",
            "error", "typeerror", "valueerror", "argumentcounterror", "ffi", "curlhandle", "curlmultihandle",
            "sqlite3", "sqlite3stmt", "sqlite3result", "async\\coroutine", "async\\scope", "async\\context",
            "async\\channel", "async\\mutex", "async\\threadchannel", "async\\future", "async\\futurestate",
            "async\\asyncexception", "async\\asynccancellation", "async\\operationcanceledexception",
            "async\\channelexception", "async\\threadchannelexception", "async\\contextexception",
            "trueasync\\httpserverconfig", "trueasync\\httpserver", "trueasync\\httprequest",
            "trueasync\\httpresponse", "trueasync\\websocket", "trueasync\\websocketmessage");

    private final List<Entry> entries = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private final Set<String> loading = new HashSet<>();

    private static final class Entry implements com.oracle.truffle.api.interop.TruffleObject {
        final ObjectModel.Invocation invocation;
        final Object receiver;
        final Object environment;
        int owners = 1;

        Entry(ObjectModel.Invocation invocation) {
            this.invocation = invocation;
            receiver = PhpValues.own(invocation.receiver());
            environment = PhpValues.own(invocation.environment());
        }
        void release() {
            if (--owners == 0) {
                PhpValues.drop(receiver);
                PhpValues.drop(environment);
            }
        }
        Object value(Activation caller) {
            if (invocation.environment() != null) return invocation.environment();
            if (invocation.function().owner() == null) return invocation.function().name();
            try (var scope = new PhpValues.Scope(caller.request.heap)) {
                var array = scope.variable(scope.array(invocation.receiver() == null
                        ? invocation.calledClass().definition.name() : invocation.receiver(), invocation.function().name()));
                return caller.track(PhpValues.own(array.read()));
            }
        }
    }

    private final class Attempt implements AutoCloseable, com.oracle.truffle.api.interop.TruffleObject {
        final String guardedName;
        int next;
        Entry current;
        boolean closed;

        Attempt(String guardedName) { this.guardedName = guardedName; }
        Entry next() {
            if (current != null) { current.release(); current = null; }
            if (next >= entries.size()) return null;
            current = entries.get(next++);
            current.owners++;
            return current;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (current != null) { current.release(); current = null; }
            attempts.remove(this);
            if (guardedName != null) loading.remove(guardedName);
        }
    }

    /** Own arguments across class loading without losing reference locations or named arguments. */
    private static final class DeferredCall implements AutoCloseable, com.oracle.truffle.api.interop.TruffleObject {
        final Activation owner;
        final Object callable;
        final Argument[] arguments;
        boolean closed;

        DeferredCall(Activation owner, Object callable, Argument[] arguments) {
            this.owner = owner;
            this.callable = PhpValues.own(PhpValues.unwrap(callable));
            this.arguments = new Argument[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                var argument = arguments[i];
                this.arguments[i] = new Argument(PhpValues.own(PhpValues.unwrap(argument.value())), argument.location(), argument.name());
            }
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            PhpValues.drop(callable);
            for (var argument : arguments) argument.close();
        }
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static Object argument(Object value, String type) {
        try { return ObjectModel.checkType(value, type); }
        catch (PhpError error) { throw new PhpError("TypeError", "Autoload argument must satisfy type " + type); }
    }
    private static String name(Object value) {
        String name = Operations.string(argument(value, "string"));
        return name.startsWith("\\") ? name.substring(1) : name;
    }
    private static boolean valid(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '\\' || c >= 128)) return false;
        }
        return true;
    }
    public static boolean exists(Request request, String name) {
        return request.classes.containsKey(key(name)) || BUILTINS.contains(key(name));
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object ensure(Activation caller, Object value, IndirectCallNode call) {
        try {
            Object raw = PhpValues.unwrap(value);
            String name;
            if (raw instanceof PhpValues.PhpObject object && object.descriptor instanceof ObjectModel.RuntimeClass type) {
                name = type.definition.name();
            } else if (raw instanceof String) {
                name = name(raw);
            } else {
                throw new PhpError("Error", "Class name must be a valid object or a string");
            }
            if (List.of("self", "parent", "static").contains(name)) {
                ObjectModel.resolve(caller, name);
                return name;
            }
            if (exists(caller.request, name)) return name;
            return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_require"),
                    new Argument[] {new Argument(name, null)}, call);
        } finally { PhpValues.drop(value); }
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object declare(Activation caller, ObjectModel.Definition definition, IndirectCallNode call) {
        if (exists(caller.request, definition.name())) throw new PhpError("Error", "Cannot redeclare class " + definition.name());
        if (definition.dependencies().stream().allMatch(name -> exists(caller.request, name))) return publish(caller, definition);
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_declare"),
                new Argument[] {new Argument(definition, null)}, call);
    }
    private static Object publish(Activation caller, ObjectModel.Definition definition) {
        if (exists(caller.request, definition.name())) throw new PhpError("Error", "Cannot redeclare class " + definition.name());
        String key = key(definition.name());
        caller.request.classes.put(key, definition);
        try {
            caller.request.type(definition.name());
            return null;
        } catch (RuntimeException failure) {
            caller.request.classes.remove(key);
            caller.request.resolvedClasses.remove(key);
            throw failure;
        }
    }
    public static String callableClass(Object value) {
        value = PhpValues.unwrap(value);
        if (value instanceof String text) {
            int separator = text.indexOf("::");
            return separator < 0 ? null : name(text.substring(0, separator));
        }
        if (!(value instanceof PhpValues.PhpArray)) return null;
        var keys = PhpValues.keys(value);
        if (keys.size() != 2 || !keys.containsAll(List.of(0L, 1L))) return null;
        Object receiver = PhpValues.element(value, 0L);
        try { return PhpValues.unwrap(receiver) instanceof String text ? name(text) : null; }
        finally { PhpValues.drop(receiver); }
    }
    public static Object deferCallable(Activation caller, Object receiver, Argument[] args, String name, IndirectCallNode call) {
        var state = new DeferredCall(caller, receiver, args);
        caller.resources.add(state);
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_callable"),
                new Argument[] {new Argument(state, null), new Argument(name, null)}, call);
    }

    public static Object function(Activation caller, String function, Argument[] args, IndirectCallNode call) {
        var registry = caller.request.autoload;
        switch (function) {
            case "class_exists", "interface_exists", "trait_exists": {
                String kind = function.substring(0, function.indexOf('_'));
                var ordered = CallArguments.builtin(function, args, List.of(kind, "autoload"), 1, true);
                String name = name(ordered[0].value());
                boolean autoload = (boolean) argument(ordered[1].value(), "bool");
                if (exists(caller.request, name)) return kind(caller.request, name, kind);
                if (!autoload || !valid(name)) return false;
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_exists_query"),
                        new Argument[] {new Argument(name, null), new Argument(kind, null)}, call);
            }
            case "is_a", "is_subclass_of": {
                var ordered = CallArguments.builtin(function, args, List.of("object_or_class", "class", "allow_string"), 2, function.equals("is_subclass_of"));
                Object value = PhpValues.unwrap(ordered[0].value());
                boolean allowString = (boolean) argument(ordered[2].value(), "bool");
                if (value instanceof String && !allowString)
                    Diagnostics.deprecated(caller, "Calling " + function + "() with a string when $allow_string is false");
                String target = name(ordered[1].value());
                String source = value instanceof String text ? allowString ? name(text) : null : ObjectModel.className(value);
                if (source == null) return false;
                return information(caller, source, function, target, true, call);
            }
            case "class_parents", "class_implements", "class_uses": {
                var ordered = CallArguments.builtin(function, args, List.of("object_or_class", "autoload"), 1, true);
                Object value = PhpValues.unwrap(ordered[0].value());
                String type = value instanceof String text ? name(text) : ObjectModel.className(value);
                if (type == null) throw new PhpError("TypeError", "Expected an object or a class name");
                return information(caller, type, function, null, (boolean) argument(ordered[1].value(), "bool"), call);
            }
            case "method_exists": {
                var ordered = CallArguments.builtin(function, args, List.of("object_or_class", "method"), 2);
                Object value = PhpValues.unwrap(ordered[0].value());
                String type = value instanceof String text ? name(text) : ObjectModel.className(value);
                if (type == null) throw new PhpError("TypeError", "Expected an object or a class name");
                return information(caller, type, function, Operations.string(argument(ordered[1].value(), "string")), true, call);
            }
            case "get_declared_classes", "get_declared_interfaces", "get_declared_traits": {
                CallArguments.builtin(function, args, List.of(), 0);
                String kind = function.equals("get_declared_classes") ? "CLASS" : function.equals("get_declared_interfaces") ? "INTERFACE" : "TRAIT";
                try (var scope = new PhpValues.Scope(caller.request.heap)) {
                    var array = scope.variable(scope.emptyArray());
                    for (var definition : caller.request.classes.values()) {
                        if (definition.kind().name().equals(kind)) array.append().set(definition.name());
                    }
                    return caller.track(PhpValues.own(array.read()));
                }
            }
            case "spl_autoload_call": {
                var ordered = CallArguments.builtin(function, args, List.of("class"), 1);
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_autoload_call"),
                        new Argument[] {new Argument(name(ordered[0].value()), null)}, call);
            }
            case "spl_autoload_register": {
                var ordered = CallArguments.builtin(function, args, List.of("callback", "throw", "prepend"), 0, null, true, false);
                Object callback = PhpValues.unwrap(ordered[0].value());
                argument(ordered[1].value(), "bool");
                boolean prepend = (boolean) argument(ordered[2].value(), "bool");
                if (callback == null) throw new PhpError("Error", "Default SPL filename loading is not implemented; register a callback");
                String type = callableClass(callback);
                if (type != null && !exists(caller.request, type)) {
                    return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_register"),
                            new Argument[] {new Argument(callback, null), new Argument(prepend, null)}, call);
                }
                return registry.register(caller, callback, prepend);
            }
            case "spl_autoload_unregister": {
                var ordered = CallArguments.builtin(function, args, List.of("callback"), 1);
                var invocation = validated(caller, ordered[0].value());
                for (int index = 0; index < registry.entries.size(); index++) {
                    var entry = registry.entries.get(index);
                    if (!entry.invocation.equals(invocation)) continue;
                    registry.entries.remove(index);
                    for (var attempt : registry.attempts) {
                        // SPL advances past the next entry when its current callback unregisters itself.
                        if (attempt.next > index && attempt.current != entry) attempt.next--;
                    }
                    entry.release();
                    return true;
                }
                return false;
            }
            case "spl_autoload_functions": {
                CallArguments.builtin(function, args, List.of(), 0);
                try (var scope = new PhpValues.Scope(caller.request.heap)) {
                    var array = scope.variable(scope.emptyArray());
                    for (var entry : registry.entries) {
                        Object callback = entry.value(caller);
                        try { array.append().set(PhpValues.unwrap(callback)); }
                        finally { PhpValues.drop(callback); }
                    }
                    return caller.track(PhpValues.own(array.read()));
                }
            }
            case "__class_kind": return kind(caller.request, Operations.string(args[0].value()), Operations.string(args[1].value()));
            case "__class_information": return informationNow(caller, Operations.string(args[0].value()), Operations.string(args[1].value()), args[2].value());
            case "__class_dependency": {
                var dependencies = ((ObjectModel.Definition) args[0].value()).dependencies();
                int index = Operations.number(args[1].value()).intValue();
                return index < dependencies.size() ? dependencies.get(index) : null;
            }
            case "__class_load": return load(caller, (String) args[0].value(), args.length > 1 && Operations.truth(args[1].value()), call);
            case "__class_require":
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_require"), args, call);
            case "__class_exists": return exists(caller.request, Operations.string(args[0].value()));
            case "__class_missing": throw new PhpError("Error", "Class \"" + Operations.string(args[0].value()) + "\" not found");
            case "__callable_class": return callableClass(args[0].value());
            case "__class_publish": return publish(caller, (ObjectModel.Definition) args[0].value());
            case "__autoload_register": return registry.register(caller, args[0].value(), Operations.truth(args[1].value()));
            case "__autoload_begin": {
                String name = Operations.string(args[0].value());
                boolean explicit = Operations.truth(args[1].value());
                if (registry.entries.isEmpty() || !explicit && !registry.loading.add(key(name))) return null;
                var attempt = registry.new Attempt(explicit ? null : key(name));
                registry.attempts.add(attempt);
                caller.resources.add(attempt);
                return attempt;
            }
            case "__autoload_next": return ((ClassLoading.Attempt) args[0].value()).next();
            case "__autoload_invoke":
                return ObjectModel.invoke(caller, ((Entry) args[0].value()).invocation,
                        new Argument[] {new Argument(args[1].value(), null)}, call);
            case "__autoload_end": {
                var attempt = (ClassLoading.Attempt) args[0].value();
                caller.resources.remove(attempt);
                attempt.close();
                return null;
            }
            case "__class_call": {
                var state = (DeferredCall) args[0].value();
                return Operations.invokeMember(state.owner, "callable", "", PhpValues.own(PhpValues.unwrap(state.callable)), state.arguments, call);
            }
            case "__class_call_close": {
                var state = (DeferredCall) args[0].value();
                state.owner.resources.remove(state);
                state.close();
                return null;
            }
            default: return AsyncApi.UNHANDLED;
        }
    }

    private static boolean kind(Request request, String name, String kind) {
        var definition = request.classes.get(key(name));
        return definition == null ? kind.equals("class") && BUILTINS.contains(key(name)) : definition.kind().name().equalsIgnoreCase(kind);
    }

    private static Object information(Activation caller, String name, String operation, Object argument, boolean autoload, IndirectCallNode call) {
        if (exists(caller.request, name) || !autoload || !valid(name)) return informationNow(caller, name, operation, argument);
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_information"),
                new Argument[] {new Argument(name, null), new Argument(operation, null), new Argument(argument, null)}, call);
    }

    private static Object informationNow(Activation caller, String name, String operation, Object argument) {
        if (!exists(caller.request, name)) return false;
        if (operation.equals("is_a") || operation.equals("is_subclass_of")) {
            String target = Operations.string(argument);
            if (name.equalsIgnoreCase(target)) return operation.equals("is_a");
            var definition = caller.request.classes.get(key(name));
            if (definition != null && definition.kind() == graalphp.frontend.Ir.TypeKind.TRAIT) return false;
            if (!exists(caller.request, target)) return false;
            return TypeRelations.subtype(caller.request, name, target);
        }
        if (!caller.request.classes.containsKey(key(name))) return false;
        var type = caller.request.type(name);
        if (operation.equals("method_exists")) return type.method(Operations.string(argument)) != null;
        var names = new java.util.LinkedHashMap<String, String>();
        if (operation.equals("class_parents")) {
            for (var current = type.parent; current != null; current = current.parent)
                names.put(current.definition.name(), current.definition.name());
        } else if (operation.equals("class_uses")) {
            for (var use : type.definition.traits()) for (String used : use.names()) {
                String canonical = caller.request.type(used).definition.name();
                names.put(canonical, canonical);
            }
        } else if (operation.equals("class_implements")) collectInterfaces(type, names);
        else throw new PhpError("Unknown class query " + operation);
        try (var scope = new PhpValues.Scope(caller.request.heap)) {
            var result = scope.variable(scope.emptyArray());
            names.forEach((key, value) -> result.element(key).set(value));
            return caller.track(PhpValues.own(result.read()));
        }
    }

    private static void collectInterfaces(ObjectModel.RuntimeClass type, java.util.LinkedHashMap<String, String> names) {
        if (type.parent != null) collectInterfaces(type.parent, names);
        for (var contract : type.interfaces) {
            names.putIfAbsent(contract.definition.name(), contract.definition.name());
            collectInterfaces(contract, names);
        }
    }

    private static Object load(Activation caller, String name, boolean explicit, IndirectCallNode call) {
        if (!explicit && (exists(caller.request, name) || !valid(name))) return exists(caller.request, name);
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_load"),
                new Argument[] {new Argument(name, null), new Argument(explicit, null)}, call);
    }
    private static ObjectModel.Invocation validated(Activation caller, Object value) {
        try { return ObjectModel.callable(caller, value); }
        catch (PhpError invalid) { throw new PhpError("TypeError", "Invalid autoload callback: " + invalid.getMessage()); }
    }
    private boolean register(Activation caller, Object value, boolean prepend) {
        var invocation = validated(caller, value);
        for (var entry : entries) if (entry.invocation.equals(invocation)) return true;
        var entry = new Entry(invocation);
        // Match SPL's live queue: appends are visited, and prepending shifts the active index.
        if (prepend) entries.addFirst(entry); else entries.add(entry);
        return true;
    }
    @Override public void close() {
        for (var attempt : List.copyOf(attempts)) attempt.close();
        for (var entry : entries) entry.release();
        entries.clear();
        loading.clear();
    }
}
