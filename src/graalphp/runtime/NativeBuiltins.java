package graalphp.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import java.nio.file.Path;
import java.util.List;

/** Only Native Image uses the static link; the JVM loads the same archive catalog from the native bridge. */
@CContext(NativeBuiltins.Directives.class)
public final class NativeBuiltins {
    private NativeBuiltins() {}
    public static final class Directives implements CContext.Directives {
        @Override public List<String> getHeaderFiles() { return List.of("\"" + Path.of("src/native/builtins.h").toAbsolutePath() + "\"", "\"" + Path.of("src/native/services.h").toAbsolutePath() + "\""); }
        @Override public List<String> getOptions() { return List.of("-I" + Path.of(System.getProperty("java.home"), "lib/svm/macros/truffle-svm/builder/include")); }
        @Override public List<String> getLibraryPaths() { return List.of(Path.of("build/native/lib").toAbsolutePath().toString()); }
        @Override public List<String> getLibraries() {
            if (System.getProperty("os.name").startsWith("Windows")) return List.of("graalphp-builtins", "sqlite3", "pcre2", "curl-impersonate", "standard-curl", "standard-boringssl",
                    "boringssl", "impersonate-nghttp2", "brotli", "zstd", "zlib", "libuv",
                    "psapi", "user32", "advapi32", "iphlpapi", "userenv", "ws2_32", "dbghelp", "ole32", "shell32", "bcrypt", "crypt32", "normaliz", "winmm");
            return List.of("graalphp-builtins", "sqlite3", "pcre2", "curl-impersonate", "standard-curl", "standard-boringssl", "boringssl", "impersonate-nghttp2", "brotli", "zstd", "zlib", "uv",
                    ":libstdc++.a", ":libgcc_eh.a", "pthread", "dl", "rt", "m");
        }
    }
    @CFunction("gp_builtin_lookup") private static native long lookup(CCharPointer library, CCharPointer symbol);
    @CFunction("gp_curl_size") private static native int curlSize(long transfer);
    @CFunction("gp_curl_copy") private static native void curlCopy(long transfer, CCharPointer destination, int length);
    /** Fixed internal leaf ABI. General PHP FFI and the JVM still use NFI. */
    public static byte[] curlBody(long transfer) {
        byte[] bytes = new byte[curlSize(transfer)];
        if (bytes.length != 0) try (var pinned = org.graalvm.nativeimage.PinnedObject.create(bytes)) {
            curlCopy(transfer, pinned.addressOfArrayElement(0), bytes.length);
        }
        return bytes;
    }
    public static long address(String library, String symbol) {
        try (var cLibrary = CTypeConversion.toCString(library); var cSymbol = CTypeConversion.toCString(symbol)) {
            return lookup(cLibrary.get(), cSymbol.get());
        }
    }

    @ExportLibrary(InteropLibrary.class)
    public static final class Pointer implements TruffleObject {
        private final long address;
        public Pointer(long address) { this.address = address; }
        @ExportMessage boolean isPointer() { return true; }
        @ExportMessage long asPointer() { return address; }
    }
}
