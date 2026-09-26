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
        function class_declare($definition, $parent) {
            __class_require($parent);
            return __class_publish($definition);
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
        if (definition.parent() == null || exists(caller.request, definition.parent())) return publish(caller, definition);
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("class_declare"),
                new Argument[] {new Argument(definition, null), new Argument(definition.parent(), null)}, call);
    }
    private static Object publish(Activation caller, ObjectModel.Definition definition) {
        if (exists(caller.request, definition.name())) throw new PhpError("Error", "Cannot redeclare class " + definition.name());
        if (definition.parent() != null) caller.request.type(definition.parent());
        caller.request.classes.put(key(definition.name()), definition);
        return null;
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
            case "class_exists": {
                var ordered = CallArguments.builtin(function, args, List.of("class", "autoload"), 1, true);
                String name = name(ordered[0].value());
                boolean autoload = (boolean) argument(ordered[1].value(), "bool");
                if (exists(caller.request, name)) return true;
                if (!autoload || !valid(name)) return false;
                return load(caller, name, false, call);
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
