package graalphp;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;
import java.nio.file.Path;

public final class Main {
    private Main() {}
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0 || arguments[0].equals("--version")) {
            System.out.println("GraalPHP 0.1 | PHP 8.6 subset | Truffle 25.4.4.1.1");
            if (arguments.length == 0) System.out.println("Usage: graalphp [--watch] script.php");
            return;
        }
        boolean watch = arguments[0].equals("--watch");
        boolean interpreter = arguments[0].equals("--interpreter");
        Path file = Path.of(arguments[watch || interpreter ? 1 : 0]).toAbsolutePath().normalize();
        try (var context = Context.newBuilder("php").allowAllAccess(true).allowExperimentalOptions(true)
                .option("engine.Compilation", interpreter ? "false" : "true")
                // Retire idle compiler workers and their native compilation isolates promptly.
                .option("engine.CompilerIdleDelay", System.getProperty("polyglot.engine.CompilerIdleDelay", "500"))
                .environment("GRAALPHP_ROOT", file.getParent().toString())
                .environment("GRAALPHP_WATCH", watch ? "1" : "0").build()) {
            var source = Source.newBuilder("php", file.toFile()).build();
            context.eval(source);
            if (watch) {
                System.err.println("Watching project; press Enter to run a new request, EOF to exit.");
                var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                while (input.readLine() != null) context.eval(source);
            }
        } catch (PolyglotException error) {
            System.err.println(error.getMessage());
            if (error.isInternalError()) error.printStackTrace();
            System.exit(1);
        }
    }
}
