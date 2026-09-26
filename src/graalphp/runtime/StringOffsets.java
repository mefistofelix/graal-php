package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.Arrays;

/** PHP string offsets address bytes, not Java UTF-16 characters. */
public final class StringOffsets {
    private StringOffsets() {}
    public enum ReadMode { NORMAL, COALESCE, PROBE }
    public record WriteResult(Object string, Object result) {}

    public static boolean isString(Object value) { return value instanceof String || value instanceof PhpString; }

    @TruffleBoundary
    public static Object read(Object value, Object key, ReadMode mode, Diagnostics.Origin origin) {
        Long offset = offset(key, mode, origin);
        if (offset == null) return null;
        byte[] bytes = PhpString.bytes(value);
        long position = offset < 0 ? bytes.length + offset : offset;
        if (position < 0 || position >= bytes.length) {
            if (mode != ReadMode.NORMAL) return null;
            warning(origin, "Uninitialized string offset " + offset);
            return "";
        }
        return PhpString.fromBytes(new byte[] {bytes[(int) position]});
    }

    @TruffleBoundary
    public static WriteResult write(Object value, Object key, Object replacement, Diagnostics.Origin origin) {
        long offset = offset(key, ReadMode.NORMAL, origin);
        byte[] bytes = PhpString.bytes(value);
        long position = offset < 0 ? bytes.length + offset : offset;
        if (position < 0) {
            warning(origin, "Illegal string offset " + offset);
            return new WriteResult(null, null);
        }
        if (position >= Integer.MAX_VALUE - 8L) throw new PhpError("Error", "String offset exceeds the maximum supported string length");
        byte[] assigned;
        if (replacement instanceof PhpValues.PhpArray) {
            warning(origin, "Array to string conversion");
            assigned = PhpString.bytes("Array");
        } else {
            try { assigned = PhpString.bytes(replacement); }
            catch (PhpError error) {
                throw new PhpError("Error", "Object of class " + EnumApi.valueType(replacement) + " could not be converted to string");
            }
        }
        if (assigned.length == 0) throw new PhpError("Error", "Cannot assign an empty string to a string offset");
        if (assigned.length > 1) warning(origin, "Only the first byte will be assigned to the string offset");
        int length = Math.max(bytes.length, (int) position + 1);
        byte[] result = Arrays.copyOf(bytes, length);
        if (length > bytes.length) Arrays.fill(result, bytes.length, length, (byte) ' ');
        result[(int) position] = assigned[0];
        return new WriteResult(PhpString.fromBytes(result), PhpString.fromBytes(new byte[] {assigned[0]}));
    }

    private static Long offset(Object value, ReadMode mode, Diagnostics.Origin origin) {
        if (value instanceof Long offset) return offset;
        if (value instanceof String text) {
            String numeric = text.strip();
            if (numeric.matches("[+-]?[0-9]+")) {
                try { return Long.parseLong(numeric); }
                catch (NumberFormatException overflow) {
                    if (mode != ReadMode.NORMAL) return null;
                    throw new PhpError("TypeError", "Cannot access offset of type string on string");
                }
            }
            if (mode != ReadMode.NORMAL) return null;
            var prefix = java.util.regex.Pattern.compile("^([+-]?[0-9]+)(.*)$").matcher(numeric);
            if (prefix.matches() && !prefix.group(2).matches("(?:\\.[0-9]*|[eE][+-]?[0-9]+).*")) {
                try {
                    long index = Long.parseLong(prefix.group(1));
                    warning(origin, "Illegal string offset \"" + text + "\"");
                    return index;
                } catch (NumberFormatException overflow) { /* A numeric offset must fit a PHP integer. */ }
            }
        } else if (value == null || value instanceof Boolean || value instanceof Double) {
            long offset = value == null ? 0 : value instanceof Boolean flag ? (flag ? 1 : 0) : ((Double) value).longValue();
            if (mode == ReadMode.NORMAL) warning(origin, "String offset cast occurred");
            else if (mode == ReadMode.PROBE && value instanceof Double number && number != (double) offset && origin != null)
                origin.deprecated("Implicit conversion from float " + Operations.string(number) + " to int loses precision");
            return offset;
        } else if (mode == ReadMode.PROBE) return null;
        throw new PhpError("TypeError", "Cannot access offset of type " + EnumApi.valueType(value) + " on string");
    }

    private static void warning(Diagnostics.Origin origin, String message) {
        if (origin != null) origin.warning(message);
    }
}
