package graalphp.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static graalphp.runtime.Execution.*;

/** Reordering never copies values or loses reference locations. Evaluation has already happened in source order. */
public final class CallArguments {
    private CallArguments() {}

    /** Traversable unpacking must preserve duplicate keys and source order until call binding. */
    public static final class Collected implements AutoCloseable, com.oracle.truffle.api.interop.TruffleObject {
        private record Entry(Object key, PhpValues.Location location) {}
        private final Activation owner;
        private final PhpValues.Scope values;
        private final List<Entry> entries = new ArrayList<>();
        private boolean closed;

        public Collected(Activation owner) {
            this.owner = owner;
            values = new PhpValues.Scope(owner.request.heap);
            owner.resources.add(this);
        }
        public void add(Object key, Object value) {
            key = PhpValues.unwrap(key);
            if (!(key instanceof Long) && !(key instanceof String))
                throw new PhpError("Error", "Keys must be of type int|string during argument unpacking");
            entries.add(new Entry(key, values.variable(PhpValues.unwrap(value))));
        }
        private void addArguments(List<Argument> result) {
            for (var entry : entries) {
                String name = entry.key instanceof String text ? text : null;
                result.add(new Argument(PhpValues.own(entry.location.read()), entry.location, name, true));
            }
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            values.close();
            entries.clear();
        }
    }

    /** A materialized unpack source. Temporary roots survive a suspended child call. */
    public static final class Spread implements AutoCloseable, com.oracle.truffle.api.interop.TruffleObject {
        private final Activation owner;
        private final PhpValues.Scope temporary;
        private final PhpValues.Location root;
        private final Collected collected;
        private boolean closed;

        public Spread(Activation owner, Object materialized, PhpValues.Location original) {
            this.owner = owner;
            if (materialized instanceof Collected sequence) {
                collected = sequence;
                root = null;
                temporary = null;
                return;
            }
            collected = null;
            Object array = PhpValues.unwrap(materialized);
            if (!(array instanceof PhpValues.PhpArray)) {
                PhpValues.drop(materialized);
                throw new PhpError("TypeError", "Only arrays and Traversables can be unpacked");
            }
            boolean originalArray = false;
            if (original != null) {
                try { originalArray = PhpValues.unwrap(original.read()) == array; }
                catch (RuntimeException ignored) { /* The evaluated value remains authoritative. */ }
            }
            if (originalArray) {
                root = original;
                temporary = null;
            } else {
                temporary = new PhpValues.Scope(owner.request.heap);
                root = temporary.variable(array);
                owner.resources.add(this);
            }
            PhpValues.drop(materialized);
        }

        private void add(List<Argument> result) {
            if (collected != null) {
                collected.addArguments(result);
                return;
            }
            Object array = root.read();
            for (Object key : PhpValues.keys(array)) {
                var location = root.element(key);
                var argument = new Argument(PhpValues.own(location.read()), location,
                        key instanceof String text ? text : null);
                if (key instanceof Long || key instanceof String) result.add(argument);
                else {
                    argument.close();
                    throw new PhpError("Error", "Named parameter keys must be strings");
                }
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (temporary != null) temporary.close();
        }
    }

    /** Expand splats after expression evaluation while preserving source/key order. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Argument[] expand(Object[] parts) {
        var result = new ArrayList<Argument>();
        try {
            for (Object part : parts) {
                if (part instanceof Argument argument) result.add(argument);
                else if (part instanceof Spread spread) spread.add(result);
                else throw new PhpError("Error", "Invalid call argument");
            }
            boolean named = false;
            var names = new HashSet<String>();
            for (var argument : result) {
                if (argument.name() == null) {
                    if (named) throw new PhpError("Error", "Cannot use positional argument after named argument during unpacking");
                } else {
                    named = true;
                    if (!names.add(argument.name()))
                        throw new PhpError("Error", "Named parameter $" + argument.name() + " overwrites previous argument");
                }
            }
            return result.toArray(Argument[]::new);
        } catch (RuntimeException error) {
            for (var argument : result) argument.close();
            throw error;
        }
    }

    public static Argument[] bind(Function function, Argument[] arguments) {
        if (!hasNames(arguments)) return arguments;
        var parameters = function.parameters();
        boolean variadic = !parameters.isEmpty() && parameters.getLast().variadic();
        int fixed = parameters.size() - (variadic ? 1 : 0);
        var ordered = new Argument[fixed];
        var rest = new ArrayList<Argument>();
        var names = new HashSet<String>();
        int position = 0;
        for (var argument : arguments) {
            int index = position;
            if (argument.name() == null) {
                if (!names.isEmpty()) throw new PhpError("Error", "Positional argument after named argument");
                position++;
            } else {
                if (!names.add(argument.name())) throw new PhpError("Error", "Duplicate named argument " + argument.name());
                index = -1;
                for (int i = 0; i < fixed; i++) if (parameters.get(i).name().equals(argument.name())) { index = i; break; }
                if (index < 0) {
                    if (!variadic) throw new PhpError("Error", "Unknown named parameter $" + argument.name());
                    rest.add(argument); continue;
                }
            }
            if (index >= fixed) { rest.add(argument); continue; }
            if (ordered[index] != null) throw new PhpError("Error", "Named parameter overwrites previous argument");
            ordered[index] = argument;
        }
        var result = java.util.Arrays.copyOf(ordered, fixed + rest.size());
        for (int i = 0; i < rest.size(); i++) result[fixed + i] = rest.get(i);
        return result;
    }

    public static boolean hasNames(Argument[] arguments) {
        for (var argument : arguments) if (argument.name() != null) return true;
        return false;
    }

    public static void positionalOnly(String name, Argument[] arguments) {
        if (hasNames(arguments)) throw new PhpError("Error", "Named arguments are not yet supported for " + name);
    }

    /** Native/public builtins use the same duplicate/unknown checks with explicit optional defaults. */
    public static Argument[] builtin(String function, Argument[] arguments, List<String> names, int required, Object... defaults) {
        if (arguments.length > names.size()) throw new PhpError("ArgumentCountError", "Too many arguments for " + function);
        var ordered = new Argument[names.size()];
        boolean named = false;
        for (int i = 0; i < arguments.length; i++) {
            var argument = arguments[i];
            int index = i;
            if (argument.name() != null) { named = true; index = names.indexOf(argument.name()); }
            else if (named) throw new PhpError("Error", "Positional argument after named argument");
            if (index < 0) throw new PhpError("Error", "Unknown named parameter $" + argument.name());
            if (ordered[index] != null) throw new PhpError("Error", "Named parameter overwrites previous argument");
            ordered[index] = new Argument(argument.value(), argument.location(), null, argument.traversableUnpack());
        }
        for (int i = 0; i < ordered.length; i++) if (ordered[i] == null) {
            if (i < required) throw new PhpError("ArgumentCountError", "Missing argument $" + names.get(i) + " for " + function);
            ordered[i] = new Argument(defaults[i - required], null);
        }
        return ordered;
    }
}
