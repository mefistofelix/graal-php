package graalphp.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static graalphp.runtime.Execution.*;

/** Reordering never copies values or loses reference locations. Evaluation has already happened in source order. */
public final class CallArguments {
    private CallArguments() {}

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
            ordered[index] = new Argument(argument.value(), argument.location());
        }
        for (int i = 0; i < ordered.length; i++) if (ordered[i] == null) {
            if (i < required) throw new PhpError("ArgumentCountError", "Missing argument $" + names.get(i) + " for " + function);
            ordered[i] = new Argument(defaults[i - required], null);
        }
        return ordered;
    }
}
