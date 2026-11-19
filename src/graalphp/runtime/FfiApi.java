package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import static graalphp.runtime.Execution.*;

/** PHP-style declarations plus per-invocation async/pool options, without a second PHP runtime. */
public final class FfiApi {
    private static final Pattern BYTE_ARRAY = Pattern.compile(
            "(?:char|signed char|unsigned char|int8_t|uint8_t)\\s*\\[\\s*([0-9]+)\\s*\\]");
    private FfiApi() {}
    public record Binding(String library, Map<String, CDeclarations.Function> functions) implements TruffleObject {}

    static final class CData extends PhpValues.HeapNode implements TruffleObject {
        final NativeAccess access;
        final int length;
        final long address;

        CData(Activation caller, int length) {
            super(caller.request.heap);
            access = caller.request.context.nativeAccess;
            this.length = length;
            address = ((Number) memory(access, "alloc", "(SINT32):UINT64", length)).longValue();
            if (address == 0) throw new PhpError("FFI\\Exception", "Cannot allocate C buffer");
        }

        @Override void children(java.util.function.Consumer<PhpValues.HeapNode> visit) {}
        @Override void discard() { memory(access, "free", "(UINT64):VOID", address); }
    }

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
        if (name.equalsIgnoreCase("new")) Diagnostics.deprecated(caller, "Calling FFI::new() statically is deprecated");
        Object memory = memoryMethod(caller, name, args);
        if (memory != AsyncApi.UNHANDLED) return memory;
        if (!name.equalsIgnoreCase("cdef")) throw new PhpError("Error", "Unimplemented FFI::" + name);
        args = CallArguments.builtin("FFI::cdef", args, List.of("code", "lib"), 2);
        String declarations = Operations.string(args[0].value());
        String library = Operations.string(args[1].value());
        var functions = new CDeclarations().parse(declarations);
        for (var function : functions.values()) caller.request.context.nativeAccess.bind(library, function.name(), function.signature());
        return new Binding(library, functions);
    }

    private static Object memoryMethod(Activation caller, String name, Argument[] args) {
        var access = caller.request.context.nativeAccess;
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "new": {
                args = CallArguments.builtin("FFI::new", args, List.of("type", "owned", "persistent"), 1, true, false);
                if (!Boolean.TRUE.equals(PhpValues.unwrap(args[1].value())) || !Boolean.FALSE.equals(PhpValues.unwrap(args[2].value())))
                    throw new PhpError("FFI\\Exception", "Only owned request-lifetime C buffers are supported");
                var declared = BYTE_ARRAY.matcher(Operations.string(args[0].value()).strip());
                if (!declared.matches()) throw new PhpError("FFI\\ParserException", "Expected a fixed-size C byte array");
                int length;
                try { length = Integer.parseInt(declared.group(1)); }
                catch (NumberFormatException error) { throw new PhpError("ValueError", "C buffer size exceeds int32"); }
                if (length == 0) throw new PhpError("ValueError", "C buffer size must be positive");
                return caller.track(PhpValues.own(new CData(caller, length)));
            }
            case "sizeof":
                args = CallArguments.builtin("FFI::sizeof", args, List.of("ptr"), 1);
                return (long) buffer(caller, args[0].value()).length;
            case "string": {
                args = CallArguments.builtin("FFI::string", args, List.of("ptr", "size"), 1, (Object) null);
                var source = buffer(caller, args[0].value());
                boolean terminated = args[1].value() == null;
                int length = terminated ? source.length : memoryLength(args[1], source.length);
                byte[] bytes = new byte[length];
                if (length > 0) access.call("builtin:runtime", "gp_bytes_copy", "(UINT64,[UINT8],SINT32):VOID",
                        new Object[] {source.address, bytes, length});
                if (terminated) {
                    int end = 0;
                    while (end < bytes.length && bytes[end] != 0) end++;
                    if (end < bytes.length) bytes = java.util.Arrays.copyOf(bytes, end);
                }
                return PhpString.fromBytes(bytes);
            }
            case "memcpy": {
                args = CallArguments.builtin("FFI::memcpy", args, List.of("to", "from", "size"), 3);
                var destination = buffer(caller, args[0].value());
                int length = memoryLength(args[2], destination.length);
                Object input = PhpValues.unwrap(args[1].value());
                if (input instanceof CData) {
                    var source = buffer(caller, input);
                    memoryLength(args[2], source.length);
                    memory(access, "move", "(UINT64,UINT64,SINT32):VOID", destination.address, source.address, length);
                } else if (input instanceof String || input instanceof PhpString) {
                    byte[] bytes = PhpString.bytes(input);
                    memoryLength(args[2], bytes.length);
                    memory(access, "write", "(UINT64,[UINT8],SINT32):VOID", destination.address, bytes, length);
                } else throw new PhpError("TypeError", "FFI::memcpy() requires a C buffer or string source");
                return null;
            }
            case "memset": {
                args = CallArguments.builtin("FFI::memset", args, List.of("ptr", "value", "size"), 3);
                var destination = buffer(caller, args[0].value());
                int length = memoryLength(args[2], destination.length);
                long value = (Long) TypeRelations.check(PhpValues.unwrap(args[1].value()), "int", caller.function.strictTypes(), Diagnostics.origin(caller));
                memory(access, "fill", "(UINT64,SINT32,SINT32):VOID", destination.address, (int) value, length);
                return null;
            }
            default: return AsyncApi.UNHANDLED;
        }
    }

    static CData buffer(Activation caller, Object value) {
        if (!(PhpValues.unwrap(value) instanceof CData buffer) || buffer.heap != caller.request.heap)
            throw new PhpError("TypeError", "Expected FFI\\CData owned by this request");
        return buffer;
    }

    private static int memoryLength(Argument argument, int capacity) {
        if (!(PhpValues.unwrap(argument.value()) instanceof Long length) || length < 0 || length > capacity)
            throw new PhpError("ValueError", "C memory operation exceeds the available buffer");
        return length.intValue();
    }

    private static Object memory(NativeAccess access, String operation, String signature, Object... arguments) {
        return access.call("builtin:runtime", "gp_ffi_buffer_" + operation, signature, arguments);
    }

    private static int integer(Argument argument) {
        Object value = PhpValues.unwrap(argument.value());
        if (!(value instanceof Long number) || number < 0 || number > 65536) throw new PhpError("ValueError", "Pool limits must be integers between 0 and 65536");
        return number.intValue();
    }

    public static Object invoke(Activation caller, Binding binding, String name, Argument[] arguments, com.oracle.truffle.api.nodes.IndirectCallNode call) {
        if (name.equalsIgnoreCase("new")) return memoryMethod(caller, name, arguments);
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
        boolean bridge = false;
        for (int i = 0; i < ordered.length; i++) {
            var type = function.parameters().get(i).type();
            if (type.callback() || type.pointer() || type.string() && !(PhpValues.unwrap(ordered[i].value()) instanceof String)) {
                bridge = true;
                break;
            }
        }
        if (bridge) {
            if (caller.synchronousCallback) throw new PhpError("FFI\\Exception", "A legacy NFI callback cannot suspend; use FFI::cdef");
            return NativeInvocation.invoke(caller, binding, function, ordered, async, pool, call);
        }
        var nativeArguments = new Object[ordered.length];
        for (int i = 0; i < ordered.length; i++) {
            Object value = PhpValues.unwrap(ordered[i].value());
            var type = function.parameters().get(i).type();
            if (type.string()) {
                if (!(value instanceof String)) throw new PhpError("TypeError", "C char* input requires a string");
            } else {
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
