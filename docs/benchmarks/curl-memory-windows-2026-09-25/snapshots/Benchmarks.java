package graalphp.lab;

import graalphp.runtime.PhpValues;

import graalphp.runtime.PhpValues.PhpArray;
import graalphp.runtime.PhpValues.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntToLongFunction;

/** Exploratory in-process measurements; not a PHP or Truffle performance comparison. */
final class Benchmarks {
    private static final PhpValues.Heap HEAP = new PhpValues.Heap();
    private static final int MEASURED_SAMPLES = 7;
    private static volatile long checksumSink;

    private record Sample(double nanosecondsPerOperation, long copies, long checksum) {}

    private Benchmarks() {}

    static void run(Path output) throws IOException {
        Files.createDirectories(output);
        var csv = new StringBuilder("case,dimension,iterations,sample,ns_per_operation,storage_copies,checksum\n");
        var summary = new StringBuilder("# Value-model benchmark\n\n");
        summary.append("Generated: ").append(Instant.now()).append("\n\n");
        summary.append("JVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.runtime.version")).append("; OS: ")
                .append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(".\n\n");
        summary.append("Maximum Java heap: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                .append(" MiB.\n\n");
        summary.append("One JVM process, 7 measured samples per case, no discarded warmup. ")
                .append("Setup and final scope cleanup are outside the timer. cow_assignment writes one source ")
                .append("scalar, assigns the array, reads an element and unsets the destination; local_write ")
                .append("writes and reads one source scalar. copy_write_release assigns, mutates and releases ")
                .append("each modified copy. path_write and reference_write each write and read a nested scalar. ")
                .append("Times include harness calls, validation and ownership bookkeeping. ")
                .append("Checksums and storage-copy counts are verified for every sample. ")
                .append("These results describe the Java lab only; they are not guest-language JIT results ")
                .append("or a comparison with PHP.\n\n");
        summary.append("| Case | Size / depth | Iterations | Median ns/op | Min ns/op | Max ns/op | Copies/op |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (int size : new int[] {8, 1_024, 8_192}) {
            record("cow_assignment", size, 100_000, csv, summary);
            record("local_write", size, 100_000, csv, summary);
            record("copy_write_release", size, Math.max(250, 2_000_000 / size), csv, summary);
        }
        for (int depth : new int[] {1, 8, 64}) {
            record("path_write", depth, 100_000, csv, summary);
            record("reference_write", depth, 100_000, csv, summary);
        }
        Files.writeString(output.resolve("benchmarks.csv"), csv);
        Files.writeString(output.resolve("benchmark-summary.md"), summary);
        System.out.print(summary);
        System.out.println("Raw samples: " + output.resolve("benchmarks.csv"));
    }

    private static void record(String name, int dimension, int iterations, StringBuilder csv, StringBuilder summary) {
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < MEASURED_SAMPLES; i++) {
            var sample = sample(name, dimension, iterations);
            samples.add(sample);
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%.2f,%d,%d\n", name, dimension, iterations,
                    i + 1, sample.nanosecondsPerOperation, sample.copies, sample.checksum));
        }
        var times = samples.stream().mapToDouble(Sample::nanosecondsPerOperation).sorted().toArray();
        summary.append(String.format(Locale.ROOT, "| %s | %d | %d | %.2f | %.2f | %.2f | %d |\n",
                name, dimension, iterations, times[times.length / 2], times[0], times[times.length - 1],
                samples.getFirst().copies / iterations));
    }

    private static Sample sample(String name, int dimension, int iterations) {
        try (var scope = new Scope(HEAP)) {
            if (name.equals("path_write") || name.equals("reference_write")) {
                var root = scope.variable(PhpArray.empty(HEAP));
                var path = root;
                for (int i = 0; i < dimension; i++) path = path.element(0);
                path.set(0L);
                var target = path;
                if (name.equals("reference_write")) {
                    try (var reference = target.reference()) {
                        return time(iterations, 0, i -> {
                            reference.set((long) i);
                            return (long) reference.read();
                        });
                    }
                }
                return time(iterations, 0, i -> {
                    target.set((long) i);
                    return (long) target.read();
                });
            }
            var values = new Object[dimension];
            Arrays.fill(values, 1L);
            var original = scope.variable(PhpArray.of(HEAP, values));
            var destination = scope.variable(null);
            var sourceElement = original.element(dimension / 2);
            var destinationElement = destination.element(dimension / 2);
            return switch (name) {
                case "cow_assignment" -> time(iterations, 0, i -> {
                    // The changing scalar also prevents an invariant read from replacing the work.
                    sourceElement.set((long) i);
                    destination.assign(original);
                    long value = (long) destinationElement.read();
                    destination.unset();
                    return value;
                });
                case "local_write" -> time(iterations, 0, i -> {
                    sourceElement.set((long) i);
                    return (long) sourceElement.read();
                });
                case "copy_write_release" -> time(iterations, iterations, i -> {
                    destination.assign(original);
                    destinationElement.set((long) i);
                    long value = (long) destinationElement.read();
                    destination.unset();
                    return value;
                });
                default -> throw new IllegalArgumentException("Unknown benchmark: " + name);
            };
        }
    }

    private static Sample time(int iterations, long expectedCopies, IntToLongFunction operation) {
        long copiesBefore = PhpValues.statistics(HEAP).storageCopies();
        long checksum = 0;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) checksum += operation.applyAsLong(i);
        long elapsed = System.nanoTime() - start;
        long copies = PhpValues.statistics(HEAP).storageCopies() - copiesBefore;
        checksumSink = checksum;
        if (checksum != (long) iterations * (iterations - 1) / 2 || copies != expectedCopies) {
            throw new AssertionError("Unexpected benchmark checksum or storage-copy count");
        }
        return new Sample((double) elapsed / iterations, copies, checksumSink);
    }
}
