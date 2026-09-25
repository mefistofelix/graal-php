package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import java.util.List;
import java.util.Map;
import static graalphp.runtime.Execution.*;

/** PHP-style declarations plus per-invocation async/pool options, without a second PHP runtime. */
public final class FfiApi {
    private FfiApi() {}
    public record Binding(String library, Map<String, CDeclarations.Function> functions) implements TruffleObject {}

    public static Object staticMethod(Activation caller, String name, Argument[] args) {
        if (name.equalsIgnoreCase("definePool")) {
            args = CallArguments.builtin("FFI::definePool", args, List.of("name", "min", "max", "queueCapacity"), 1, 0L, 4L, 256L);
            String poolName = Operations.string(args[0].value());
            var configuration = new WorkerPool.Configuration(integer(args[1]), integer(args[2]), integer(args[3]));
            caller.request.definePool(caller, poolName, configuration);
            return null;
        }
        if (name.equalsIgnoreCase("poolSize")) {
            args = CallArguments.builtin("FFI::poolSize", args, List.of("name"), 1);
            return (long) caller.request.poolSize(Operations.string(args[0].value()));
        }
        if (!name.equalsIgnoreCase("cdef")) throw new PhpError("Error", "Unimplemented FFI::" + name);
        args = CallArguments.builtin("FFI::cdef", args, List.of("code", "lib"), 2);
        String declarations = Operations.string(args[0].value());
        String library = Operations.string(args[1].value());
        var functions = new CDeclarations().parse(declarations);
        for (var function : functions.values()) caller.request.context.nativeAccess.bind(library, function.name(), function.signature());
        return new Binding(library, functions);
    }

    private static int integer(Argument argument) {
        Object value = PhpValues.unwrap(argument.value());
        if (!(value instanceof Long number) || number < 0 || number > 65536) throw new PhpError("ValueError", "Pool limits must be integers between 0 and 65536");
        return number.intValue();
    }

    public static Object invoke(Activation caller, Binding binding, String name, Argument[] arguments, com.oracle.truffle.api.nodes.IndirectCallNode call) {
        var function = binding.functions.get(name);
        if (function == null) throw new PhpError("FFI\\Exception", "Undefined C function " + name);
        boolean async = false;
        String pool = "ffi";
        var cArguments = new java.util.ArrayList<Argument>();
        var options = new java.util.HashSet<String>();
        for (var argument : arguments) {
            if ("async".equals(argument.name()) || "pool".equals(argument.name())) {
                if (!options.add(argument.name())) throw new PhpError("Error", "Duplicate FFI option " + argument.name());
                Object value = PhpValues.unwrap(argument.value());
                if (argument.name().equals("async")) {
                    if (!(value instanceof Boolean flag)) throw new PhpError("TypeError", "FFI async must be bool");
                    async = flag;
                } else {
                    if (!(value instanceof String text) || text.isBlank()) throw new PhpError("ValueError", "FFI pool must be a non-empty string");
                    pool = text;
                }
            } else cArguments.add(argument);
        }
        if (caller.synchronousCallback && async) throw new PhpError("FFI\\Exception", "A synchronous native callback cannot suspend");
        var names = function.parameters().stream().map(CDeclarations.Parameter::name).toList();
        var ordered = CallArguments.builtin(name, cArguments.toArray(Argument[]::new), names, names.size());
        if (function.parameters().stream().anyMatch(p -> p.type().callback())) {
            if (caller.synchronousCallback) throw new PhpError("FFI\\Exception", "A legacy NFI callback cannot suspend; use FFI::cdef");
            return NativeInvocation.invoke(caller, binding, function, ordered, async, pool, call);
        }
        var nativeArguments = new Object[ordered.length];
        for (int i = 0; i < ordered.length; i++) {
            Object value = PhpValues.unwrap(ordered[i].value());
            var type = function.parameters().get(i).type();
            if (type.callback() && !(value instanceof NativeCallback)) {
                var callback = new NativeCallback(caller, ObjectModel.callable(caller, value));
                caller.request.resources.add(callback);
                value = callback;
            } else if (type.string()) {
                if (!(value instanceof String)) throw new PhpError("TypeError", "C char* input requires a string");
            } else if (!type.callback()) {
                if (!(value instanceof Number)) throw new PhpError("TypeError", "C numeric parameter requires a number");
            }
            nativeArguments[i] = value;
        }
        if (!async) return caller.request.context.nativeAccess.call(binding.library, name, function.signature(), nativeArguments);
        var future = caller.request.workers(caller, pool).submit(caller,
                () -> caller.request.context.nativeAccess.call(binding.library, name, function.signature(), nativeArguments));
        future.cancelOnAbort = true;
        return Scheduler.awaitValue(caller, future, null);
    }
}
