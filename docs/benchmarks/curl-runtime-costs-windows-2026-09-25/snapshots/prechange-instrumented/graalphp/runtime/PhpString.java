package graalphp.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** UTF-8 text keeps its String fast path; arbitrary bytes remain lossless at native boundaries. */
public final class PhpString {
    private final byte[] bytes;
    private PhpString(byte[] bytes) { this.bytes = bytes.clone(); }
    public static Object fromBytes(byte[] bytes) {
DispatchCosts.enter(); try {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // UTF-8 has a unique byte encoding. Round-trip validation preserves arbitrary
        // PHP bytes without a decoder/char buffer or an exception for binary data.
        return Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)) ? text : new PhpString(bytes);
    } finally { DispatchCosts.leave(6); }
}
    public static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new PhpError("ValueError", "Invalid UTF-8 text"); }
    }
    public static byte[] bytes(Object value) {
        value = PhpValues.unwrap(value);
        if (value instanceof PhpString string) return string.bytes.clone();
        return Operations.string(value).getBytes(StandardCharsets.UTF_8);
    }
    public static Object concat(Object first, Object second) {
        byte[] a = bytes(first), b = bytes(second);
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return fromBytes(result);
    }
    public int length() { return bytes.length; }
    @Override public boolean equals(Object other) { return other instanceof PhpString string && Arrays.equals(bytes, string.bytes); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
}
