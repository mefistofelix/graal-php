package graalphp;

import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/** PHP is only a cURL client. Independent HTTP/TLS peers provide deterministic barriers. */
public final class CurlIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path output = Files.createTempDirectory(root.resolve("build"), "curl-test-");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        try (Fixture fixture = new Fixture(output, windows)) {
            List<String> command = new ArrayList<>();
            boolean oracle = args.length > 0 && args[0].equals("trueasync");
            if (oracle) {
                command.add(root.resolve("tools/trueasync-0.10.0/php" + (windows ? ".exe" : "")).toString());
                command.addAll(List.of("-n", "-d", "extension_dir=" + root.resolve("tools/trueasync-0.10.0/ext"), "-d", "extension=curl"));
            } else if (args.length > 0 && !args[0].isEmpty()) command.add(Path.of(args[0]).toAbsolutePath().toString());
            else command.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-cp", "build/classes" + File.pathSeparator + "build/deps/25.4.4.1.1/*", "graalphp.Main"));
            command.add("tests/php/curl-integration.php");
            var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("php.log").toFile());
            builder.environment().putAll(Map.of("CURL_TEST_HTTP", "http://127.0.0.1:" + fixture.http.getAddress().getPort(),
                    "CURL_TEST_HTTPS", "https://localhost:" + fixture.https.getAddress().getPort(),
                    "CURL_TEST_CA", fixture.certificate.toString(), "CURL_TEST_PROVIDERS", oracle ? "0" : "1",
                    "GRAALPHP_CURL_PROVIDER", "standard", "GRAALPHP_REACTOR", "libuv", "GRAALPHP_TIMEOUT_MS", "90000"));
            Process process = builder.start();
            try {
                if (!process.waitFor(100, TimeUnit.SECONDS)) throw new AssertionError("cURL test timed out");
                String log = Files.readString(output.resolve("php.log"));
                System.out.print(log);
                if (process.exitValue() != 0 || !log.contains("PASS curl assertions=")) throw new AssertionError("PHP cURL suite failed: " + output);
                if (!log.contains("8.22.0")) throw new AssertionError("Expected stock curl 8.22.0: " + log);
                if (fixture.peak.get() < 16) throw new AssertionError("cURL requests did not reach the peer concurrently: " + fixture.peak);
                System.out.println("PASS independent peer: peak held requests=" + fixture.peak + "; artifacts=" + output);
            } finally {
                if (process.isAlive()) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer http;
        final HttpsServer https;
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Path certificate;
        final ConcurrentHashMap<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicInteger> started = new ConcurrentHashMap<>();
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        Fixture(Path output, boolean windows) throws Exception {
            Path key = output.resolve("fixture.p12");
            Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool").toString(),
                    "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12", "-keystore", key.toString(),
                    "-storepass", "local-test-fixture", "-noprompt").redirectErrorStream(true).redirectOutput(output.resolve("keytool.log").toFile()).start();
            if (!keytool.waitFor(20, TimeUnit.SECONDS) || keytool.exitValue() != 0) throw new AssertionError("keytool failed");
            var store = KeyStore.getInstance("PKCS12");
            try (var stream = Files.newInputStream(key)) { store.load(stream, "local-test-fixture".toCharArray()); }
            var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, "local-test-fixture".toCharArray());
            var context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, null);
            certificate = output.resolve("fixture-ca.pem");
            Files.writeString(certificate, "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[] {10}).encodeToString(store.getCertificate("fixture").getEncoded()) + "\n-----END CERTIFICATE-----\n");
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
            https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
            https.setHttpsConfigurator(new HttpsConfigurator(context));
            for (HttpServer server : List.of(http, https)) { server.setExecutor(workers); server.createContext("/", this::serve); server.start(); }
        }
        private void serve(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String id = Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), "default");
            byte[] body = "curl-body".getBytes(StandardCharsets.UTF_8);
            int status = 200;
            try {
                switch (path) {
                    case "/hold" -> {
                        var gate = gates.computeIfAbsent(id, ignored -> new CountDownLatch(1));
                        started.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                        int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
                        try { if (!gate.await(60, TimeUnit.SECONDS)) throw new IOException("Unreleased cURL barrier"); }
                        finally { active.decrementAndGet(); }
                    }
                    case "/state" -> body = Integer.toString(started.computeIfAbsent(id, ignored -> new AtomicInteger()).get()).getBytes(StandardCharsets.US_ASCII);
                    case "/release" -> gates.computeIfAbsent(id, ignored -> new CountDownLatch(1)).countDown();
                    case "/peer" -> body = exchange.getRemoteAddress().toString().getBytes(StandardCharsets.UTF_8);
                    case "/headers" -> body = Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst("X-Test"), "absent").getBytes(StandardCharsets.UTF_8);
                    case "/post" -> body = exchange.getRequestBody().readAllBytes();
                    case "/bytes" -> body = HexFormat.of().parseHex("007fc0afeda080ffe282acf09f9880");
                    case "/unicode" -> body = HexFormat.of().parseHex("e282acf09f988000");
                    case "/empty" -> body = new byte[0];
                    case "/large" -> { body = new byte[1048576]; Arrays.fill(body, (byte) 'x'); }
                    case "/redirect" -> { status = 302; exchange.getResponseHeaders().set("Location", "/body"); }
                    case "/missing" -> status = 404;
                    case "/delay" -> Thread.sleep(250);
                    case "/gzip" -> {
                        var compressed = new ByteArrayOutputStream();
                        try (var gzip = new GZIPOutputStream(compressed)) { gzip.write(body); }
                        body = compressed.toByteArray(); exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }
        @Override public void close() {
            gates.values().forEach(CountDownLatch::countDown);
            http.stop(0); https.stop(0); workers.close();
        }
    }
}
