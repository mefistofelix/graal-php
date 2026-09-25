package graalphp.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.source.Source;
import java.util.concurrent.ConcurrentHashMap;

/** NFI owns ABI marshalling; PHP never generates Java classes for a signature. */
public final class NativeAccess {
    private record Symbol(String library, String name, String signature) {}
    private final TruffleLanguage.Env environment;
    private final ConcurrentHashMap<Symbol, Object> symbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> libraries = new ConcurrentHashMap<>();
    public NativeAccess(TruffleLanguage.Env environment) { this.environment = environment; }
    public Object bind(String library, String name, String signature) {
        var key = new Symbol(library, name, signature);
        Object cached = symbols.get(key);
        if (cached != null) return cached;
        Object bound = bindSymbol(key);
        Object existing = symbols.putIfAbsent(key, bound);
        return existing == null ? bound : existing;
    }
    private Object bindSymbol(Symbol symbol) {
            Object type = environment.parseInternal(Source.newBuilder("nfi", symbol.signature, "signature").build()).call();
            try {
                var interop = InteropLibrary.getUncached();
                return interop.invokeMember(type, "bind", addressObject(symbol.library, symbol.name));
            } catch (InteropException error) { throw new PhpError("Native binding: " + error.getMessage()); }
    }
    private Object addressObject(String library, String name) throws InteropException {
                if (library.startsWith("builtin:")) {
                    String builtin = library.substring(8);
                    long pointer = org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode()
                            ? NativeBuiltins.address(builtin, name)
                            : ((Number) call(bridgeLibrary(), "gp_builtin_lookup", "(STRING,STRING):UINT64", new Object[] {builtin, name})).longValue();
                    if (pointer == 0) throw new PhpError("FFI\\Exception", "Unknown built-in symbol " + library + "::" + name);
                    return new NativeBuiltins.Pointer(pointer);
                } else {
                    if (library.contains("\"") || library.contains("\n")) throw new PhpError("Invalid library path");
                    Object loaded = libraries.computeIfAbsent(library, key -> environment.parseInternal(
                            Source.newBuilder("nfi", "load \"" + key.replace('\\', '/') + "\"", "library").build()).call());
                    return InteropLibrary.getUncached().readMember(loaded, name);
                }
    }
    public long address(String library, String name) {
        try { return InteropLibrary.getUncached().asPointer(addressObject(library, name)); }
        catch (InteropException error) { throw new PhpError("Native address: " + error.getMessage()); }
    }
    private String bridgeLibrary() {
        String configured = environment.getEnvironment().get("GRAALPHP_NATIVE_LIBRARY");
        if (configured != null) return configured;
        try {
            var location = java.nio.file.Path.of(NativeAccess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var build = location.getParent();
            return build.resolve(System.getProperty("os.name").startsWith("Windows") ? "graalphp-native.dll" : "libgraalphp-native.so").toString();
        } catch (java.net.URISyntaxException error) { throw new PhpError(error.getMessage()); }
    }
    public Object call(String library, String name, String signature, Object[] arguments) {
DispatchCosts.enter(); try {
        try { return normalize(InteropLibrary.getUncached().execute(bind(library, name, signature), arguments)); }
        catch (InteropException error) { throw new PhpError("Native call: " + error.getMessage()); }
    } finally { DispatchCosts.leave(name.equals("gp_reactor_step") && ((Number) arguments[1]).longValue() > 0 ? 1 : 0); }
}
    public Object host(String name, Object[] arguments) {
        try { return normalize(InteropLibrary.getUncached().execute(environment.importSymbol(name), arguments)); }
        catch (InteropException error) { throw new PhpError("Host call: " + error.getMessage()); }
    }
    private Object normalize(Object value) throws com.oracle.truffle.api.interop.UnsupportedMessageException {
        // NFI already returns boxed Java scalars for these signatures. Keep PHP's
        // integer width without dispatching five interop predicates per scalar.
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Long) return value;
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return ((Number) value).longValue();
        var interop = InteropLibrary.getUncached();
        if (interop.isNull(value)) return null;
        if (interop.isString(value)) return interop.asString(value);
        if (interop.isBoolean(value)) return interop.asBoolean(value);
        if (interop.fitsInLong(value)) return interop.asLong(value);
        if (interop.fitsInDouble(value)) return interop.asDouble(value);
        return value;
    }
}
