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
    private record Control(String path, long receivedNanos, Instant receivedAt, CompletableFuture<Void> release) {}
    private record RuntimeSpec(String name, List<String> command, int latencyEvery) {
        RuntimeSpec(String name, List<String> command) { this(name, command, LATENCY_EVERY); }
    }
    private record MemorySample(long nanos, long rss, long commit, long cpuNanos) {}
    record Timing(int client, int iteration, long start, long duration) {}
    record Clock(long before, long after) {}
    private static final boolean TIMELINE = "1".equals(System.getenv("GRAALPHP_BENCH_TIMELINE"));
    private static final boolean PROFILE = "1".equals(System.getenv("GRAALPHP_BENCH_PROFILE"));
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
        if (LATENCY_EVERY < 0) throw new IllegalArgumentException("Negative latency sampling interval");
        if (TIMELINE && (LATENCY_EVERY == 0 || "1".equals(System.getenv("GRAALPHP_BENCH_LATENCY_CONTROL"))))
            throw new IllegalArgumentException("Timeline diagnostics require positive sampling and no untimed controls");
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
        String vmOptions = System.getenv("GRAALPHP_BENCH_VM_OPTIONS");
        if (vmOptions != null && !vmOptions.isBlank()) {
            var configured = new ArrayList<>(runtimes.getFirst().command);
            configured.addAll(1, vmOptions.lines().filter(option -> !option.isBlank()).toList());
            runtimes = List.of(new RuntimeSpec("graalphp", configured), runtimes.get(1));
        }
        if (baseline != null && !baseline.isBlank()) {
            var compared = new ArrayList<RuntimeSpec>();
            var baselineCommand = new ArrayList<>(List.of(root.resolve(baseline).toString()));
            String baselineOptions = System.getenv("GRAALPHP_BENCH_BASELINE_VM_OPTIONS");
            if (baselineOptions != null && !baselineOptions.isBlank())
                baselineCommand.addAll(baselineOptions.lines().filter(option -> !option.isBlank()).toList());
            compared.add(new RuntimeSpec("graalphp-before", baselineCommand));
            compared.add(runtimes.getFirst());
            if ("1".equals(System.getenv("GRAALPHP_BENCH_WITH_TRUEASYNC"))) compared.add(runtimes.get(1));
            runtimes = compared;
        }
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
        Files.writeString(output.resolve("events.csv"), "runtime,repetition,parked,event,received_elapsed_ns,observed_elapsed_ns,received_utc\n");
        Files.writeString(output.resolve("environment.txt"), "Direct cURL; ordinary provider forced with GRAALPHP_CURL_PROVIDER=standard\n"
                + "No WebSocket, PHP HTTP server, SQLite or FFI extension/workload\n"
                + "Payload=1024 bytes; independent HTTP/1.1 keep-alive peer; " + delay + "ms response delay; validates every body\n"
                + "No warmup or discarded payload requests; no pre-traffic idle wait\n"
                + "Native C control, when selected, uses the same static reactor/libraries, has no PHP coroutines and only supports parked=0\n"
                + "Fresh PHP process and peer for each trial; alternating runtime order\n"
                + "Metrics=external Windows process CPU, working set (RSS), private commit; sampled peak every 25ms\n"
                + "Memory distribution=external samples from process launch to work completion, including endpoints; raw timestamps retained; quantiles nearest rank\n"
                + "Latency=hrtime(true) around curl_exec including guest resumption; validation/recording after timestamp; closed-loop load\n"
                + "Timeline diagnostics=" + TIMELINE + "; additional guest arrays only when enabled; never pool with ordinary latency measurements\n"
                + "Clock alignment=guest timestamps bracket suspended control; host receipt gives offset bounds, not assumed shared clock origins\n"
                + "Latency sampling interval=" + LATENCY_EVERY + "; per-client offset=client index modulo interval; no samples discarded; raw samples emitted after end\n"
                + "Measurement boundary=before process launch to peer receives end; includes bootstrap, coroutine creation, payload requests and parked release; excludes sample printing and final runtime shutdown\n"
                + "Wall-time endpoints=peer control receipt; CPU/RSS endpoints=external observation immediately afterward; both timestamps in events.csv\n"
                + "Control calls return immediately; startup and suspended rows are cumulative snapshots from launch\n"
                + "Startup=first cURL control submission; suspended=after parked coroutines are ready, before first payload\n"
                + "Script=" + Objects.requireNonNullElse(System.getenv("GRAALPHP_BENCH_SCRIPT"), "tests/php/curl-benchmark.php") + "\n"
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
            if (!runtime.name.equals("native-curl")) command.add(Objects.requireNonNullElse(System.getenv("GRAALPHP_BENCH_SCRIPT"), "tests/php/curl-benchmark.php"));
            Path log = output.resolve(runtime.name + "-" + repetition + "-" + parked + ".log");
            var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(Map.of("BENCH_UPSTREAM", "http://127.0.0.1:" + fixture.server.getAddress().getPort(),
                    "BENCH_BODY", new String(BODY, StandardCharsets.US_ASCII), "BENCH_CLIENTS", Integer.toString(clients),
                    "BENCH_ITERATIONS", Integer.toString(iterations), "BENCH_PARKED", Integer.toString(parked),
                    "GRAALPHP_CURL_PROVIDER", "standard", "GRAALPHP_REACTOR", "libuv", "GRAALPHP_TIMEOUT_MS", "180000"));
            builder.environment().put("BENCH_LATENCY_EVERY", Integer.toString(runtime.latencyEvery));
            builder.environment().put("BENCH_TIMELINE", TIMELINE ? "1" : "0");
            long launch = System.nanoTime();
            Instant trafficStart = Instant.now();
            Process process = builder.start();
            System.out.printf("START %s run=%d parked=%d pid=%d%n", runtime.name, repetition, parked, process.pid());
            boolean completed = false;
            try (var sampler = new NetworkBenchmark.Sampler(process.pid())) {
                var before = new NetworkBenchmark.Resources(0, 0, 0, 0, 0, 0);
                long begin = launch, peak = 0, suspendedReceived = 0;
                var memory = new ArrayList<MemorySample>();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    Control control = fixture.controls.poll(25, TimeUnit.MILLISECONDS);
                    if (!completed) {
                        var current = sampler.read();
                        peak = Math.max(peak, current.resident());
                        memory.add(new MemorySample(System.nanoTime() - begin, current.resident(), current.privateCommit(), current.cpuNanos()));
                    }
                    if (control == null) continue;
                    Files.writeString(output.resolve("events.csv"), runtime.name + "," + repetition + "," + parked + "," + control.path
                            + "," + (control.receivedNanos - launch) + "," + (System.nanoTime() - launch) + "," + control.receivedAt + "\n", StandardOpenOption.APPEND);
                    switch (control.path) {
                        case "/begin", "/suspended" -> {
                            if (control.path.equals("/suspended")) suspendedReceived = control.receivedNanos;
                            var current = sampler.read();
                            peak = Math.max(peak, current.resident());
                            memory.add(new MemorySample(System.nanoTime() - begin, current.resident(), current.privateCommit(), current.cpuNanos()));
                            save(output, runtime, repetition, parked, 0, control.path.equals("/begin") ? "startup" : "suspended",
                                    0, control.receivedNanos - begin, before, current, peak, 0);
                        }
                        case "/end" -> {
                            var after = sampler.read();
                            memory.add(new MemorySample(System.nanoTime() - begin, after.resident(), after.privateCommit(), after.cpuNanos()));
                            peak = Math.max(peak, after.resident());
                            Instant trafficEnd = control.receivedAt;
                            long count = fixture.requests.sum();
                            if (count != (long) clients * iterations) throw new AssertionError("Peer request count: " + count);
                            save(output, runtime, repetition, parked, clients, "curl", count, control.receivedNanos - begin, before, after, peak, fixture.peers.size());
                            Files.writeString(output.resolve("phases.csv"), runtime.name + "," + repetition + "," + parked + ",curl," + clients
                                    + "," + trafficStart + "," + trafficEnd + "\n", StandardOpenOption.APPEND);
                            completed = true;
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
                if (TIMELINE) saveTimeline(output, runtime, repetition, parked, clients, iterations, text, launch, suspendedReceived);
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
        long expected = expectedSamples(clients, iterations, runtime.latencyEvery);
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
    static long expectedSamples(int clients, int iterations, int interval) {
        if (clients < 1 || iterations < 1 || interval < 0) throw new IllegalArgumentException("Invalid sampling configuration");
        long expected = 0;
        if (interval > 0) for (int client = 0; client < clients; client++) {
            int offset = client % interval;
            if (offset < iterations) expected += 1L + (iterations - 1 - offset) / interval;
        }
        return expected;
    }
    static Clock timelineClock(String log) {
        var lines = log.lines().filter(line -> line.startsWith("CLOCK ")).toList();
        if (lines.size() != 1) throw new AssertionError("Expected one clock bracket");
        String[] fields = lines.getFirst().split(" ");
        if (fields.length != 3) throw new AssertionError("Invalid clock bracket");
        var clock = new Clock(Long.parseLong(fields[1]), Long.parseLong(fields[2]));
        if (clock.after < clock.before) throw new AssertionError("Reversed clock bracket");
        return clock;
    }
    static List<Timing> timelineSamples(String log, int clients, int iterations, int interval) {
        if (interval <= 0) throw new IllegalArgumentException("Timeline requires sampling");
        var clock = timelineClock(log);
        long[] durations = log.lines().filter(line -> line.startsWith("LATENCY "))
                .mapToLong(line -> Long.parseLong(line.substring(8))).toArray();
        var samples = log.lines().filter(line -> line.startsWith("TIMELINE ")).map(line -> {
            String[] fields = line.split(" ");
            if (fields.length != 5) throw new AssertionError("Invalid timeline sample");
            return new Timing(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), Long.parseLong(fields[3]), Long.parseLong(fields[4]));
        }).toList();
        if (samples.size() != expectedSamples(clients, iterations, interval) || samples.size() != durations.length)
            throw new AssertionError("Unexpected timeline sample count");
        int index = 0;
        for (int client = 0; client < clients; client++) {
            long previousEnd = clock.after;
            for (long iteration = client % interval; iteration < iterations; iteration += interval) {
                var sample = samples.get(index);
                if (sample.client != client || sample.iteration != iteration || sample.duration < 0
                        || sample.duration != durations[index] || sample.start < previousEnd)
                    throw new AssertionError("Invalid timeline ordering, identity or duration at sample " + index);
                previousEnd = Math.addExact(sample.start, sample.duration);
                index++;
            }
        }
        return samples;
    }
    private static void saveTimeline(Path output, RuntimeSpec runtime, int repetition, int parked,
            int clients, int iterations, String log, long launch, long suspendedReceived) throws IOException {
        var clock = timelineClock(log);
        var samples = timelineSamples(log, clients, iterations, runtime.latencyEvery);
        long anchor = suspendedReceived - launch;
        String suffix = runtime.name + "-" + repetition + "-" + parked + ".csv";
        Files.writeString(output.resolve("clock-" + suffix), "host_suspended_elapsed_ns,guest_before_ns,guest_after_ns,uncertainty_ns\n"
                + anchor + "," + clock.before + "," + clock.after + "," + Math.subtractExact(clock.after, clock.before) + "\n");
        var raw = new StringBuilder("client,iteration,guest_start_ns,duration_ns,start_elapsed_lower_ns,start_elapsed_upper_ns\n");
        for (var sample : samples) {
            long lower = Math.addExact(anchor, Math.subtractExact(sample.start, clock.after));
            long upper = Math.addExact(anchor, Math.subtractExact(sample.start, clock.before));
            raw.append(sample.client).append(',').append(sample.iteration).append(',').append(sample.start).append(',')
                    .append(sample.duration).append(',').append(lower).append(',').append(upper).append('\n');
        }
        Files.writeString(output.resolve("timeline-" + suffix), raw);
    }
    private static void saveMemory(Path output, RuntimeSpec runtime, int repetition, int parked,
            List<MemorySample> samples) throws IOException {
        if (samples.isEmpty()) throw new AssertionError("No memory samples");
        var raw = new StringBuilder("elapsed_ns,rss_bytes,private_commit_bytes,cpu_ns\n");
        for (var sample : samples) raw.append(sample.nanos).append(',').append(sample.rss).append(',').append(sample.commit).append(',').append(sample.cpuNanos).append('\n');
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
            long elapsedNanos, NetworkBenchmark.Resources before, NetworkBenchmark.Resources after, long peak, int peers) throws IOException {
        double seconds = elapsedNanos / 1e9, cpu = (after.cpuNanos() - before.cpuNanos()) / 1e9;
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
                        var control = new Control(exchange.getRequestURI().getPath(), System.nanoTime(), Instant.now(), new CompletableFuture<>());
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
