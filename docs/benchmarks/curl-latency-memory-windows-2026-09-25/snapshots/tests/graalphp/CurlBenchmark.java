package graalphp;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Direct cURL throughput. All process counters are sampled outside the PHP runtime. */
public final class CurlBenchmark {
    private static final byte[] BODY = "r".repeat(1024).getBytes(StandardCharsets.US_ASCII);
    private record Control(String path, CompletableFuture<Void> release) {}
    private record RuntimeSpec(String name, List<String> command, int latencyEvery) {
        RuntimeSpec(String name, List<String> command) { this(name, command, LATENCY_EVERY); }
    }
    private record MemorySample(long nanos, long rss, long commit) {}
    private static final boolean PROFILE = "1".equals(System.getenv("GRAALPHP_BENCH_PROFILE"));
    private static final int WARMUP = Integer.parseInt(Objects.requireNonNullElse(System.getenv("GRAALPHP_BENCH_WARMUP"), "256"));
    private static final int LATENCY_EVERY = Integer.parseInt(Objects.requireNonNullElse(System.getenv("GRAALPHP_BENCH_LATENCY_EVERY"), "0"));
    private static final String HEADER = "runtime,repetition,parked,clients,phase,requests,seconds,requests_per_second,cpu_seconds,cpu_us_per_request,rss_mib,private_commit_mib,peak_rss_mib,distinct_peer_endpoints,user_us_per_request,kernel_us_per_request\n";
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IllegalArgumentException("The external process sampler currently supports Windows");
        int repetitions = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int clients = args.length > 2 ? Integer.parseInt(args[2]) : 64;
        int delay = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int[] populations = args.length > 4 ? Arrays.stream(args[4].split(",")).mapToInt(Integer::parseInt).toArray() : new int[] {1000, 10000};
        if (delay < 0 || repetitions < 1 || iterations < 1 || clients < 1) throw new IllegalArgumentException("Positive repetitions, iterations and clients required");
        if (Arrays.stream(populations).anyMatch(count -> count < 0)) throw new IllegalArgumentException("Negative parked population");
        if (WARMUP < 1) throw new IllegalArgumentException("Positive warmup iterations required");
        if (LATENCY_EVERY < 0) throw new IllegalArgumentException("Negative latency sampling interval");
        System.setProperty("sun.net.httpserver.maxIdleConnections", "4096");
        Path root = Path.of("").toAbsolutePath();
        Path output = Files.createTempDirectory(root.resolve("build"), "curl-benchmark-");
        var runtimes = List.of(new RuntimeSpec("graalphp", List.of(root.resolve("build/graalphp.exe").toString())),
                new RuntimeSpec("trueasync", List.of(root.resolve("tools/trueasync-0.10.0/php.exe").toString(), "-n", "-d",
                        "extension_dir=" + root.resolve("tools/trueasync-0.10.0/ext"), "-d", "extension=curl", "-d", "memory_limit=-1")));
        String executable = System.getenv("GRAALPHP_BENCH_EXECUTABLE");
        String baseline = System.getenv("GRAALPHP_BENCH_BASELINE");
        if (executable != null && !executable.isBlank()) runtimes = List.of(
                new RuntimeSpec("graalphp", List.of(root.resolve(executable).toString())), runtimes.get(1));
        String jvmClasses = System.getenv("GRAALPHP_BENCH_JVM_CLASSES");
        if (jvmClasses != null && !jvmClasses.isBlank()) runtimes = List.of(new RuntimeSpec("graalphp", List.of(
                Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "--enable-native-access=ALL-UNNAMED",
                "-cp", jvmClasses + ";build/deps/25.4.4.1.1/*", "graalphp.Main")), runtimes.get(1));
        if (baseline != null && !baseline.isBlank()) runtimes = List.of(
                new RuntimeSpec("graalphp-before", List.of(root.resolve(baseline).toString())), runtimes.getFirst());
        if (PROFILE) runtimes = List.of(new RuntimeSpec("graalphp-profile", List.of(root.resolve("build/graalphp-profile.exe").toString())));
        if ("1".equals(System.getenv("GRAALPHP_BENCH_NATIVE"))) {
            if (LATENCY_EVERY != 0) throw new IllegalArgumentException("Latency timing requires the PHP workload");
            if (Arrays.stream(populations).anyMatch(count -> count != 0)) throw new IllegalArgumentException("The C control has no parked PHP coroutines; use population 0");
            runtimes = new ArrayList<>(runtimes);
            runtimes.add(new RuntimeSpec("native-curl", List.of(root.resolve("build/curl-native-benchmark.exe").toString())));
        }
        String only = System.getenv("GRAALPHP_BENCH_ONLY");
        if (only != null && !only.isBlank()) {
            runtimes = runtimes.stream().filter(runtime -> runtime.name.equals(only)).toList();
            if (runtimes.isEmpty()) throw new IllegalArgumentException("Unknown benchmark runtime " + only);
        }
        if ("1".equals(System.getenv("GRAALPHP_BENCH_LATENCY_CONTROL"))) {
            if (LATENCY_EVERY == 0) throw new IllegalArgumentException("Set a positive latency sampling interval for paired controls");
            var paired = new ArrayList<RuntimeSpec>();
            for (var runtime : runtimes) {
                paired.add(new RuntimeSpec(runtime.name + "-untimed", runtime.command, 0));
                paired.add(runtime);
            }
            runtimes = paired;
        }
        Files.writeString(output.resolve("results.csv"), HEADER);
        Files.writeString(output.resolve("latency.csv"), "runtime,repetition,parked,sample_every,samples,mean_ms,p20_ms,p50_ms,p90_ms,p95_ms,p99_ms,max_ms\n");
        Files.writeString(output.resolve("memory.csv"), "runtime,repetition,parked,samples,rss_p20_mib,rss_p50_mib,rss_p90_mib,rss_p95_mib,rss_p99_mib,rss_max_mib,commit_p50_mib,commit_p99_mib,commit_max_mib\n");
        Files.writeString(output.resolve("phases.csv"), "runtime,repetition,parked,phase,clients,start,end\n");
        Files.writeString(output.resolve("environment.txt"), "Direct cURL; ordinary provider forced with GRAALPHP_CURL_PROVIDER=standard\n"
                + "No WebSocket, PHP HTTP server, SQLite or FFI extension/workload\n"
                + "Payload=1024 bytes; independent HTTP/1.1 keep-alive peer; " + delay + "ms response delay; validates every body\n"
                + "Warmup=64 clients * " + WARMUP + " requests before parking, excluded\n"
                + "Native C control, when selected, uses the same static reactor/libraries, has no PHP coroutines and only supports parked=0\n"
                + "Fresh PHP process and peer for each trial; alternating runtime order\n"
                + "Metrics=external Windows process CPU, working set (RSS), private commit; sampled peak every 25ms\n"
                + "Memory distribution=external samples during traffic, including endpoints; raw timestamps retained; quantiles nearest rank\n"
                + "Latency=hrtime(true) around curl_exec including guest resumption; validation/recording after timestamp; closed-loop load\n"
                + "Latency sampling interval=" + LATENCY_EVERY + "; per-client offset=client index modulo interval; warmup discarded; raw samples emitted after end\n"
                + "Measurement boundary=peer receives begin/end control calls, includes coroutine creation and drain\n"
                + "Idle samples=1s held control request; no payload traffic\n"
                + "Clients=" + clients + "; iterations per client=" + iterations + "; repetitions=" + repetitions + "; parked=" + Arrays.toString(populations) + "; profile=" + PROFILE + "\n"
                + "Interpreter=" + System.getenv("GRAALPHP_BENCH_INTERPRETER") + "; traceCompilation=" + System.getenv("GRAALPHP_BENCH_TRACE_COMPILATION") + "\n"
                + "Host=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "; logical CPUs=" + Runtime.getRuntime().availableProcessors() + "\n");
        Files.writeString(output.resolve("environment.txt"), "Commands=" + runtimes.toString().replace(root + File.separator, "") + "\n", StandardOpenOption.APPEND);
        for (var runtime : runtimes) Files.writeString(output.resolve("environment.txt"), runtime.name + " SHA256="
                + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(runtime.command.getFirst())))) + "\n", StandardOpenOption.APPEND);
        int failures = 0;
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (int parked : populations) for (var runtime : repetition % 2 == 1 ? runtimes : runtimes.reversed()) {
                try { run(root, output, runtime, repetition, parked, clients, iterations, delay); }
                catch (Exception | AssertionError failure) {
                    failures++;
                    System.err.println("FAILED " + runtime.name + " run=" + repetition + " parked=" + parked + ": " + failure);
                }
            }
        }
        System.out.println("RESULTS " + output);
        if (failures != 0) throw new AssertionError(failures + " trials failed; see " + output.resolve("failure.txt"));
    }
    private static void run(Path root, Path output, RuntimeSpec runtime, int repetition, int parked, int clients, int iterations, int delay) throws Exception {
        try (var fixture = new Fixture(delay)) {
            var command = new ArrayList<>(runtime.command);
            if (PROFILE) command.add("-XX:StartFlightRecording=settings=profile,dumponexit=true,filename="
                    + output.resolve(runtime.name + "-" + repetition + "-" + parked + ".jfr"));
            if (runtime.name.startsWith("graalphp") && "1".equals(System.getenv("GRAALPHP_BENCH_INTERPRETER"))) command.add("--interpreter");
            if (runtime.name.startsWith("graalphp") && "1".equals(System.getenv("GRAALPHP_BENCH_TRACE_COMPILATION"))) command.add(1, "-Dpolyglot.engine.TraceCompilation=true");
            if (!runtime.name.equals("native-curl")) command.add("tests/php/curl-benchmark.php");
            Path log = output.resolve(runtime.name + "-" + repetition + "-" + parked + ".log");
            var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(Map.of("BENCH_UPSTREAM", "http://127.0.0.1:" + fixture.server.getAddress().getPort(),
                    "BENCH_BODY", new String(BODY, StandardCharsets.US_ASCII), "BENCH_CLIENTS", Integer.toString(clients),
                    "BENCH_ITERATIONS", Integer.toString(iterations), "BENCH_PARKED", Integer.toString(parked), "BENCH_WARMUP", Integer.toString(WARMUP),
                    "GRAALPHP_CURL_PROVIDER", "standard", "GRAALPHP_REACTOR", "libuv", "GRAALPHP_TIMEOUT_MS", "180000"));
            builder.environment().put("BENCH_LATENCY_EVERY", Integer.toString(runtime.latencyEvery));
            Process process = builder.start();
            System.out.printf("START %s run=%d parked=%d pid=%d%n", runtime.name, repetition, parked, process.pid());
            boolean completed = false;
            try (var sampler = new NetworkBenchmark.Sampler(process.pid())) {
                NetworkBenchmark.Resources before = null;
                long begin = 0, peak = 0;
                Instant trafficStart = null;
                var memory = new ArrayList<MemorySample>();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    Control control = fixture.controls.poll(25, TimeUnit.MILLISECONDS);
                    if (before != null) {
                        var current = sampler.read();
                        peak = Math.max(peak, current.resident());
                        memory.add(new MemorySample(System.nanoTime() - begin, current.resident(), current.privateCommit()));
                    }
                    if (control == null) continue;
                    switch (control.path) {
                        case "/baseline", "/suspended" -> {
                            var idleBefore = sampler.read(); long idleStart = System.nanoTime();
                            Thread.sleep(1000);
                            var idleAfter = sampler.read();
                            save(output, runtime, repetition, parked, 0, control.path.substring(1), 0, idleStart, idleBefore, idleAfter, idleAfter.resident(), 0);
                        }
                        case "/begin" -> {
                            fixture.requests.reset(); fixture.peers.clear();
                            before = sampler.read(); begin = System.nanoTime(); peak = before.resident();
                            memory.add(new MemorySample(0, before.resident(), before.privateCommit()));
                            trafficStart = Instant.now();
                        }
                        case "/end" -> {
                            var after = sampler.read();
                            memory.add(new MemorySample(System.nanoTime() - begin, after.resident(), after.privateCommit()));
                            peak = Math.max(peak, after.resident());
                            Instant trafficEnd = Instant.now();
                            long count = fixture.requests.sum();
                            if (count != (long) clients * iterations) throw new AssertionError("Peer request count: " + count);
                            save(output, runtime, repetition, parked, clients, "curl", count, begin, before, after, peak, fixture.peers.size());
                            Files.writeString(output.resolve("phases.csv"), runtime.name + "," + repetition + "," + parked + ",curl," + clients
                                    + "," + trafficStart + "," + trafficEnd + "\n", StandardOpenOption.APPEND);
                            before = null; completed = true;
                        }
                        default -> throw new AssertionError("Unknown control " + control.path);
                    }
                    control.release.complete(null);
                }
                if (!process.waitFor(30, TimeUnit.SECONDS)) throw new AssertionError("PHP benchmark exceeded deadline while draining samples");
                String text = Files.readString(log);
                if (process.exitValue() != 0 || !text.contains("PASS curl benchmark") || !completed)
                    throw new AssertionError("Process exit=" + process.exitValue() + " (0x" + Integer.toHexString(process.exitValue())
                            + "), trafficCompleted=" + completed + "\n" + text);
                if (!text.contains("8.22.0")) throw new AssertionError("Unexpected cURL version: " + text);
                saveMemory(output, runtime, repetition, parked, memory);
                saveLatency(output, runtime, repetition, parked, clients, iterations, text);
                Files.writeString(output.resolve("environment.txt"), runtime.name + " run=" + repetition + " parked=" + parked + " "
                        + text.lines().filter(line -> line.startsWith("VERSION ")).findFirst().orElseThrow() + "\n", StandardOpenOption.APPEND);
            } catch (Exception | AssertionError error) {
                Files.writeString(output.resolve("failure.txt"), runtime.name + " run=" + repetition + " parked=" + parked + "\n" + error + "\n" + Files.readString(log),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                throw error;
            } finally {
                if (process.isAlive()) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            }
        }
    }
    private static double percentile(long[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(fraction * sorted.length) - 1)];
    }
    private static void saveLatency(Path output, RuntimeSpec runtime, int repetition, int parked,
            int clients, int iterations, String log) throws IOException {
        long[] samples = log.lines().filter(line -> line.startsWith("LATENCY "))
                .mapToLong(line -> Long.parseLong(line.substring(8))).toArray();
        long expected = 0;
        if (runtime.latencyEvery > 0) for (int client = 0; client < clients; client++) {
            int offset = client % runtime.latencyEvery;
            if (offset < iterations) expected += 1L + (iterations - 1 - offset) / runtime.latencyEvery;
        }
        if (samples.length != expected || Arrays.stream(samples).anyMatch(value -> value < 0))
            throw new AssertionError("Invalid latency samples: " + samples.length + ", expected " + expected);
        if (samples.length == 0) return;
        var raw = new StringBuilder("nanoseconds\n");
        for (long sample : samples) raw.append(sample).append('\n');
        Files.writeString(output.resolve("latency-" + runtime.name + "-" + repetition + "-" + parked + ".csv"), raw);
        Arrays.sort(samples);
        String row = String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                runtime.name, repetition, parked, runtime.latencyEvery, samples.length,
                Arrays.stream(samples).average().orElseThrow() / 1e6,
                percentile(samples, .20) / 1e6, percentile(samples, .50) / 1e6, percentile(samples, .90) / 1e6,
                percentile(samples, .95) / 1e6, percentile(samples, .99) / 1e6, samples[samples.length - 1] / 1e6);
        Files.writeString(output.resolve("latency.csv"), row, StandardOpenOption.APPEND);
        System.out.print("LATENCY " + row);
    }
    private static void saveMemory(Path output, RuntimeSpec runtime, int repetition, int parked,
            List<MemorySample> samples) throws IOException {
        if (samples.isEmpty()) throw new AssertionError("No memory samples");
        var raw = new StringBuilder("elapsed_ns,rss_bytes,private_commit_bytes\n");
        for (var sample : samples) raw.append(sample.nanos).append(',').append(sample.rss).append(',').append(sample.commit).append('\n');
        Files.writeString(output.resolve("memory-" + runtime.name + "-" + repetition + "-" + parked + ".csv"), raw);
        long[] rss = samples.stream().mapToLong(MemorySample::rss).sorted().toArray();
        long[] commit = samples.stream().mapToLong(MemorySample::commit).sorted().toArray();
        String row = String.format(Locale.ROOT, "%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                runtime.name, repetition, parked, samples.size(), percentile(rss, .20) / 1048576,
                percentile(rss, .50) / 1048576, percentile(rss, .90) / 1048576, percentile(rss, .95) / 1048576,
                percentile(rss, .99) / 1048576, rss[rss.length - 1] / 1048576.0,
                percentile(commit, .50) / 1048576, percentile(commit, .99) / 1048576, commit[commit.length - 1] / 1048576.0);
        Files.writeString(output.resolve("memory.csv"), row, StandardOpenOption.APPEND);
    }
    private static void save(Path output, RuntimeSpec runtime, int repetition, int parked, int clients, String phase, long requests,
            long begin, NetworkBenchmark.Resources before, NetworkBenchmark.Resources after, long peak, int peers) throws IOException {
        double seconds = (System.nanoTime() - begin) / 1e9, cpu = (after.cpuNanos() - before.cpuNanos()) / 1e9;
        String row = String.format(Locale.ROOT, "%s,%d,%d,%d,%s,%d,%.6f,%.3f,%.6f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f%n",
                runtime.name, repetition, parked, clients, phase, requests, seconds, requests / seconds, cpu,
                requests == 0 ? 0 : cpu * 1e6 / requests, after.resident() / 1048576.0, after.privateCommit() / 1048576.0, peak / 1048576.0, peers,
                requests == 0 ? 0 : (after.userNanos() - before.userNanos()) / 1000.0 / requests,
                requests == 0 ? 0 : (after.kernelNanos() - before.kernelNanos()) / 1000.0 / requests);
        Files.writeString(output.resolve("results.csv"), row, StandardOpenOption.APPEND);
        System.out.print(row);
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512);
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final BlockingQueue<Control> controls = new LinkedBlockingQueue<>();
        final Set<InetSocketAddress> peers = ConcurrentHashMap.newKeySet();
        final LongAdder requests = new LongAdder();
        Fixture(int delay) throws IOException {
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    byte[] body = BODY;
                    if (exchange.getRequestURI().getPath().equals("/payload")) {
                        requests.increment(); peers.add(exchange.getRemoteAddress()); if (delay > 0) Thread.sleep(delay);
                    } else {
                        var control = new Control(exchange.getRequestURI().getPath(), new CompletableFuture<>());
                        controls.add(control); control.release.get(10, TimeUnit.SECONDS);
                        body = "ok".getBytes(StandardCharsets.US_ASCII);
                    }
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (ExecutionException | TimeoutException error) { throw new IOException(error); }
                finally { exchange.close(); }
            });
            server.start();
        }
        @Override public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
