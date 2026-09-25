package graalphp.lab;

import graalphp.runtime.PhpValues;

import graalphp.runtime.PhpValues.PhpArray;
import graalphp.runtime.PhpValues.Scope;
import java.io.IOException;
import java.nio.file.Path;

public final class Main {
    private static final PhpValues.Heap HEAP = new PhpValues.Heap();
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--benchmark")) {
            Benchmarks.run(Path.of("build"));
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Usage: graalphp-lab [--benchmark]");
        System.out.println("GraalPHP value-model lab | target PHP " + PhpValues.PHP_TARGET);
        try (var scope = new Scope(HEAP)) {
            var original = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var copy = scope.variable(original.read());
            copy.element(0).set(99L);
            System.out.println("COW: original[0]=" + original.element(0).read()
                    + ", copy[0]=" + copy.element(0).read());

            var alias = scope.variable(null);
            alias.bind(original.element(1));
            var withReference = scope.variable(original.read());
            alias.set(42L);
            System.out.println("Reference: original[1]=" + original.element(1).read()
                    + ", copy[1]=" + withReference.element(1).read());
        }
        System.out.println("Isolated value-model diagnostic; use graalphp.Main to execute PHP on Truffle.");
    }
}
