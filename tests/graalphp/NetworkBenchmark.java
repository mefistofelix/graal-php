package graalphp;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static java.lang.foreign.ValueLayout.*;

/** External, closed-loop benchmark. No instrumentation is injected into either PHP runtime. */
public final class NetworkBenchmark {
    private static final String MESSAGE = "m".repeat(128), RESPONSE = "r".repeat(1024);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private record RuntimeSpec(String name, List<String> command) {}
    record Resources(long cpuNanos, long resident, long privateCommit, long lifetimePeakResident, long userNanos, long kernelNanos) {}
    private record Measurement(String runtime, int repetition, int parked, String phase, int clients,
            long operations, double seconds, double cpuSeconds, double cpuPercent, double p50ms, double p95ms,
            double p99ms, double residentMiB, double privateMiB, double peakResidentMiB) {
        String csv() {
            return String.format(Locale.ROOT, "%s,%d,%d,%s,%d,%d,%.6f,%.6f,%.3f,%.6f,%.6f,%.6f,%.3f,%.3f,%.3f%n",
                    runtime, repetition, parked, phase, clients, operations, seconds, cpuSeconds, cpuPercent,
                    p50ms, p95ms, p99ms, residentMiB, privateMiB, peakResidentMiB);
        }
    }
    private static final String HEADER = "runtime,repetition,parked,phase,clients,operations,seconds,cpu_seconds,cpu_percent_one_core,p50_ms,p95_ms,p99_ms,rss_mib,private_commit_mib,sampled_peak_rss_mib\n";
    private static final boolean PROFILE = "1".equals(System.getenv("GRAALPHP_BENCH_PROFILE"));
    private static final boolean CALLBACKS = "1".equals(System.getenv("GRAALPHP_BENCH_CALLBACKS"));
    public static void main(String[] arguments) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows"))
            throw new IllegalArgumentException("This first benchmark uses Windows process counters; Linux requires a separate sampler.");
        Path root = Path.of("").toAbsolutePath();
        int repetitions = arguments.length > 0 ? Integer.parseInt(arguments[0]) : 3;
        int seconds = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 4;
        int[] populations = arguments.length > 2 ? Arrays.stream(arguments[2].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[] {1000, 10000};
        if (repetitions < 1 || seconds < 1 || Arrays.stream(populations).anyMatch(count -> count < 1))
            throw new IllegalArgumentException("Positive repetitions, seconds and coroutine counts required");
        // JDK 25 defaults to 200 idle connections: below the 256-client workload,
        // that churns TCP connections and can exhaust Windows ephemeral ports.
        System.setProperty("sun.net.httpserver.maxIdleConnections", "4096");
        Path output = Files.createTempDirectory(root.resolve("build"), "network-benchmark-");
        List<RuntimeSpec> runtimes = List.of(
                new RuntimeSpec("graalphp-native", List.of(root.resolve("build/graalphp.exe").toString())),
                new RuntimeSpec("trueasync", List.of(root.resolve("tools/trueasync-0.10.0/php.exe").toString(),
                        "-n", "-d", "extension_dir=" + root.resolve("tools/trueasync-0.10.0/ext"),
                        "-d", "extension=true_async_server", "-d", "extension=curl", "-d", "extension=sqlite3",
                        "-d", "extension=ffi", "-d", "ffi.enable=1", "-d", "memory_limit=-1")));
        String baseline = System.getenv("GRAALPHP_BENCH_BASELINE");
        String configuredExecutable = System.getenv("GRAALPHP_BENCH_EXECUTABLE");
        if (configuredExecutable != null && !configuredExecutable.isBlank()) runtimes = List.of(
                new RuntimeSpec("graalphp-native", List.of(root.resolve(configuredExecutable).toString())), runtimes.get(1));
        if (baseline != null && !baseline.isBlank()) runtimes = List.of(
                new RuntimeSpec("graalphp-before", List.of(root.resolve(baseline).toString())), runtimes.getFirst());
        if (PROFILE) runtimes = List.of(new RuntimeSpec("graalphp-profile",
                List.of(root.resolve("build/graalphp-profile.exe").toString())));
        int[] clients = PROFILE ? new int[] {64} : System.getenv("GRAALPHP_BENCH_CLIENTS") == null
                ? new int[] {64, 256} : Arrays.stream(System.getenv("GRAALPHP_BENCH_CLIENTS").split(",")).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(clients).anyMatch(count -> count < 1)) throw new IllegalArgumentException("Positive client counts required");
        Files.writeString(output.resolve("results.csv"), HEADER);
        Files.writeString(output.resolve("fixture.csv"), "runtime,repetition,parked,http_requests,distinct_peer_endpoints\n");
        Files.writeString(output.resolve("phases.csv"), "runtime,repetition,parked,phase,clients,start,end\n");
        Files.writeString(output.resolve("environment.txt"), "OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "\nProcessors=" + Runtime.getRuntime().availableProcessors() + "\nJava=" + System.getProperty("java.version")
                + "\nRepetitions=" + repetitions + "\nSecondsPerPhase=" + seconds
                + "\nPayload=128-byte WebSocket echo; relay=1024-byte HTTP response after 5ms fixture delay, HTTP/1.1 keep-alive\n"
                + "SQLite=one in-memory database and prepared UPSERT per client; one UPSERT + primary-key SELECT per message; relay-sqlite stores the HTTP response\n"
                + "No discarded warmup; measured paths run sequentially in the same process; populations above 10000 use memory-only phases\n"
                + "Callbacks=" + CALLBACKS + "; one C call/message, 33 recursive C frames, 3 PHP callbacks each with Async\\delay(0); fixture validates C locals and owner thread\n"
                + "Populations=" + Arrays.toString(populations) + "\n"
                + "Profile=" + PROFILE + "; clients=" + Arrays.toString(clients) + "\n"
                + "Fixture=new HTTP peer for each trial; maxIdleConnections=4096; successful phases only in results.csv, failures retained separately\n"
                + "Commands=" + runtimes.toString().replace(root + File.separator, "") + "\n");
        for (RuntimeSpec runtime : runtimes) {
            Path executable = Path.of(runtime.command.getFirst());
            Files.writeString(output.resolve("environment.txt"), runtime.name + " sha256="
                    + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)))
                    + "\n", StandardOpenOption.APPEND);
        }
        if (CALLBACKS) Files.writeString(output.resolve("environment.txt"), "ffi-bridge-fixture sha256="
                + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                        Files.readAllBytes(root.resolve("build/ffi-bridge-fixture.dll")))) + "\n", StandardOpenOption.APPEND);
        int failures = 0;
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            // Alternate order to reduce warm-cache and thermal order bias.
            var ordered = repetition % 2 == 1 ? runtimes : runtimes.reversed();
            for (int parked : populations) for (RuntimeSpec runtime : ordered) {
                try (Fixture fixture = new Fixture()) {
                    try { run(root, output, fixture, runtime, repetition, parked, seconds, clients); }
                    finally {
                        Files.writeString(output.resolve("fixture.csv"), runtime.name + "," + repetition + "," + parked + ","
                                + fixture.requests.sum() + "," + fixture.peers.size() + "\n", StandardOpenOption.APPEND);
                    }
                }
                catch (Exception | AssertionError failure) {
                    failures++;
                    StringWriter details = new StringWriter();
                    failure.printStackTrace(new PrintWriter(details));
                    Files.writeString(output.resolve("failures.txt"), runtime.name + " run=" + repetition + " parked=" + parked
                            + "\n" + details + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    System.err.println("FAILED " + runtime.name + " run=" + repetition + " parked=" + parked + ": " + failure);
                }
            }
        }
        System.out.println("RESULTS " + output);
        if (failures > 0) throw new AssertionError(failures + " benchmark trials failed; see " + output.resolve("failures.txt"));
    }
    private static void run(Path root, Path output, Fixture fixture, RuntimeSpec runtime, int repetition, int parked, int seconds, int[] clientCounts) throws Exception {
        int port;
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = listener.getLocalPort(); }
        var command = new ArrayList<>(runtime.command);
        if (PROFILE) command.add("-XX:StartFlightRecording=settings=profile,dumponexit=true,filename="
                + output.resolve(runtime.name + "-" + repetition + "-" + parked + ".jfr"));
        command.add(root.resolve("tests/php/network-benchmark.php").toString());
        Path log = output.resolve(runtime.name + "-" + repetition + "-" + parked + ".log");
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("BENCH_PORT", Integer.toString(port),
                "FFI_TEST_LIBRARY", root.resolve("build/ffi-bridge-fixture.dll").toString(),
                "BENCH_UPSTREAM", "http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/",
                "GRAALPHP_TIMEOUT_MS", "180000", "GRAALPHP_REACTOR", "libuv"));
        long processStart = System.nanoTime();
        Process process = builder.start();
        String phase = "startup";
        System.out.println("START " + runtime.name + " run=" + repetition + " parked=" + parked + " pid=" + process.pid());
        try (var sampler = new Sampler(process.pid())) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            boolean ready = false;
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { ready = control(port, "/health", null).equals("ready"); } catch (IOException ignored) {}
                if (ready) break;
                Thread.sleep(25);
            }
            if (!ready) throw new AssertionError("Server failed to start: " + Files.readString(log));
            Resources started = sampler.read();
            save(output, measurement(runtime, repetition, parked, "startup", 0, 0, processStart,
                    new Resources(0, 0, 0, 0, 0, 0), started, new long[0], started.lifetimePeakResident));
            save(output, measureIdle(runtime, repetition, parked, "baseline", sampler));
            phase = "spawn";
            long parkStart = System.nanoTime();
            Resources parkBefore = sampler.read();
            if (!control(port, "/park", Integer.toString(parked)).equals("parked")) throw new AssertionError("Park failed");
            Resources parkAfter = sampler.read();
            save(output, measurement(runtime, repetition, parked, "spawn", 0, parked, parkStart, parkBefore, parkAfter,
                    new long[0], parkAfter.resident));
            phase = "suspended";
            save(output, measureIdle(runtime, repetition, parked, "suspended", sampler));
            var paths = new ArrayList<>(List.of("/echo", "/relay", "/sqlite", "/relay-sqlite"));
            if (CALLBACKS) paths.add("/callback");
            if (parked <= 10000) for (String path : paths) for (int clients : clientCounts) {
                phase = path + " clients=" + clients;
                java.time.Instant phaseStart = java.time.Instant.now();
                Measurement sample = traffic(port, path, clients, seconds, sampler);
                Files.writeString(output.resolve("phases.csv"), runtime.name + "," + repetition + "," + parked
                        + "," + path.substring(1) + "," + clients + "," + phaseStart + "," + java.time.Instant.now() + "\n",
                        StandardOpenOption.APPEND);
                save(output, new Measurement(runtime.name, repetition, parked, path.substring(1), clients,
                        sample.operations, sample.seconds, sample.cpuSeconds, sample.cpuPercent,
                        sample.p50ms, sample.p95ms, sample.p99ms, sample.residentMiB, sample.privateMiB, sample.peakResidentMiB));
            }
            phase = "release";
            if (!control(port, "/release", null).equals("released")) throw new AssertionError("Release failed");
            save(output, measureIdle(runtime, repetition, parked, "released", sampler));
            phase = "shutdown";
            control(port, "/stop", null);
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new AssertionError("Server did not exit cleanly: " + Files.readString(log));
        } catch (Exception | AssertionError failure) {
            String status = process.isAlive() ? "still alive" : "exit=" + process.exitValue() + " (0x" + Integer.toHexString(process.exitValue()) + ")";
            throw new IOException("Failure during " + phase + "; pid=" + process.pid() + "; " + status + "; log=" + log, failure);
        } finally {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
        }
    }
    private static void save(Path output, Measurement result) throws IOException {
        Files.writeString(output.resolve("results.csv"), result.csv(), StandardOpenOption.APPEND);
        System.out.printf(Locale.ROOT, "%s run=%d parked=%d %-9s c=%d ops=%d rate=%.0f/s CPU=%.1f%% RSS=%.1fMiB p95=%.2fms%n",
                result.runtime, result.repetition, result.parked, result.phase, result.clients, result.operations,
                result.operations / result.seconds, result.cpuPercent, result.residentMiB, result.p95ms);
    }
    private static String control(int port, String path, String count) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(60));
        if (count != null) request.header("X-Count", count);
        var response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " " + response.body());
        return response.body();
    }
    private static Measurement measureIdle(RuntimeSpec runtime, int repetition, int parked, String phase, Sampler sampler) throws Exception {
        Thread.sleep(300);
        Resources before = sampler.read();
        long start = System.nanoTime(), peak = before.resident;
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1)) {
            Thread.sleep(50); peak = Math.max(peak, sampler.read().resident);
        }
        return measurement(runtime, repetition, parked, phase, 0, 0, start, before, sampler.read(), new long[0], peak);
    }
    private static Measurement measurement(RuntimeSpec runtime, int repetition, int parked, String phase, int clients,
            long count, long start, Resources before, Resources after, long[] latency, long peak) {
        double seconds = (System.nanoTime() - start) / 1e9, cpu = (after.cpuNanos - before.cpuNanos) / 1e9;
        Arrays.sort(latency);
        return new Measurement(runtime == null ? "" : runtime.name, repetition, parked, phase, clients, count, seconds,
                cpu, cpu / seconds * 100, percentile(latency, 0.50), percentile(latency, 0.95), percentile(latency, 0.99),
                after.resident / 1048576.0, after.privateCommit / 1048576.0, peak / 1048576.0);
    }
    private static double percentile(long[] values, double fraction) {
        return values.length == 0 ? 0 : values[Math.min(values.length - 1, (int) Math.ceil(values.length * fraction) - 1)] / 1e6;
    }
    private static Measurement traffic(int port, String path, int concurrency, int seconds, Sampler sampler) throws Exception {
        var clients = new ArrayList<Client>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < concurrency; i++) clients.add(new Client(port, path));
                var startGate = new CountDownLatch(1);
                var stop = new AtomicBoolean();
                var futures = new ArrayList<Future<long[]>>();
                for (Client client : clients) futures.add(workers.submit(() -> {
                    var timings = new LongSamples();
                    startGate.await();
                    while (!stop.get()) {
                        long start = System.nanoTime();
                        client.socket.sendText(MESSAGE, true).join();
                        String reply = client.messages.poll(20, TimeUnit.SECONDS);
                        if (!(path.startsWith("/relay") ? RESPONSE : MESSAGE).equals(reply)) throw new AssertionError("Response mismatch: " + reply);
                        timings.add(System.nanoTime() - start);
                    }
                    return timings.array();
                }));
                Resources before = sampler.read();
                long started = System.nanoTime(), peak = before.resident;
                startGate.countDown();
                while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(seconds)) {
                    Thread.sleep(40); peak = Math.max(peak, sampler.read().resident);
                }
                stop.set(true);
                var timings = new LongSamples();
                for (var future : futures) timings.addAll(future.get(25, TimeUnit.SECONDS));
                return measurement(null, 0, 0, path, concurrency, timings.size, started, before, sampler.read(), timings.array(), peak);
            } finally { for (var client : clients) client.socket.abort(); }
        }
    }
    private static final class LongSamples {
        long[] values = new long[4096]; int size;
        void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = value;
        }
        void addAll(long[] samples) { for (long sample : samples) add(sample); }
        long[] array() { return Arrays.copyOf(values, size); }
    }
    private static final class Client implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final StringBuilder fragments = new StringBuilder();
        final WebSocket socket;
        Client(int port, String path) throws Exception {
            socket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + path), this).get(20, TimeUnit.SECONDS);
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            fragments.append(text);
            if (last) { messages.add(fragments.toString()); fragments.setLength(0); }
            socket.request(1); return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { messages.add("ERROR:" + error); }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            messages.add("CLOSED:" + code); return null;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<InetSocketAddress> peers = ConcurrentHashMap.newKeySet();
        final LongAdder requests = new LongAdder();
        Fixture() throws IOException {
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    peers.add(exchange.getRemoteAddress());
                    requests.increment();
                    Thread.sleep(5);
                    byte[] bytes = RESPONSE.getBytes(StandardCharsets.US_ASCII);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        @Override public void close() { server.stop(0); workers.close(); }
    }
    /** Win64 PROCESS_MEMORY_COUNTERS_EX and GetProcessTimes; sampling runs outside the target. */
    static final class Sampler implements AutoCloseable {
        final Arena arena = Arena.ofConfined();
        final MemorySegment process, memory = arena.allocate(80, 8), times = arena.allocate(32, 8);
        final MethodHandle memoryInfo, processTimes, close;
        Sampler(long pid) {
            var kernel = SymbolLookup.libraryLookup("kernel32", arena);
            var psapi = SymbolLookup.libraryLookup("psapi", arena);
            var linker = Linker.nativeLinker();
            var open = linker.downcallHandle(kernel.find("OpenProcess").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            memoryInfo = linker.downcallHandle(psapi.find("GetProcessMemoryInfo").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            processTimes = linker.downcallHandle(kernel.find("GetProcessTimes").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            close = linker.downcallHandle(kernel.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            try { process = (MemorySegment) open.invokeExact(0x1010, 0, (int) pid); }
            catch (Throwable error) { throw new IllegalStateException(error); }
            if (process.address() == 0) throw new IllegalStateException("Cannot open target process " + pid);
            memory.set(JAVA_INT, 0, 80);
        }
        Resources read() {
            try {
                if ((int) memoryInfo.invokeExact(process, memory, 80) == 0
                        || (int) processTimes.invokeExact(process, times.asSlice(0, 8), times.asSlice(8, 8), times.asSlice(16, 8), times.asSlice(24, 8)) == 0)
                    throw new IllegalStateException("Cannot sample target process");
                return new Resources((times.get(JAVA_LONG, 16) + times.get(JAVA_LONG, 24)) * 100,
                        memory.get(JAVA_LONG, 16), memory.get(JAVA_LONG, 72), memory.get(JAVA_LONG, 8),
                        times.get(JAVA_LONG, 24) * 100, times.get(JAVA_LONG, 16) * 100);
            } catch (RuntimeException error) { throw error; }
            catch (Throwable error) { throw new IllegalStateException(error); }
        }
        @Override public void close() {
            try { int ignored = (int) close.invokeExact(process); }
            catch (Throwable error) { throw new IllegalStateException(error); }
            finally { arena.close(); }
        }
    }
}
