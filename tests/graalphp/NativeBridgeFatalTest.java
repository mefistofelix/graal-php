package graalphp;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

/** Fatal declaration errors must not strand a C stack or execute guest catch/finally blocks. */
public final class NativeBridgeFatalTest {
    private record Case(String name, boolean async, boolean suspend) {}
    private static final String DECLARATIONS = """
        typedef int64_t (*transform)(int64_t, const char *);
        int64_t gp_test_walk(transform callback, int64_t seed, int depth, const char *text);
        int gp_test_active(void);
        int gp_test_finished(void);
        """;

    public static void main(String[] arguments) throws Exception {
        Path library = Path.of(arguments[0]).toAbsolutePath();
        Path executable = arguments.length > 1 && !arguments[1].isBlank() ? Path.of(arguments[1]).toAbsolutePath() : null;
        if (!Files.isRegularFile(library)) throw new IllegalArgumentException("Missing native bridge fixture");
        Path directory = Files.createTempDirectory(Path.of("build"), "native-fatal-").toAbsolutePath();
        String binding = "$ffi=FFI::cdef('" + DECLARATIONS + "', getenv('FFI_TEST_LIBRARY'));";
        var report = new StringBuilder();
        for (var test : List.of(new Case("sync", false, false), new Case("sync-suspended", false, true),
                new Case("async", true, false), new Case("async-suspended", true, true))) {
            Path root = directory.resolve(test.name);
            Files.createDirectories(root);
            Path script = root.resolve("main.php");
            Files.writeString(script, "<?php\n" + binding + "\n" + """
                try {
                    $ffi->gp_test_walk(function($n,$text){
                        echo 'entered:';
                        if (%s) Async\\delay(1);
                        try {eval('interface I{function f(int $n);} class C implements I{function f(string $n){}}');}
                        catch(Throwable $error){echo 'UNEXPECTED_CATCH';}
                        finally {echo 'UNEXPECTED_FINALLY';}
                        return 0;
                    },1,8,'C-stack',async:%s);
                } catch(Throwable $error){echo 'UNEXPECTED_OUTER_CATCH';}
                echo 'UNEXPECTED_AFTER';
                """.formatted(test.suspend, test.async));
            if (executable == null) {
                var output = new ByteArrayOutputStream();
                try (var context = Context.newBuilder("php").allowAllAccess(true).out(output)
                        .environment("GRAALPHP_ROOT", root.toString()).environment("GRAALPHP_WATCH", "0")
                        .environment("FFI_TEST_LIBRARY", library.toString()).build()) {
                    long before = context.eval("php", binding + "return $ffi->gp_test_finished();").asLong();
                    try {
                        context.eval(Source.newBuilder("php", script.toFile()).build());
                        throw new AssertionError("Fatal declaration returned normally");
                    } catch (PolyglotException failure) {
                        if (failure.isInternalError() || !failure.getMessage().contains("compatible")) throw failure;
                        Files.writeString(root.resolve("error.txt"), failure.getMessage());
                    }
                    if (!output.toString().equals("entered:")) throw new AssertionError(output.toString());
                    Files.writeString(root.resolve("output.txt"), output.toString());
                    String after = context.eval("php", binding + "return $ffi->gp_test_active().':'.$ffi->gp_test_finished();").asString();
                    if (!after.equals("0:" + (before + 1))) throw new AssertionError("C stack not drained: " + after);
                    report.append("PASS ").append(test.name).append(" active=0 finished_delta=1 context_reused=true\n");
                }
            } else {
                var builder = new ProcessBuilder(executable.toString(), script.toString())
                        .redirectOutput(root.resolve("output.txt").toFile()).redirectError(root.resolve("error.txt").toFile());
                builder.environment().put("FFI_TEST_LIBRARY", library.toString());
                var process = builder.start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); throw new AssertionError("Fatal callback shutdown timed out"); }
                String output = Files.readString(root.resolve("output.txt"));
                String error = Files.readString(root.resolve("error.txt"));
                if (process.exitValue() != 1 || !output.equals("entered:") || !error.contains("compatible")
                        || error.contains("shutdown") || error.contains("still active") || error.contains("Exception in thread"))
                    throw new AssertionError("Unexpected fatal callback result: " + process.exitValue() + " " + output + error);
                report.append("PASS ").append(test.name).append(" expected_exit=1 output=entered: no_shutdown_error=true\n");
            }
        }
        Files.writeString(directory.resolve("results.txt"), report);
        System.out.print(report);
        System.out.println("Evidence: " + directory);
        System.out.println("PASS: 4 fatal native-callback scenarios; " + (executable == null ? "C drain counters and context reuse checked" : "product CLI failure/shutdown checked; counters are covered by the JVM test"));
    }
}
