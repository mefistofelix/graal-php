package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** RFC 6455 server framing. Transport reads and writes never execute guest callbacks. */
public final class WebSocketConnection implements TruffleObject {
    public record Message(Object data, boolean binary) implements TruffleObject {}
    public final TcpConnection transport;
    private final int maxMessage;
    private final AtomicBoolean receiving = new AtomicBoolean();
    private volatile boolean closed;
    private ByteArrayOutputStream fragments;
    private int fragmentOpcode;
    public WebSocketConnection(TcpConnection transport, int maxMessage) { this.transport = transport; this.maxMessage = maxMessage; }
    public boolean isClosed() { return closed; }
    public CompletableFuture<Object> send(Object data, boolean binary) {
        if (closed) return CompletableFuture.failedFuture(error("WebSocketClosedException", "Connection closed"));
        byte[] bytes = PhpString.bytes(data);
        if (!binary) PhpString.utf8(bytes);
        if (bytes.length > maxMessage) return CompletableFuture.failedFuture(error("WebSocketBackpressureException", "Message exceeds configured limit"));
        return frame(binary ? 2 : 1, bytes);
    }
    public CompletableFuture<Object> ping(Object data) {
        byte[] bytes = PhpString.bytes(data);
        if (bytes.length > 125) throw new PhpError("ValueError", "Ping payload exceeds 125 bytes");
        if (closed) throw error("WebSocketClosedException", "Connection closed");
        return frame(9, bytes);
    }
    public CompletableFuture<Object> close(int code, String reason) {
        if (!validCloseCode(code)) throw new PhpError("ValueError", "Invalid WebSocket close code");
        byte[] message = PhpString.bytes(reason);
        if (message.length > 123) throw new PhpError("ValueError", "Close reason exceeds 123 bytes");
        if (closed) return CompletableFuture.completedFuture(null);
        closed = true;
        byte[] bytes = ByteBuffer.allocate(message.length + 2).putShort((short) code).put(message).array();
        return frame(8, bytes).whenComplete((value, failure) -> transport.close());
    }
    private CompletableFuture<Object> frame(int opcode, byte[] bytes) {
        int extended = bytes.length < 126 ? 0 : bytes.length <= 65535 ? 2 : 8;
        var frame = ByteBuffer.allocate(2 + extended + bytes.length);
        frame.put((byte) (0x80 | opcode));
        frame.put((byte) (extended == 0 ? bytes.length : extended == 2 ? 126 : 127));
        if (extended == 2) frame.putShort((short) bytes.length);
        if (extended == 8) frame.putLong(bytes.length);
        frame.put(bytes);
        return transport.write(frame.array());
    }
    public CompletableFuture<Object> receive() {
        if (!receiving.compareAndSet(false, true)) throw error("WebSocketConcurrentReadException", "Only one recv may be pending");
        var result = new CompletableFuture<Object>();
        var released = result.whenComplete((value, failure) -> receiving.set(false));
        if (closed) result.complete(null);
        else transport.dispatch(() -> nextFrame(result));
        return released;
    }
    private void nextFrame(CompletableFuture<Object> result) {
        transport.readExact(2).thenCompose(header -> {
            int flags = header[0] & 255, size = header[1] & 127;
            int opcode = flags & 15;
            if ((flags & 112) != 0 || (header[1] & 128) == 0 || !(opcode == 0 || opcode == 1 || opcode == 2 || opcode == 8 || opcode == 9 || opcode == 10)) {
                return CompletableFuture.failedFuture(new ProtocolError(1002, "Invalid frame header or missing client mask"));
            }
            boolean control = opcode >= 8;
            if (control && ((flags & 128) == 0 || size > 125)) return CompletableFuture.failedFuture(new ProtocolError(1002, "Invalid control frame"));
            return transport.readExact(size < 126 ? 0 : size == 126 ? 2 : 8).thenCompose(extension -> {
                long length = size < 126 ? size : size == 126 ? Short.toUnsignedInt(ByteBuffer.wrap(extension).getShort()) : ByteBuffer.wrap(extension).getLong();
                if (length < 0 || size == 126 && length < 126 || size == 127 && length <= 65535) return CompletableFuture.failedFuture(new ProtocolError(1002, "Non-canonical frame length"));
                if (length > maxMessage) return CompletableFuture.failedFuture(new ProtocolError(1009, "Frame too large"));
                return transport.readExact((int) length + 4).thenApply(payload -> {
                    byte[] data = new byte[(int) length];
                    for (int i = 0; i < data.length; i++) data[i] = (byte) (payload[i + 4] ^ payload[i % 4]);
                    return new Frame(opcode, (flags & 128) != 0, data);
                });
            });
        }).whenComplete((frame, failure) -> transport.dispatch(() -> {
            if (failure != null) { fail(result, TcpConnection.cause(failure)); return; }
            try { consume(result, frame); } catch (RuntimeException error) { fail(result, error); }
        }));
    }
    private void consume(CompletableFuture<Object> result, Frame incoming) {
        byte[] data = incoming.data;
        if (incoming.opcode == 8) {
            if (data.length == 1) throw new ProtocolError(1002, "Invalid close frame");
            int code = data.length == 0 ? 1005 : Short.toUnsignedInt(ByteBuffer.wrap(data).getShort());
            if (code != 1005 && !validCloseCode(code)) throw new ProtocolError(1002, "Invalid close code");
            if (data.length > 2) validateText(java.util.Arrays.copyOfRange(data, 2, data.length));
            closed = true;
            frame(8, data).whenComplete((ignored, failure) -> {
                transport.close();
                if (code == 1000 || code == 1001 || code == 1005) result.complete(null);
                else result.completeExceptionally(error("WebSocketClosedException", "Peer closed with code " + code));
            });
            return;
        }
        if (incoming.opcode == 9) {
            frame(10, data).whenComplete((ignored, failure) -> transport.dispatch(() -> {
                if (failure != null) fail(result, failure); else nextFrame(result);
            }));
            return;
        }
        if (incoming.opcode == 10) { nextFrame(result); return; }
        if (incoming.opcode == 0 && fragments == null || incoming.opcode != 0 && fragments != null) throw new ProtocolError(1002, "Invalid continuation sequence");
        int opcode = incoming.opcode;
        if (fragments != null || !incoming.fin) {
            if (fragments == null) { fragments = new ByteArrayOutputStream(); fragmentOpcode = opcode; }
            if (data.length > maxMessage - fragments.size()) throw new ProtocolError(1009, "Message too large");
            fragments.writeBytes(data);
            if (!incoming.fin) { nextFrame(result); return; }
            data = fragments.toByteArray();
            fragments = null;
            opcode = fragmentOpcode;
        }
        Object value = opcode == 1 ? validateText(data) : PhpString.fromBytes(data);
        result.complete(new Message(value, opcode == 2));
    }
    private static String validateText(byte[] data) {
        try { return PhpString.utf8(data); }
        catch (PhpError error) { throw new ProtocolError(1007, "Invalid UTF-8 payload"); }
    }
    private void fail(CompletableFuture<Object> result, Throwable failure) {
        failure = TcpConnection.cause(failure);
        if (failure instanceof TcpConnection.EndOfStream) {
            closed = true; transport.close(); result.complete(null); return;
        }
        if (failure instanceof ProtocolError protocol) {
            close(protocol.code, protocol.getMessage()).whenComplete((ignored, writeError) ->
                    result.completeExceptionally(error("WebSocketClosedException", protocol.getMessage() + " (" + protocol.code + ")")));
        } else {
            closed = true; transport.close();
            result.completeExceptionally(failure instanceof PhpError ? failure : error("WebSocketClosedException", failure.getMessage()));
        }
    }
    private static boolean validCloseCode(int code) { return code >= 3000 && code <= 4999 || code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006; }
    private static PhpError error(String type, String message) { return new PhpError("TrueAsync\\" + type, message); }
    private record Frame(int opcode, boolean fin, byte[] data) {}
    private static final class ProtocolError extends RuntimeException {
        final int code;
        ProtocolError(int code, String message) { super(message); this.code = code; }
    }
}
