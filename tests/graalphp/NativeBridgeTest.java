package graalphp;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/** Runs the production FFI API with GC on every lifetime checkpoint. */
public final class NativeBridgeTest {
    public static void main(String[] arguments) throws Exception {
        var output = new ByteArrayOutputStream();
        var collections = new AtomicInteger();
        Thread owner = Thread.currentThread();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output)
                .environment("FFI_TEST_LIBRARY", Path.of(arguments[0]).toAbsolutePath().toString())
                .environment("FFI_TEST_GC", "1").environment("GRAALPHP_REACTOR", "libuv").build()) {
            context.getPolyglotBindings().putMember("checkpoint", (ProxyExecutable) args -> {
                if (Thread.currentThread() != owner) throw new AssertionError("PHP callback changed OS thread");
                System.gc(); collections.incrementAndGet(); return 0;
            });
            context.eval(Source.newBuilder("php", Path.of("tests/php/ffi-bridge.php").toFile()).build());
        }
        if (!output.toString().startsWith("PASS transparent FFI:") || collections.get() != 80)
            throw new AssertionError(output + "; collections=" + collections.get());
        System.out.println(output + "; full-GC requests=" + collections.get());
    }
}
