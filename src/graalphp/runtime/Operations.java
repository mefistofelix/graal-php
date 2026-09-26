package graalphp.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import graalphp.truffle.PhpCompiler;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static graalphp.runtime.Execution.*;

public final class Operations {
    private Operations() {}
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static String string(Object value) {
        value = PhpValues.unwrap(value);
        if (value == null || Boolean.FALSE.equals(value)) return "";
        if (Boolean.TRUE.equals(value)) return "1";
        if (value instanceof String || value instanceof Number) return value.toString();
        if (value instanceof PhpError error) return error.getMessage();
        throw new PhpError("Value cannot be converted to string");
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static boolean truth(Object value) {
        value = PhpValues.unwrap(value);
        if (value == null || Boolean.FALSE.equals(value)) return false;
        if (value instanceof Number number) return number.doubleValue() != 0;
        if (value instanceof String text) return !text.isEmpty() && !text.equals("0");
        if (value instanceof PhpValues.PhpArray) return !PhpValues.keys(value).isEmpty();
        return true;
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Number number(Object value) {
        value = PhpValues.unwrap(value);
        if (value instanceof Number number) return number;
        if (value == null || value instanceof Boolean) return truth(value) ? 1L : 0L;
        if (value instanceof String text) {
            try { return Long.parseLong(text.strip()); }
            catch (NumberFormatException integer) {
                try { return Double.parseDouble(text.strip()); }
                catch (NumberFormatException decimal) { throw new PhpError("Non-numeric string"); }
            }
        }
        throw new PhpError("Unsupported arithmetic operand");
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object binary(String operator, Object left, Object right) {
        try {
            Object a = PhpValues.unwrap(left); Object b = PhpValues.unwrap(right);
            if (operator.equals(".")) return a instanceof PhpString || b instanceof PhpString ? PhpString.concat(a, b) : string(a) + string(b);
            if (operator.equals("===") || operator.equals("!==")) {
                boolean equal = ValueEquality.identical(a, b);
                return operator.equals("===") == equal;
            }
            if (operator.equals("==") || operator.equals("!=")) {
                boolean equal;
                if (a instanceof Boolean || b instanceof Boolean || a == null || b == null) equal = truth(a) == truth(b);
                else if (a instanceof Number && b instanceof Number) equal = number(a).doubleValue() == number(b).doubleValue();
                else equal = java.util.Objects.equals(a, b);
                return operator.equals("==") == equal;
            }
            if (operator.equals("%")) {
                long divisor = number(b).longValue(); if (divisor == 0) throw new PhpError("Modulo by zero");
                return number(a).longValue() % divisor;
            }
            var first = number(a); var second = number(b);
            if (first instanceof Long x && second instanceof Long y) {
                if (operator.equals("/") && y != 0 && !(x == Long.MIN_VALUE && y == -1) && x % y == 0) return x / y;
                try {
                    switch (operator) {
                        case "+": return Math.addExact(x, y);
                        case "-": return Math.subtractExact(x, y);
                        case "*": return Math.multiplyExact(x, y);
                    }
                } catch (ArithmeticException overflow) { /* PHP promotes integer overflow to float. */ }
            }
            double x = first.doubleValue(); double y = second.doubleValue();
            return switch (operator) {
                case "+" -> x + y; case "-" -> x - y; case "*" -> x * y;
                case "/" -> { if (y == 0) throw new PhpError("Division by zero"); yield x / y; }
                case "<" -> x < y; case "<=" -> x <= y; case ">" -> x > y; case ">=" -> x >= y;
                default -> throw new PhpError("Unknown operator " + operator);
            };
        } finally { PhpValues.drop(left); PhpValues.drop(right); }
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object invoke(Activation caller, String name, Argument[] arguments, IndirectCallNode call) {
        return invoke(caller, name, arguments, call, false);
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object invoke(Activation caller, String name, Argument[] arguments, IndirectCallNode call, boolean globalFallback) {
        name = name.toLowerCase(Locale.ROOT);
        try {
            if (caller.request.functions.containsKey(name)) return invokeFunction(caller, caller.request.function(name), arguments, call);
            if (name.startsWith("async\\")) {
                Object result = AsyncApi.function(caller, name, arguments, call);
                if (result != AsyncApi.UNHANDLED) return result;
                throw new PhpError("Unimplemented TrueAsync function " + name);
            }
            if (globalFallback && name.contains("\\")) {
                name = name.substring(name.lastIndexOf('\\') + 1);
                if (caller.request.functions.containsKey(name)) return invokeFunction(caller, caller.request.function(name), arguments, call);
            }
            return builtin(caller, name, arguments, call);
        } catch (PhpError error) {
            if (error.fatal) caller.request.fatalFailure = error;
            throw error;
        } finally { for (var argument : arguments) argument.close(); }
    }
    public static Object invokeFunction(Activation caller, Function function, Argument[] arguments, IndirectCallNode call) {
        var child = new Activation(caller.request, function, caller.task, false, arguments, Diagnostics.origin(caller));
        return executeChild(caller, child, call);
    }
    public static Object executeChild(Activation caller, Activation child, IndirectCallNode call) {
        child.synchronousCallback = caller.synchronousCallback;
        try {
            Object result = call.call(child.function.target(), child);
            if (result instanceof ContinuationResult continuation) return new NestedCall(continuation, child);
            child.escape(result); child.close(); return caller.track(result);
        } catch (PhpError error) { child.close(); throw error; }
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object invokeMember(Activation caller, String kind, String name, Object receiver, Argument[] args, IndirectCallNode call) {
        try {
            if (kind.equals("static") && string(receiver).equalsIgnoreCase("FFI")) return FfiApi.staticMethod(caller, name, args);
            if (PhpValues.unwrap(receiver) instanceof FfiApi.Binding binding) return FfiApi.invoke(caller, binding, name, args, call);
            if (PhpValues.unwrap(receiver) instanceof AsyncMutex mutex) return mutexMethod(caller, mutex, name, args, call);
            if (!kind.equals("callable")) {
                Object service = kind.equals("static")
                        ? NetworkApi.staticMethod(string(receiver), name)
                        : NetworkApi.method(caller, PhpValues.unwrap(receiver), name, args, call);
                if (service != AsyncApi.UNHANDLED) return service;
                if (!kind.equals("static")) {
                    service = SqliteApi.method(caller, PhpValues.unwrap(receiver), name, args, call);
                    if (service != AsyncApi.UNHANDLED) return service;
                }
                Object result = kind.equals("static")
                        ? AsyncApi.staticMethod(caller, string(receiver), name, args)
                        : AsyncApi.method(caller, receiver, name, args);
                if (result != AsyncApi.UNHANDLED) return result;
            }
            if (kind.equals("callable") && PhpValues.unwrap(receiver) instanceof String function && !function.contains("::")) {
                return invoke(caller, function, args, call);
            }
            if (kind.equals("callable")) {
                String type = ClassLoading.callableClass(receiver);
                if (type != null && !ClassLoading.exists(caller.request, type)) {
                    return ClassLoading.deferCallable(caller, receiver, args, type, call);
                }
            }
            var invocation = switch (kind) {
                case "callable" -> ObjectModel.callable(caller, receiver);
                case "static" -> ObjectModel.staticMethod(caller, string(receiver), name);
                case "method", "constructor", "clone" -> ObjectModel.method(caller, receiver, name, !kind.equals("method"));
                default -> throw new PhpError("Unknown invocation kind");
            };
            return ObjectModel.invoke(caller, invocation, args, call);
        } finally {
            for (var argument : args) argument.close();
            if (!kind.equals("constructor") && !kind.equals("clone")) PhpValues.drop(receiver);
        }
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object builtin(Activation activation, String name, Argument[] args, IndirectCallNode call) {
        Object enumValue = EnumApi.function(activation, name, args);
        if (enumValue != AsyncApi.UNHANDLED) return enumValue;
        Object diagnostic = Diagnostics.function(activation, name, args);
        if (diagnostic != AsyncApi.UNHANDLED) return diagnostic;
        Object loading = ClassLoading.function(activation, name, args, call);
        if (loading != AsyncApi.UNHANDLED) return loading;
        Object multi = CurlMultiApi.function(activation, name, args, call);
        if (multi != AsyncApi.UNHANDLED) return multi;
        if (name.equals("curl_init")) args = CallArguments.builtin(name, args, List.of("url", "provider"), 0, null, null);
        if (name.equals("curl_version")) args = CallArguments.builtin(name, args, List.of("provider"), 0, (Object) null);
        if (name.equals("hrtime")) args = CallArguments.builtin(name, args, List.of("as_number"), 0, false);
        CallArguments.positionalOnly(name, args);
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) values[i] = PhpValues.unwrap(args[i].value());
        if (name.startsWith("__ffi_")) return NativeInvocation.operation(activation, name, values, call);
        Object service = NetworkApi.function(activation, name, values, call);
        if (service != AsyncApi.UNHANDLED) return service;
        service = CurlApi.function(activation, name, values, call);
        if (service != AsyncApi.UNHANDLED) return service;
        service = SqliteApi.function(activation, name, values);
        if (service != AsyncApi.UNHANDLED) return service;
        switch (name) {
            case "__mutex_wait": {
                var mutex = (AsyncMutex) values[0];
                var waiter = mutex.acquire(activation.task);
                activation.request.scheduler.keep(waiter);
                return waiter;
            }
            case "__mutex_accept": ((AsyncMutex) values[0]).accept(activation.task, future(values[1])); return null;
            case "__mutex_abort": ((AsyncMutex) values[0]).abort(activation.task, future(values[1])); return null;
            case "count": require(name, values, 1); return (long) PhpValues.keys(values[0]).size();
            case "get_class":
                require(name, values, 1);
                String builtinClass = AsyncApi.className(values[0]);
                if (builtinClass == null) builtinClass = NetworkApi.className(values[0]);
                if (builtinClass == null) builtinClass = SqliteApi.className(values[0]);
                if (values[0] instanceof CurlApi.Handle) return "CurlHandle";
                if (values[0] instanceof CurlMultiApi.Handle) return "CurlMultiHandle";
                if (values[0] instanceof FfiApi.Binding) return "FFI";
                if (builtinClass != null) return builtinClass;
                if (values[0] instanceof PhpValues.PhpObject object) {
                    return object.descriptor instanceof ObjectModel.RuntimeClass type ? type.definition.name() : "Closure";
                }
                throw new PhpError("get_class requires an object");
            case "is_array": require(name, values, 1); return values[0] instanceof PhpValues.PhpArray;
            case "is_object": require(name, values, 1); return values[0] instanceof com.oracle.truffle.api.interop.TruffleObject;
            case "usleep": require(name, values, 1); return new Scheduler.Delay(Math.max(0, (number(values[0]).longValue() + 999) / 1000));
            case "sleep": require(name, values, 1); return new Scheduler.Delay(Math.multiplyExact(number(values[0]).longValue(), 1000));
            case "strlen": require(name, values, 1); return (long) PhpString.bytes(values[0]).length;
            case "hrtime":
                if (values[0] != null && !(values[0] instanceof Boolean) && !(values[0] instanceof Number)
                        && !(values[0] instanceof String) && !(values[0] instanceof PhpString))
                    throw new PhpError("TypeError", "hrtime(): Argument #1 ($as_number) must be of type bool");
                long nanos = System.nanoTime();
                if (truth(values[0])) return nanos;
                var clock = activation.locals.variable(activation.locals.emptyArray());
                clock.element(0L).set(Math.floorDiv(nanos, 1_000_000_000L));
                clock.element(1L).set(Math.floorMod(nanos, 1_000_000_000L));
                return activation.track(PhpValues.own(clock.read()));
            case "getenv":
                require(name, values, 1);
                String environmentValue = activation.request.context.environment.getEnvironment().get(string(values[0]));
                return environmentValue == null ? false : environmentValue;
            case "bin2hex": require(name, values, 1); return java.util.HexFormat.of().formatHex(PhpString.bytes(values[0]));
            case "hex2bin":
                require(name, values, 1);
                try { return PhpString.fromBytes(java.util.HexFormat.of().parseHex(string(values[0]))); }
                catch (IllegalArgumentException invalid) { return false; }
            case "intval": require(name, values, 1); return number(values[0]).longValue();
            case "strval": require(name, values, 1); return values[0] instanceof PhpString ? values[0] : string(values[0]);
            case "exception": require(name, values, 1); return new PhpError(string(values[0]));
            case "__exception": require(name, values, 1); return new PhpError(string(values[0]), values.length > 1 ? string(values[1]) : "");
            case "__chain_previous":
                var error = (PhpError) values[0];
                return values[1] == null ? error : new PhpError(error.type, error.getMessage(), (PhpError) values[1]);
            case "__protect_enter": activation.task.protectionDepth++; return null;
            case "__protect_leave":
                if (activation.task.protectionDepth <= 0) throw new PhpError("Unbalanced protection boundary");
                activation.task.protectionDepth--;
                activation.task.checkCancellation();
                return null;
            case "error_message": require(name, values, 1); return ((PhpError) values[0]).getMessage();
            case "assert": case "graal_assert":
                require(name, values, 1); if (!truth(values[0])) throw new PhpError(values.length > 1 ? string(values[1]) : "Assertion failed"); return true;
            case "sleep_ms": require(name, values, 1); return new Scheduler.Delay(number(values[0]).longValue());
            case "await": require(name, values, 1); return new Scheduler.Await(future(values[0]));
            case "cancel":
                require(name, values, 1); var future = future(values[0]); future.observed = true;
                activation.request.scheduler.cancel(future); return null;
            case "spawn":
                require(name, values, 1);
                return activation.request.scheduler.spawn(ObjectModel.callable(activation, values[0]),
                        copyArguments(args, 1), activation.task.context.branch(), false, activation.task.scope);
            case "context_get": require(name, values, 1); return activation.task.context.read(string(values[0]));
            case "context_set":
                require(name, values, 2); transferable(values[1]);
                activation.task.context = activation.task.context.with(string(values[0]), values[1]); return values[1];
            case "generation_id": return activation.request.generation.id();
            case "reload_failed": return activation.request.context.repository().reloadError != null;
            case "reload_compiled_units": return (long) activation.request.context.repository().lastCompiledUnits;
            case "shared_counter": require(name, values, 1); return new SharedCounter(number(values[0]).longValue());
            case "shared_add": require(name, values, 2); return ((SharedCounter) values[0]).add(number(values[1]).longValue());
            case "shared_get": require(name, values, 1); return ((SharedCounter) values[0]).add(0);
            case "parallel":
                require(name, values, 1); return parallel(activation, activation.request.function(string(values[0])), Arrays.copyOfRange(values, 1, values.length));
            case "ffi_call":
                require(name, values, 3); return activation.request.context.nativeAccess.call(string(values[0]), string(values[1]), string(values[2]), Arrays.copyOfRange(values, 3, values.length));
            case "ffi_call_async":
                require(name, values, 3);
                for (var value : values) if (!(value instanceof NativeCallback)) transferable(value);
                return offload(activation, () -> activation.request.context.nativeAccess.call(string(values[0]), string(values[1]), string(values[2]), Arrays.copyOfRange(values, 3, values.length)));
            case "ffi_callback":
                require(name, values, 1);
                var callback = new NativeCallback(activation, ObjectModel.callable(activation, values[0]));
                activation.request.resources.add(callback); return callback;
            case "host_call":
                require(name, values, 1); return activation.request.context.nativeAccess.host(string(values[0]), Arrays.copyOfRange(values, 1, values.length));
            case "read_file_async":
                require(name, values, 1);
                Path path = resolve(activation, string(values[0]));
                return offload(activation, () -> java.nio.file.Files.readString(path));
            case "include": case "require": case "include_once": case "require_once":
                require(name, values, 1);
                Path included = resolve(activation, string(values[0]));
                if (name.endsWith("_once") && activation.request.included.contains(included)) return 1L;
                var unit = activation.request.generation.units().get(included);
                if (unit == null) throw new PhpError("File absent from request generation: " + included.getFileName());
                activation.request.install(unit);
                return include(activation, unit.main(), call);
            case "eval":
                require(name, values, 1);
                var evaluated = PhpCompiler.compile(activation.request.context.language, Source.newBuilder("php", string(values[0]), "eval").build());
                activation.request.install(evaluated); return include(activation, evaluated.main(), call);
            default: throw new PhpError("Undefined function " + name);
        }
    }
    private static Object include(Activation caller, Function function, IndirectCallNode call) {
        // Includes execute in the caller's variable scope, while retaining their own source path.
        var child = new Activation(caller.request, function, caller.task, caller.topLevel, new Argument[0]);
        child.includedScope = caller;
        child.synchronousCallback = caller.synchronousCallback;
        try {
            Object result = call.call(function.target(), child);
            if (result instanceof ContinuationResult continuation) return new NestedCall(continuation, child);
            child.escape(result); child.close(); return caller.track(result);
        } catch (PhpError error) { child.close(); throw error; }
    }
    private static Argument[] copyArguments(Argument[] arguments, int start) {
        var copy = new Argument[arguments.length - start];
        for (int i = start; i < arguments.length; i++) copy[i - start] = new Argument(PhpValues.own(PhpValues.unwrap(arguments[i].value())), arguments[i].location(), arguments[i].name());
        return copy;
    }
    private static Path resolve(Activation activation, String filename) {
        Path path = Path.of(filename);
        if (!path.isAbsolute()) {
            Path source = activation.function.file();
            if (source == null && activation.includedScope != null) source = activation.includedScope.function.file();
            path = (source == null ? activation.request.context.repository().root() : source.getParent()).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
    private static Scheduler.Future future(Object value) {
        if (value instanceof Scheduler.Future future) return future;
        throw new PhpError("Expected a task future");
    }
    private static void require(String name, Object[] values, int count) {
        if (values.length < count) throw new PhpError("Too few arguments for " + name);
    }
    private static void transferable(Object value) {
        if (value == null || value instanceof String || value instanceof Long || value instanceof Double || value instanceof Boolean
                || value instanceof SharedCounter || value instanceof AsyncMutex || value instanceof ThreadChannel) return;
        throw new PhpError("Cross-thread/context values currently require scalars or SharedCounter");
    }
    private static Scheduler.Future parallel(Activation caller, Function function, Object[] values) {
        for (var value : values) { transferable(value); if (value instanceof SharedCounter counter) counter.promote(); }
        caller.task.context.promote();
        var functions = java.util.Map.copyOf(caller.request.functions);
        var classes = java.util.Map.copyOf(caller.request.classes);
        return offload(caller, () -> {
            try (var request = new Request(caller.request.context, caller.request.generation)) {
                request.functions.putAll(functions);
                request.classes.putAll(classes);
                var arguments = Arrays.stream(values).map(value -> new Argument(value, null)).toArray(Argument[]::new);
                var future = request.scheduler.spawn(function, arguments, caller.task.context.branch(), false);
                Object result = request.scheduler.runUntil(future);
                transferable(PhpValues.unwrap(result)); return PhpValues.unwrap(result);
            }
        });
    }
    private static Scheduler.Future offload(Activation caller, java.util.concurrent.Callable<Object> work) {
        return caller.request.workers(caller).submit(caller, work);
    }

    private static Object mutexMethod(Activation caller, AsyncMutex mutex, String name, Argument[] args, IndirectCallNode call) {
        name = name.toLowerCase(Locale.ROOT);
        if (name.equals("lock") || name.equals("synchronized")) {
            if (caller.synchronousCallback) throw new PhpError("Async\\AsyncException", "Cannot wait for a mutex in a synchronous native callback");
            var ordered = name.equals("lock")
                    ? CallArguments.builtin(name, args, java.util.List.of("cancellation"), 0, (Object) null)
                    : CallArguments.builtin(name, args, java.util.List.of("callback"), 1);
            var forwarded = new Argument[] { new Argument(mutex, null), ordered[0] };
            return invokeFunction(caller, caller.request.context.asyncFunction("mutex_" + name), forwarded, call);
        }
        CallArguments.builtin(name, args, java.util.List.of(), 0);
        return switch (name) {
            case "__construct" -> null;
            case "trylock" -> mutex.tryLock(caller.task);
            case "unlock" -> { mutex.unlock(caller.task); yield null; }
            case "islocked" -> mutex.isLocked();
            default -> throw new PhpError("Error", "Undefined method Async\\Mutex::" + name);
        };
    }
}
