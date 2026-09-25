package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static graalphp.runtime.Execution.*;

/** TrueAsync HTTP/1.1 and WebSocket subset, sharing the request scheduler and libuv loop. */
public final class NetworkApi {
    private NetworkApi() {}
    private static final String HEADER_NAME = "[!#$%&'*+.^_|~\\x60A-Za-z0-9-]+";
    public static final String SOURCE = """
        function net_server_start($server) {
            try {
                __net_listen($server);
                while ($server->isRunning()) {
                    $id = __net_accept($server);
                    if ($id !== null) {
                        $connection = __net_attach($server, $id);
                        if ($connection !== null) {
                            Async\\spawn(function() use ($server, $connection) { __net_dispatch($server, $connection); });
                        }
                    }
                }
            } finally {
                $server->stop();
                __net_drain($server);
            }
            return true;
        }
        function net_dispatch($server, $connection) {
            try {
                $request = __net_request($connection);
                if (__net_is_upgrade($request)) {
                    $handler = __net_handler($server, true);
                    if ($handler === null) throw exception('No WebSocket handler');
                    $ws = __net_upgrade($server, $connection, $request);
                    $handler($ws, $request);
                    $ws->close();
                } else {
                    $response = __net_response();
                    $handler = __net_handler($server, false);
                    if ($handler !== null) $handler($request, $response);
                    __net_send_response($connection, $response);
                }
            } catch (Throwable $error) {
                __net_failed($server, $connection, $error);
            } finally {
                __net_detach($server, $connection);
            }
        }
        """;
    public static final class Config implements TruffleObject {
        String host;
        int port = 8080, backlog = 128, readTimeout = 10000, writeTimeout = 10000, shutdownTimeout = 5000, maxMessage = 1048576, maxConnections = 256;
        boolean locked;
    }
    public static final class Server implements TruffleObject, AutoCloseable {
        Config config;
        Object httpHandler, wsHandler;
        LibuvReactor reactor;
        Request owner;
        long listener;
        boolean running, started;
        long accepted, completed, failed;
        final Set<TcpConnection> connections = new HashSet<>();
        final Scheduler.Future drained = new Scheduler.Future();
        @Override public void close() {
            if (reactor != null) connections.forEach(TcpConnection::close);
            PhpValues.drop(httpHandler); httpHandler = null;
            PhpValues.drop(wsHandler); wsHandler = null;
        }
        void stop() {
            if (!running) return;
            running = false;
            reactor.closeSocket(listener);
            if (connections.isEmpty()) drained.completion.complete(null);
            else {
                var deadline = reactor.timer(config.shutdownTimeout);
                drained.completion.whenComplete((value, failure) -> deadline.cancelAction.run());
                deadline.completion.whenComplete((value, failure) -> {
                    if (failure == null) owner.scheduler.enqueue(() -> connections.forEach(TcpConnection::close));
                });
            }
        }
    }
    public record HttpRequest(String method, String uri, Map<String, String> headers, Object body) implements TruffleObject {}
    public static final class Response implements TruffleObject {
        int status = 200;
        Object body = "";
        final Map<String, String> headers = new LinkedHashMap<>();
    }
    public static Object allocate(Activation caller, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "trueasync\\httpserverconfig" -> new Config();
            case "trueasync\\httpserver" -> {
                var server = new Server(); server.owner = caller.request;
                caller.request.resources.add(server); yield server;
            }
            default -> AsyncApi.UNHANDLED;
        };
    }
    public static String className(Object value) {
        if (value instanceof Config) return "TrueAsync\\HttpServerConfig";
        if (value instanceof Server) return "TrueAsync\\HttpServer";
        if (value instanceof HttpRequest) return "TrueAsync\\HttpRequest";
        if (value instanceof Response) return "TrueAsync\\HttpResponse";
        if (value instanceof WebSocketConnection) return "TrueAsync\\WebSocket";
        if (value instanceof WebSocketConnection.Message) return "TrueAsync\\WebSocketMessage";
        return null;
    }
    public static Object staticMethod(String type, String name) {
        if (type.equalsIgnoreCase("TrueAsync\\HttpServer") && (name.equalsIgnoreCase("isHttp2") || name.equalsIgnoreCase("isHttp3"))) return false;
        return AsyncApi.UNHANDLED;
    }
    private static Argument[] args(String name, Argument[] arguments, int required, String... names) {
        return CallArguments.builtin(name, arguments, List.of(names), required);
    }
    private static long integer(Object value) { return Operations.number(value).longValue(); }
    private static String string(Object value) { return Operations.string(value); }
    private static int positive(Object value, int max) {
        long number = integer(value);
        if (number < 1 || number > max) throw new PhpError("ValueError", "Value outside supported range 1.." + max);
        return (int) number;
    }
    public static Object method(Activation caller, Object value, String name, Argument[] arguments, IndirectCallNode call) {
        name = name.toLowerCase(Locale.ROOT);
        if (value instanceof Config config) {
            if (name.equals("__construct")) {
                var ordered = CallArguments.builtin(name, arguments, List.of("host", "port"), 0, null, 8080L);
                if (ordered[0].value() != null) config.host = string(ordered[0].value());
                config.port = port(ordered[1].value()); return null;
            }
            if (name.equals("getlisteners")) {
                args(name, arguments, 0);
                var listeners = caller.locals.variable(caller.locals.emptyArray());
                if (config.host != null) {
                    var item = caller.locals.variable(caller.locals.emptyArray());
                    item.element("host").set(config.host); item.element("port").set((long) config.port); item.element("tls").set(false);
                    listeners.append().set(item.read());
                }
                return caller.track(PhpValues.own(listeners.read()));
            }
            if (config.locked) throw new PhpError("TrueAsync\\HttpServerRuntimeException", "Server configuration is already in use");
            if (name.equals("addlistener") || name.equals("addhttp1listener")) {
                var ordered = CallArguments.builtin(name, arguments, List.of("host", "port", "tls"), 2, false);
                if (config.host != null) throw new PhpError("This server currently supports one listener");
                if (Operations.truth(ordered[2].value())) throw new PhpError("TLS server listeners are not implemented");
                config.host = string(ordered[0].value()); config.port = port(ordered[1].value()); return config;
            }
            var ordered = args(name, arguments, 1, switch (name) {
                case "setbacklog" -> "backlog"; case "setmaxconnections" -> "maxConnections";
                case "setwsmaxmessagesize" -> "bytes"; case "setworkers" -> "workers"; default -> "timeout";
            });
            if (name.equals("setreadtimeout") || name.equals("setwritetimeout") || name.equals("setshutdowntimeout")) {
                long seconds = integer(ordered[0].value());
                if (seconds < 0 || seconds > 86400) throw new PhpError("Timeout must be 0..86400 seconds");
                int milliseconds = Math.toIntExact(seconds * 1000);
                if (name.equals("setreadtimeout")) config.readTimeout = milliseconds;
                else if (name.equals("setwritetimeout")) config.writeTimeout = milliseconds;
                else config.shutdownTimeout = milliseconds;
                return config;
            }
            int number = positive(ordered[0].value(), 16 * 1024 * 1024);
            switch (name) {
                case "setbacklog" -> config.backlog = number;
                case "setmaxconnections" -> config.maxConnections = number;
                case "setwsmaxmessagesize" -> config.maxMessage = number;
                case "setworkers" -> { if (number != 1) throw new PhpError("Shared-heap server workers are not implemented"); }
                default -> throw new PhpError("Unimplemented HttpServerConfig::" + name);
            }
            return config;
        }
        if (value instanceof Server server) {
            if (name.equals("__construct")) {
                var ordered = args(name, arguments, 1, "config");
                if (!(PhpValues.unwrap(ordered[0].value()) instanceof Config config)) throw new PhpError("TypeError", "Expected HttpServerConfig");
                server.config = config; return null;
            }
            if (name.equals("addhttphandler") || name.equals("addwebsockethandler")) {
                if (server.started) throw new PhpError("Server has already started");
                Object handler = PhpValues.unwrap(args(name, arguments, 1, "handler")[0].value());
                ObjectModel.callable(caller, handler);
                if (name.equals("addhttphandler")) { PhpValues.drop(server.httpHandler); server.httpHandler = PhpValues.own(handler); }
                else { PhpValues.drop(server.wsHandler); server.wsHandler = PhpValues.own(handler); }
                return server;
            }
            args(name, arguments, 0);
            return switch (name) {
                case "start" -> Operations.invokeFunction(caller, caller.request.context.asyncFunction("net_server_start"), new Argument[] {new Argument(server, null)}, call);
                case "stop" -> { server.stop(); yield true; }
                case "isrunning" -> server.running;
                case "getconfig" -> server.config;
                case "gettelemetry" -> array(caller, Map.of("accepted", server.accepted, "completed", server.completed, "failed", server.failed, "active", (long) server.connections.size()));
                default -> throw new PhpError("Unimplemented HttpServer::" + name);
            };
        }
        if (value instanceof HttpRequest request) {
            if (name.equals("getheader") || name.equals("getheaderline") || name.equals("hasheader")) {
                String header = string(args(name, arguments, 1, "name")[0].value()).toLowerCase(Locale.ROOT);
                return name.equals("hasheader") ? request.headers.containsKey(header) : request.headers.getOrDefault(header, name.equals("getheaderline") ? "" : null);
            }
            args(name, arguments, 0);
            return switch (name) {
                case "getmethod" -> request.method;
                case "geturi" -> request.uri;
                case "getpath" -> request.uri.split("\\?", 2)[0];
                case "gethttpversion" -> "1.1";
                case "getbody" -> request.body;
                case "getheaders" -> array(caller, request.headers);
                case "iskeepalive" -> false;
                case "awaitbody" -> request;
                default -> throw new PhpError("Unimplemented HttpRequest::" + name);
            };
        }
        if (value instanceof Response response) {
            switch (name) {
                case "setstatuscode" -> response.status = positive(args(name, arguments, 1, "code")[0].value(), 599);
                case "setbody" -> response.body = PhpValues.unwrap(args(name, arguments, 1, "body")[0].value());
                case "appendbody" -> response.body = PhpString.concat(response.body, args(name, arguments, 1, "data")[0].value());
                case "setheader" -> {
                    var ordered = args(name, arguments, 2, "name", "value");
                    String header = string(ordered[0].value()), content = string(ordered[1].value());
                    if (!header.matches(HEADER_NAME) || content.contains("\r") || content.contains("\n")) throw new PhpError("Invalid HTTP header");
                    if (header.equalsIgnoreCase("content-length") || header.equalsIgnoreCase("transfer-encoding") || header.equalsIgnoreCase("connection")) throw new PhpError("Framing headers are managed by the server");
                    response.headers.put(header, content);
                }
                case "getbody" -> { args(name, arguments, 0); return response.body; }
                case "getstatuscode" -> { args(name, arguments, 0); return (long) response.status; }
                default -> throw new PhpError("Unimplemented HttpResponse::" + name);
            }
            return response;
        }
        if (value instanceof WebSocketConnection socket) {
            if (name.equals("isclosed")) { args(name, arguments, 0); return socket.isClosed(); }
            if (name.equals("getsubprotocol")) { args(name, arguments, 0); return null; }
            if (caller.synchronousCallback) throw new PhpError("Cannot suspend a native callback for WebSocket I/O");
            CompletableFuture<?> result;
            switch (name) {
                case "recv" -> { args(name, arguments, 0); result = socket.receive(); }
                case "send", "sendbinary" -> result = socket.send(args(name, arguments, 1, name.equals("send") ? "text" : "data")[0].value(), name.equals("sendbinary"));
                case "ping" -> result = socket.ping(CallArguments.builtin(name, arguments, List.of("payload"), 0, "")[0].value());
                case "close" -> {
                    var ordered = CallArguments.builtin(name, arguments, List.of("code", "reason"), 0, 1000L, "");
                    result = socket.close((int) integer(ordered[0].value()), string(ordered[1].value()));
                }
                default -> throw new PhpError("Unimplemented WebSocket::" + name);
            }
            return await(caller, result, socket.transport::close);
        }
        return AsyncApi.UNHANDLED;
    }
    private static int port(Object value) {
        long number = integer(value);
        if (number < 0 || number > 65535) throw new PhpError("Invalid port");
        return (int) number;
    }
    public static Object array(Activation caller, Map<String, ?> values) {
        var array = caller.locals.variable(caller.locals.emptyArray());
        values.forEach((key, value) -> array.element(key).set(value));
        return caller.track(PhpValues.own(array.read()));
    }
    public static Object await(Activation caller, CompletableFuture<?> operation, Runnable cancel) {
        if (caller.synchronousCallback) throw new PhpError("Cannot perform asynchronous I/O in a synchronous native callback");
        return Scheduler.awaitValue(caller, TcpConnection.awaitable(operation, cancel), null);
    }
    public static Object function(Activation caller, String name, Object[] values, IndirectCallNode call) {
        if (!name.startsWith("__net_")) return AsyncApi.UNHANDLED;
        if (caller.synchronousCallback) throw new PhpError("Cannot suspend a native callback for network I/O");
        Server server = values.length > 0 && values[0] instanceof Server item ? item : null;
        switch (name) {
            case "__net_dispatch":
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("net_dispatch"), new Argument[] {new Argument(server, null), new Argument(values[1], null)}, call);
            case "__net_listen": {
                if (server.started || server.config == null || server.config.host == null) throw new PhpError("Server requires an unused listener");
                server.started = true; server.config.locked = true;
                server.reactor = caller.request.libuv(caller);
                var future = server.reactor.submit("gp_tcp_listen", ",STRING,SINT32,SINT32", server.config.host, server.config.port, server.config.backlog);
                var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                var listening = future.completion.thenCompose(id -> {
                    server.listener = (Long) id;
                    return server.reactor.submit("gp_tcp_port", ",SINT64", server.listener).completion;
                });
                var result = new CompletableFuture<Object>();
                listening.whenComplete((port, error) -> caller.request.scheduler.enqueue(() -> {
                    if (error != null) result.completeExceptionally(error);
                    else if (cancelled.get()) {
                        server.reactor.closeSocket(server.listener);
                        result.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Server startup cancelled"));
                    }
                    else { server.config.port = ((Number) port).intValue(); server.running = true; result.complete(null); }
                }));
                return await(caller, result, () -> {
                    cancelled.set(true);
                    if (server.listener != 0) server.reactor.closeSocket(server.listener);
                });
            }
            case "__net_accept":
                if (!server.running) return null;
                return Scheduler.awaitValue(caller, server.reactor.submit("gp_tcp_accept", ",SINT64", server.listener), null);
            case "__net_attach": {
                var connection = new TcpConnection(server.reactor, (Long) values[1], server.config.readTimeout, server.config.writeTimeout);
                if (!server.running || server.connections.size() >= server.config.maxConnections) { connection.close(); return null; }
                server.accepted++; server.connections.add(connection); return connection;
            }
            case "__net_detach": {
                var connection = (TcpConnection) values[1];
                connection.close(); server.connections.remove(connection); server.completed++;
                if (!server.running && server.connections.isEmpty()) server.drained.completion.complete(null);
                return null;
            }
            case "__net_drain":
                if (!server.started || server.connections.isEmpty()) return null;
                return Scheduler.awaitValue(caller, server.drained, null);
            case "__net_failed":
                server.failed++; ((TcpConnection) values[1]).close();
                try {
                    caller.request.context.environment.err().write(("Request failed: " + ((PhpError) values[2]).getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException error) { throw new PhpError("Server error log failed: " + error.getMessage()); }
                return null;
            case "__net_handler": return caller.track(PhpValues.own(PhpValues.unwrap(Operations.truth(values[1]) ? server.wsHandler : server.httpHandler)));
            case "__net_request": {
                var connection = (TcpConnection) values[0];
                return await(caller, request(connection), connection::close);
            }
            case "__net_is_upgrade": return ((HttpRequest) values[0]).headers.getOrDefault("upgrade", "").equalsIgnoreCase("websocket");
            case "__net_upgrade": {
                var connection = (TcpConnection) values[1];
                var request = (HttpRequest) values[2];
                return await(caller, upgrade(connection, request, server.config.maxMessage), connection::close);
            }
            case "__net_response": return new Response();
            case "__net_send_response": {
                var connection = (TcpConnection) values[0];
                return await(caller, connection.write(response((Response) values[1])), connection::close);
            }
            default: throw new PhpError("Unknown network operation");
        }
    }
    private static CompletableFuture<HttpRequest> request(TcpConnection connection) {
        return connection.readHeaders().thenCompose(bytes -> {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            String[] lines = text.substring(0, text.length() - 4).split("\r\n");
            String[] start = lines[0].split(" ", -1);
            if (start.length != 3 || !start[2].equals("HTTP/1.1") || !start[1].startsWith("/") || !start[0].matches("[A-Z]+")) throw new PhpError("Invalid HTTP request line");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1 || !lines[i].substring(0, colon).matches(HEADER_NAME)) throw new PhpError("Invalid HTTP header");
                String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.putIfAbsent(key, lines[i].substring(colon + 1).strip()) != null) throw new PhpError("Duplicate HTTP header");
            }
            if (!headers.containsKey("host") || headers.containsKey("transfer-encoding")) throw new PhpError("Host required; chunked request bodies are not supported");
            int length;
            try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
            catch (NumberFormatException error) { throw new PhpError("Invalid Content-Length"); }
            if (length < 0 || length > 1048576) throw new PhpError("HTTP body exceeds limit");
            return connection.readExact(length).thenApply(body -> new HttpRequest(start[0], start[1], Map.copyOf(headers), PhpString.fromBytes(body)));
        });
    }
    private static CompletableFuture<WebSocketConnection> upgrade(TcpConnection connection, HttpRequest request, int maxMessage) {
        String key = request.headers.getOrDefault("sec-websocket-key", "");
        boolean validKey;
        try { validKey = Base64.getDecoder().decode(key).length == 16; }
        catch (IllegalArgumentException error) { validKey = false; }
        if (!request.method.equals("GET") || !request.headers.getOrDefault("upgrade", "").equalsIgnoreCase("websocket")
                || !Arrays.stream(request.headers.getOrDefault("connection", "").split(",")).anyMatch(item -> item.strip().equalsIgnoreCase("upgrade"))
                || !request.headers.getOrDefault("sec-websocket-version", "").equals("13") || !validKey || PhpString.bytes(request.body).length != 0) {
            return connection.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
                    .thenCompose(ignored -> CompletableFuture.failedFuture(new PhpError("Invalid WebSocket upgrade")));
        }
        String accept;
        try { accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        byte[] response = ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        return connection.write(response).thenApply(ignored -> new WebSocketConnection(connection, maxMessage));
    }
    private static byte[] response(Response response) {
        if (response.status < 200) throw new PhpError("Only final HTTP responses are supported");
        byte[] body = PhpString.bytes(response.body);
        StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(response.status).append(" Response\r\nConnection: close\r\nContent-Length: ").append(body.length).append("\r\n");
        response.headers.forEach((name, value) -> headers.append(name).append(": ").append(value).append("\r\n"));
        headers.append("\r\n");
        byte[] head = headers.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, bytes, head.length, body.length);
        return bytes;
    }
}
