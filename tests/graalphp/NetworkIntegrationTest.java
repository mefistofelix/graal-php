package graalphp;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent network peers exercise the real PHP executable, TLS provider and on-disk SQLite. */
public final class NetworkIntegrationTest {
    private static final String REMOTE = "fixture-body";
    private static int checks;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public static void main(String[] arguments) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String nativeExecutable = arguments.length == 0 || arguments[0].isEmpty() ? null : Path.of(arguments[0]).toAbsolutePath().toString();
        Path artifacts = Files.createTempDirectory(root.resolve("build"), nativeExecutable == null ? "network-jvm-" : "network-native-");
        try (Fixture fixture = new Fixture(artifacts); TlsProxy proxy = new TlsProxy(fixture.port())) {
            int port = freePort();
            Path database = artifacts.resolve("messages.sqlite");
            var command = command(root, nativeExecutable);
            command.add(root.resolve("examples/websocket-sqlite.php").toString());
            var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(artifacts.resolve("server.log").toFile());
            builder.environment().putAll(Map.of(
                    "GRAALPHP_DEMO_PORT", Integer.toString(port), "GRAALPHP_DEMO_DB", database.toString(),
                    "GRAALPHP_DEMO_UPSTREAM", "https://localhost:" + proxy.port(), "GRAALPHP_DEMO_CA", fixture.certificate.toString(),
                    "GRAALPHP_TIMEOUT_MS", "120000", "GRAALPHP_REACTOR", "libuv"));
            Process server = builder.start();
            long started = System.nanoTime();
            try {
                ready(server, port, artifacts);
                equal("HTTP route", "ready", get(port, "/health"));
                equal("transaction rollback", "0", get(port, "/rollback"));
                equal("ordinary curl TLS request", REMOTE, get(port, "/baseline"));
                try (Client client = new Client(port, "/concurrent-read")) {
                    equal("single-reader contract and cancellation", "concurrent-read-rejected", client.next());
                }
                try (Client client = new Client(port)) {
                    client.send("CURL-ERRORS");
                    equal("immediate curl failure, reuse and PHP 8 curl_close identity", "curl-errors-and-reuse-ok", client.next());
                    client.send("CANCEL-CURL");
                    equal("curl cancellation task started", "cancel-started", client.next());
                    check("cancelled request reached HTTPS peer", fixture.cancelStarted.await(10, TimeUnit.SECONDS));
                    client.send("cancel-now");
                    try {
                        equal("curl cancellation and same-handle reuse before peer release", "cancelled-and-reused:" + REMOTE, client.next());
                    } finally { fixture.cancelRelease.countDown(); }
                }
                try (Client client = new Client(port)) {
                    client.socket.sendText("héllo ", false).get(5, TimeUnit.SECONDS);
                    client.socket.sendText("🚀", true).get(5, TimeUnit.SECONDS);
                    response("fragmented UTF-8 text", client.next(), "héllo 🚀");
                    String longMessage = "x".repeat(70000);
                    client.socket.sendText(longMessage, true).get(5, TimeUnit.SECONDS);
                    response("64-bit frame length", client.next(), longMessage);
                    byte[] binary = new byte[4096];
                    for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;
                    client.socket.sendBinary(ByteBuffer.wrap(binary, 0, 2031), false).get(5, TimeUnit.SECONDS);
                    client.socket.sendBinary(ByteBuffer.wrap(binary, 2031, binary.length - 2031), true).get(5, TimeUnit.SECONDS);
                    check("fragmented binary, NUL and non-UTF-8 bytes", Arrays.equals(binary, (byte[]) client.next()));
                    client.socket.sendPing(ByteBuffer.wrap(new byte[] {1, 2, 3})).get(5, TimeUnit.SECONDS);
                    check("ping/pong", client.next() instanceof Pong);
                    client.send("TLS-FAIL");
                    equal("untrusted certificate rejected", "TLS:60", client.next());
                    client.send("TIMEOUT");
                    equal("curl deadline", "TIMEOUT:28", client.next());
                }
                try (Client client = new Client(port)) {
                    client.send("CURL-MULTI-BATCH");
                    equal("explicit multi batch starts", "multi-batch-started", client.next());
                    check("two multi transfers reach the HTTPS barrier", fixture.multiBatchStarted.await(10, TimeUnit.SECONDS));
                    equal("HTTP progresses during explicit multi transfers", "ready", get(port, "/health"));
                    fixture.multiBatchRelease.countDown();
                    equal("multi results, content, error queue, SQLite and close/reuse", "multi-batch-ok:6:1", client.next());
                    equal("native multi connection limit is respected", 2, fixture.multiBatchPeak.get());
                    client.send("CURL-MULTI-CANCEL");
                    equal("multi cancellation scenario starts", "multi-cancel-started", client.next());
                    check("both cancellable multi requests reach the HTTPS peer", fixture.multiHeldStarted.await(10, TimeUnit.SECONDS));
                    client.send("cancel-wait");
                    equal("cancelling select preserves transfers and the other waiter", "multi-wait-cancelled-transfers-active", client.next());
                    equal("remove and same-handle reuse before peer release", "multi-removed-and-reused", client.next());
                    fixture.multiWaitRelease.countDown();
                    client.send("release-first");
                    equal("surviving transfer completes, next multi starts", "multi-close-started", client.next());
                    check("transfer is active before multi_close", fixture.multiCloseStarted.await(10, TimeUnit.SECONDS));
                    client.send("close-now");
                    equal("multi_close wakes select and permits immediate reuse", "multi-close-and-reuse-ok", client.next());
                    fixture.multiRemoveRelease.countDown();
                    fixture.multiCloseRelease.countDown();
                }
                int clients = 16, messages = 4;
                long workloadStarted = System.nanoTime();
                fixture.hold = new CountDownLatch(1);
                try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = new ArrayList<Future<?>>();
                    for (int i = 0; i < clients; i++) {
                        int index = i;
                        futures.add(workers.submit(() -> {
                            try (Client client = new Client(port)) {
                                for (int j = 0; j < messages; j++) {
                                    String payload = index == 0 && j == 0 ? "quote'; DROP TABLE messages; --" : "client-" + index + "-message-" + j;
                                    client.send(payload);
                                    Object result = client.next();
                                    if (!(result instanceof String text) || !text.matches("(?s)OK:[0-9]+:.*") || !text.endsWith(":" + payload + ":" + REMOTE)) {
                                        throw new AssertionError("Concurrent response mismatch: " + result);
                                    }
                                }
                            } catch (Exception error) { throw new RuntimeException(error); }
                        }));
                    }
                    try {
                        check("eight reactor transfers reached the upstream concurrently", fixture.workloadStarted.await(10, TimeUnit.SECONDS));
                        equal("HTTP progresses while curl transfers are blocked", "ready", get(port, "/health"));
                        try (RawWebSocket control = new RawWebSocket(port)) {
                            control.send(9, true, true, new byte[] {42});
                            RawFrame pong = control.read();
                            check("libuv ping progresses while curl transfers are blocked", pong.opcode == 10 && Arrays.equals(pong.data, new byte[] {42}));
                        }
                    } finally { fixture.hold.countDown(); }
                    for (Future<?> future : futures) future.get(40, TimeUnit.SECONDS);
                }
                check("16 concurrent clients, 64 committed requests", true);
                check("curl concurrency exceeds the former four-worker pool", fixture.maxActive.get() >= 8);
                System.out.printf(Locale.ROOT, "WORKLOAD: %d clients, %d messages, %.3f s, upstream peak concurrency %d%n",
                        clients, clients * messages, (System.nanoTime() - workloadStarted) / 1e9, fixture.maxActive.get());
                try (RawWebSocket raw = new RawWebSocket(port)) {
                    raw.send(1, false, true, "frag".getBytes(StandardCharsets.UTF_8));
                    raw.send(9, true, true, new byte[] {8, 9});
                    raw.send(0, true, true, "mented".getBytes(StandardCharsets.UTF_8));
                    RawFrame pong = raw.read();
                    check("control frame between fragments", pong.opcode == 10 && Arrays.equals(pong.data, new byte[] {8, 9}));
                    response("message after interleaved ping", new String(raw.read().data, StandardCharsets.UTF_8), "fragmented");
                    raw.send(8, true, true, new byte[] {3, (byte) 232});
                    equal("close handshake", 1000, closeCode(raw.read()));
                }
                protocolClose(port, "unmasked client frame", 1002, raw -> raw.send(1, true, false, new byte[] {65}));
                protocolClose(port, "invalid text UTF-8", 1007, raw -> raw.send(1, true, true, new byte[] {(byte) 255}));
                protocolClose(port, "oversize frame rejected before allocation", 1009, raw -> {
                    raw.output.write(new byte[] {(byte) 0x81, (byte) 0xff, 0, 0, 0, 0, 0, 8, 0, 0});
                    raw.output.flush();
                });
                protocolClose(port, "invalid continuation", 1002, raw -> raw.send(0, true, true, new byte[] {65}));
                try (Socket invalid = new Socket("127.0.0.1", port)) {
                    invalid.setSoTimeout(5000);
                    invalid.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: invalid\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    check("malformed upgrade rejected", readHeaders(invalid.getInputStream()).startsWith("HTTP/1.1 400"));
                }
                try (RawWebSocket ignored = new RawWebSocket(port)) { /* Abrupt TCP disconnect, without a close frame. */ }
                equal("server survives abrupt disconnect", "ready", get(port, "/health"));
                int expected = clients * messages + 3;
                equal("persisted row count while serving", Integer.toString(expected), get(port, "/count"));
                check("Chrome headers observed on the wire", fixture.chromeRequests.get() >= expected);
                check("TLS ClientHello contains Chrome cipher ordering and GREASE",
                        proxy.hellos.stream().anyMatch(Hello::chrome));
                check("profile changes TLS fingerprint beyond User-Agent",
                        proxy.hellos.stream().map(Hello::fingerprint).distinct().count() >= 2);
                Files.write(artifacts.resolve("client-hellos.txt"), proxy.hellos.stream().map(Object::toString).toList());
                try (RawWebSocket idle = new RawWebSocket(port)) {
                    equal("graceful stop route", "stopping", get(port, "/shutdown"));
                    check("idle connection drained within shutdown deadline", idle.input.read() == -1);
                }
                check("server exits without a live reactor/worker", server.waitFor(10, TimeUnit.SECONDS) && server.exitValue() == 0);
                verifyDatabase(root, nativeExecutable, artifacts, database, expected);
                byte[] header = Files.readAllBytes(database);
                check("SQLite file format on disk", new String(header, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3"));
                System.out.printf(Locale.ROOT, "PASS: %d network assertions in %.3f s; artifacts: %s%n", checks, (System.nanoTime() - started) / 1e9, artifacts);
            } catch (Throwable error) {
                System.err.println("SERVER LOG:\n" + Files.readString(artifacts.resolve("server.log")));
                throw error;
            } finally {
                if (server.isAlive()) { server.destroy(); if (!server.waitFor(3, TimeUnit.SECONDS)) server.destroyForcibly(); }
            }
        }
    }
    private static List<String> command(Path root, String nativeExecutable) {
        if (nativeExecutable != null) return new ArrayList<>(List.of(nativeExecutable));
        String separator = System.getProperty("path.separator");
        String classpath = root.resolve("build/classes") + separator + root.resolve("build/deps/25.4.4.1.1") + "/*";
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "graalphp.Main"));
    }
    private static void verifyDatabase(Path root, String nativeExecutable, Path artifacts, Path database, int expected) throws Exception {
        Path script = artifacts.resolve("verify.php");
        Files.writeString(script, """
            <?php
            $db = new SQLite3(getenv('GRAALPHP_DEMO_DB'));
            echo $db->querySingle('SELECT count(*) FROM messages'), '|';
            echo $db->querySingle('SELECT count(*) FROM messages WHERE status != 200'), '|';
            echo $db->querySingle('SELECT count(*) FROM rollback_probe'), '|';
            $result = $db->query('SELECT payload, remote FROM messages ORDER BY id LIMIT 1');
            $row = $result->fetchArray(SQLITE3_ASSOC);
            echo $row['payload'], '|', $row['remote'];
            $result->finalize();
            $db->exec('CREATE TABLE type_probe (a TEXT, b BLOB, c INTEGER, d REAL, e TEXT)');
            $statement = $db->prepare('INSERT INTO type_probe VALUES (?, ?, ?, ?, ?)');
            $statement->bindValue(1, '', SQLITE3_TEXT);
            $statement->bindValue(2, hex2bin('00ff80fe'), SQLITE3_BLOB);
            $statement->bindValue(3, 5000000000, SQLITE3_INTEGER);
            $statement->bindValue(4, 0.125, SQLITE3_FLOAT);
            $statement->bindValue(5, null, SQLITE3_NULL);
            $insert = $statement->execute();
            $insert->finalize();
            $statement->close();
            $types = $db->query('SELECT a, b, c, d, e FROM type_probe');
            $row = $types->fetchArray(SQLITE3_NUM);
            graal_assert($row[0] === '' && bin2hex($row[1]) === '00ff80fe');
            graal_assert($row[2] === 5000000000 && $row[3] === 0.125 && $row[4] === null);
            graal_assert($types->fetchArray() === false);
            $types->finalize();
            echo '|types-ok';
            graal_assert($db->querySingle("SELECT count(*) FROM multi_results WHERE status = 200 AND body = 'fixture-body'") === 6);
            $db->close();
            """);
        var command = command(root, nativeExecutable); command.add(script.toString());
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectError(artifacts.resolve("reopen-error.log").toFile());
        builder.environment().put("GRAALPHP_DEMO_DB", database.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check("fresh process reopens database", process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0);
        equal("persisted values, rollback and SQLite binding types after restart", expected + "|0|0|héllo 🚀|" + REMOTE + "|types-ok", output);
    }
    private static void ready(Process process, int port, Path artifacts) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError("Server exited " + process.exitValue());
            try { if (get(port, "/health").equals("ready")) return; } catch (IOException ignored) {}
            Thread.sleep(50); // Test startup only; neither runtime event loop uses a polling tick.
        }
        throw new AssertionError("Server did not become ready; " + artifacts);
    }
    private static String get(int port, String path) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new AssertionError("HTTP status " + response.statusCode());
        return response.body();
    }
    private static int freePort() throws IOException { try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
    private static void check(String name, boolean condition) { if (!condition) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    private static void equal(String name, Object expected, Object actual) { check(name + (Objects.equals(expected, actual) ? "" : ": expected " + expected + ", got " + actual), Objects.equals(expected, actual)); }
    private static void response(String name, Object result, String payload) {
        check(name, result instanceof String text && text.matches("(?s)OK:[0-9]+:.*") && text.endsWith(":" + payload + ":" + REMOTE));
    }
    private record Pong() {}
    private record Closed(int code) {}
    private static final class Client implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        final StringBuilder text = new StringBuilder();
        final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        final WebSocket socket;
        Client(int port) throws Exception { this(port, "/"); }
        Client(int port, String path) throws Exception { socket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create("ws://127.0.0.1:" + port + path), this).get(10, TimeUnit.SECONDS); }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data); if (last) { events.add(text.toString()); text.setLength(0); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); binary.writeBytes(bytes);
            if (last) { events.add(binary.toByteArray()); binary.reset(); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) { events.add(new Pong()); socket.request(1); return null; }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { events.add(new Closed(code)); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { events.add(error); }
        void send(String text) throws Exception { socket.sendText(text, true).get(5, TimeUnit.SECONDS); }
        Object next() throws Exception {
            Object value = events.poll(20, TimeUnit.SECONDS);
            if (value == null || value instanceof Throwable || value instanceof Closed) throw new AssertionError("WebSocket did not deliver a message: " + value);
            return value;
        }
        @Override public void close() throws Exception {
            try { if (!socket.isOutputClosed()) socket.sendClose(1000, "").get(5, TimeUnit.SECONDS); }
            catch (ExecutionException disconnected) { if (!(disconnected.getCause() instanceof IOException)) throw disconnected; }
            finally { socket.abort(); }
        }
    }
    private record RawFrame(int opcode, byte[] data) {}
    @FunctionalInterface private interface RawAction { void run(RawWebSocket socket) throws Exception; }
    private static void protocolClose(int port, String name, int expected, RawAction action) throws Exception {
        try (var socket = new RawWebSocket(port)) { action.run(socket); equal(name, expected, closeCode(socket.read())); }
    }
    private static int closeCode(RawFrame frame) {
        if (frame.opcode != 8 || frame.data.length < 2) throw new AssertionError("Expected close frame");
        return Short.toUnsignedInt(ByteBuffer.wrap(frame.data).getShort());
    }
    private static final class RawWebSocket implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final OutputStream output;
        RawWebSocket(int port) throws IOException {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(8000);
            input = new DataInputStream(socket.getInputStream()); output = socket.getOutputStream();
            output.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String headers = readHeaders(input);
            if (!headers.startsWith("HTTP/1.1 101") || !headers.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")) throw new AssertionError("Invalid independent upgrade " + headers);
        }
        void send(int opcode, boolean fin, boolean masked, byte[] bytes) throws IOException {
            var frame = new ByteArrayOutputStream(); var data = new DataOutputStream(frame);
            data.writeByte(opcode | (fin ? 128 : 0));
            data.writeByte((masked ? 128 : 0) | (bytes.length < 126 ? bytes.length : bytes.length < 65536 ? 126 : 127));
            if (bytes.length >= 126 && bytes.length < 65536) data.writeShort(bytes.length);
            else if (bytes.length >= 65536) data.writeLong(bytes.length);
            byte[] mask = {17, 31, 47, 63};
            if (masked) data.write(mask);
            for (int i = 0; i < bytes.length; i++) data.writeByte(bytes[i] ^ (masked ? mask[i % 4] : 0));
            output.write(frame.toByteArray()); output.flush();
        }
        RawFrame read() throws IOException {
            int first = input.readUnsignedByte(), second = input.readUnsignedByte();
            if ((first & 128) == 0 || (second & 128) != 0) throw new AssertionError("Invalid server frame");
            long length = second & 127;
            if (length == 126) length = input.readUnsignedShort(); else if (length == 127) length = input.readLong();
            if (length < 0 || length > 1048576) throw new AssertionError("Unexpected frame length");
            byte[] data = input.readNBytes((int) length);
            if (data.length != length) throw new EOFException();
            return new RawFrame(first & 15, data);
        }
        @Override public void close() throws IOException { socket.close(); }
    }
    private static String readHeaders(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream(); int state = 0;
        while (output.size() < 32768) {
            int value = input.read(); if (value < 0) throw new EOFException();
            output.write(value);
            state = state == 0 && value == 13 ? 1 : state == 1 && value == 10 ? 2 : state == 2 && value == 13 ? 3 : state == 3 && value == 10 ? 4 : 0;
            if (state == 4) return output.toString(StandardCharsets.US_ASCII);
        }
        throw new IOException("Oversize headers");
    }
    private static final class Fixture implements AutoCloseable {
        final HttpsServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger(), chromeRequests = new AtomicInteger();
        final CountDownLatch workloadStarted = new CountDownLatch(8);
        final CountDownLatch cancelStarted = new CountDownLatch(1), cancelRelease = new CountDownLatch(1);
        final CountDownLatch multiBatchStarted = new CountDownLatch(2), multiBatchRelease = new CountDownLatch(1);
        final CountDownLatch multiHeldStarted = new CountDownLatch(2), multiWaitRelease = new CountDownLatch(1), multiRemoveRelease = new CountDownLatch(1);
        final CountDownLatch multiCloseStarted = new CountDownLatch(1), multiCloseRelease = new CountDownLatch(1);
        final AtomicInteger multiBatchActive = new AtomicInteger(), multiBatchPeak = new AtomicInteger();
        volatile CountDownLatch hold;
        final Path certificate;
        Fixture(Path artifacts) throws Exception {
            Path key = artifacts.resolve("fixture.p12");
            Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", windows() ? "keytool.exe" : "keytool").toString(),
                    "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12", "-keystore", key.toString(),
                    "-storepass", "local-test-fixture", "-noprompt").redirectErrorStream(true).redirectOutput(artifacts.resolve("keytool.log").toFile()).start();
            if (!keytool.waitFor(20, TimeUnit.SECONDS) || keytool.exitValue() != 0) throw new AssertionError("keytool failed");
            var store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(key)) { store.load(input, "local-test-fixture".toCharArray()); }
            var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, "local-test-fixture".toCharArray());
            var context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, null);
            certificate = artifacts.resolve("fixture-ca.pem");
            Files.writeString(certificate, "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[] {10}).encodeToString(store.getCertificate("fixture").getEncoded()) + "\n-----END CERTIFICATE-----\n");
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            server.setHttpsConfigurator(new HttpsConfigurator(context)); server.setExecutor(executor);
            server.createContext("/", exchange -> {
                boolean payload = exchange.getRequestURI().getPath().equals("/payload");
                String path = exchange.getRequestURI().getPath();
                boolean multiBatch = path.equals("/multi-batch");
                if (multiBatch) multiBatchPeak.accumulateAndGet(multiBatchActive.incrementAndGet(), Math::max);
                if (payload) maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    String ua = exchange.getRequestHeaders().getFirst("User-Agent");
                    if (ua != null && ua.contains("Chrome/136.")) chromeRequests.incrementAndGet();
                    if (payload && hold != null) { workloadStarted.countDown(); hold.await(10, TimeUnit.SECONDS); }
                    if (exchange.getRequestURI().getPath().equals("/cancel")) {
                        cancelStarted.countDown();
                        cancelRelease.await(20, TimeUnit.SECONDS);
                    }
                    if (multiBatch) { multiBatchStarted.countDown(); multiBatchRelease.await(15, TimeUnit.SECONDS); }
                    if (path.equals("/multi-wait") || path.equals("/multi-remove")) {
                        multiHeldStarted.countDown();
                        (path.equals("/multi-wait") ? multiWaitRelease : multiRemoveRelease).await(20, TimeUnit.SECONDS);
                    }
                    if (path.equals("/multi-close")) { multiCloseStarted.countDown(); multiCloseRelease.await(20, TimeUnit.SECONDS); }
                    Thread.sleep(exchange.getRequestURI().getPath().equals("/slow") ? 800 : 40);
                    byte[] body = REMOTE.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (IOException disconnected) { /* Expected for the timeout case. */ }
                finally {
                    if (payload) active.decrementAndGet();
                    if (multiBatch) multiBatchActive.decrementAndGet();
                    exchange.close();
                }
            });
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        @Override public void close() {
            if (hold != null) hold.countDown();
            cancelRelease.countDown();
            multiBatchRelease.countDown(); multiWaitRelease.countDown(); multiRemoveRelease.countDown(); multiCloseRelease.countDown();
            server.stop(0);
            executor.close();
        }
    }
    private record Hello(List<Integer> ciphers, List<Integer> extensions, boolean grease) {
        String fingerprint() { return ciphers + ":" + extensions.stream().sorted().toList(); }
        boolean chrome() {
            return grease && ciphers.equals(List.of(4865, 4866, 4867, 49195, 49199, 49196, 49200, 52393, 52392, 49171, 49172, 156, 157, 47, 53));
        }
    }
    private static final class TlsProxy implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 128, InetAddress.getLoopbackAddress());
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final List<Hello> hellos = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        TlsProxy(int upstream) throws IOException {
            executor.submit(() -> {
                while (!closed) {
                    try { Socket client = listener.accept(); executor.submit(() -> forward(client, upstream)); }
                    catch (IOException error) { if (!closed) throw new UncheckedIOException(error); }
                }
            });
        }
        int port() { return listener.getLocalPort(); }
        void forward(Socket client, int port) {
            try (client; var upstream = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(15000); upstream.setSoTimeout(15000);
                var input = new DataInputStream(client.getInputStream());
                var handshake = new ByteArrayOutputStream();
                while (handshake.size() < 4 || handshake.size() < 4 + ((handshake.toByteArray()[1] & 255) << 16 | (handshake.toByteArray()[2] & 255) << 8 | handshake.toByteArray()[3] & 255)) {
                    byte[] record = input.readNBytes(5);
                    if (record.length != 5 || record[0] != 22) throw new IOException("Expected TLS handshake");
                    int length = (record[3] & 255) << 8 | record[4] & 255;
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length != length || handshake.size() + length > 131072) throw new IOException("Invalid ClientHello");
                    handshake.write(bytes);
                    upstream.getOutputStream().write(record); upstream.getOutputStream().write(bytes); upstream.getOutputStream().flush();
                }
                hellos.add(parseHello(handshake.toByteArray()));
                Future<?> response = executor.submit(() -> {
                    try { upstream.getInputStream().transferTo(client.getOutputStream()); client.shutdownOutput(); }
                    catch (IOException ignored) {}
                });
                input.transferTo(upstream.getOutputStream()); upstream.shutdownOutput();
                response.get(15, TimeUnit.SECONDS);
            } catch (Exception disconnected) { /* Client certificate rejection and deadlines close either side. */ }
        }
        static Hello parseHello(byte[] bytes) throws IOException {
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readUnsignedByte() != 1) throw new IOException("Expected ClientHello");
            input.skipNBytes(3 + 2 + 32); input.skipNBytes(input.readUnsignedByte());
            int cipherBytes = input.readUnsignedShort();
            List<Integer> ciphers = new ArrayList<>(), extensions = new ArrayList<>();
            boolean grease = false;
            for (int i = 0; i < cipherBytes; i += 2) {
                int cipher = input.readUnsignedShort(); if (grease(cipher)) grease = true; else ciphers.add(cipher);
            }
            input.skipNBytes(input.readUnsignedByte());
            int remaining = input.readUnsignedShort();
            while (remaining > 0) {
                int type = input.readUnsignedShort(), length = input.readUnsignedShort();
                if (grease(type)) grease = true; else extensions.add(type);
                input.skipNBytes(length); remaining -= length + 4;
            }
            return new Hello(List.copyOf(ciphers), List.copyOf(extensions), grease);
        }
        static boolean grease(int value) { return (value & 0x0f0f) == 0x0a0a && (value >> 8) == (value & 255); }
        @Override public void close() throws IOException { closed = true; listener.close(); executor.shutdownNow(); }
    }
}
