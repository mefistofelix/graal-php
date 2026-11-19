package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import graalphp.runtime.Execution.Activation;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Explicit PHP casts accept numeric prefixes and have their own coercion rules. */
public final class Casts {
    private static final Pattern NUMERIC_PREFIX = Pattern.compile(
            "^[ \\t\\n\\r\\f\\x0B]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final MathContext PRINT_PRECISION = new MathContext(14, RoundingMode.HALF_EVEN);

    private Casts() {}

    @TruffleBoundary
    public static Object apply(Activation caller, String kind, Object value, IndirectCallNode call) {
        Object raw = PhpValues.unwrap(value);
        try {
            return switch (kind) {
                case "bool" -> Operations.truth(raw);
                case "int" -> integer(caller, raw);
                case "float" -> number(caller, raw, "float").doubleValue();
                case "string" -> string(caller, raw, call);
                default -> throw new AssertionError(kind);
            };
        } finally { PhpValues.drop(value); }
    }

    private static long integer(Activation caller, Object value) {
        Number converted = number(caller, value, "int");
        if (converted instanceof Long integer) return integer;
        double decimal = converted.doubleValue();
        if (value instanceof String || value instanceof PhpString) return Double.isFinite(decimal) ? (long) decimal : 0L;
        if (decimal >= -0x1p63 && decimal < 0x1p63) return (long) decimal;
        warning(caller, "The float " + exactFloat(decimal) + " is not representable as an int, cast occurred");
        if (!Double.isFinite(decimal)) return 0L;
        double wrapped = decimal % 0x1p64;
        if (wrapped >= 0x1p63) wrapped -= 0x1p64;
        else if (wrapped < -0x1p63) wrapped += 0x1p64;
        return (long) wrapped;
    }

    private static Number number(Activation caller, Object value, String kind) {
        if (value instanceof Number number) return number;
        if (value instanceof PhpString bytes) value = new String(PhpString.bytes(bytes), StandardCharsets.ISO_8859_1);
        if (value instanceof String text) {
            var numeric = NUMERIC_PREFIX.matcher(text);
            if (!numeric.find()) return 0L;
            String prefix = numeric.group(1);
            try { return Long.parseLong(prefix); }
            catch (NumberFormatException decimal) { return Double.parseDouble(prefix); }
        }
        if (value == null || value instanceof Boolean || value instanceof PhpValues.PhpArray)
            return Operations.truth(value) ? 1L : 0L;
        warning(caller, "Object of class " + ObjectModel.className(value) + " could not be converted to " + kind);
        return 1L;
    }

    private static Object string(Activation caller, Object value, IndirectCallNode call) {
        if (value instanceof String || value instanceof PhpString) return value;
        if (value instanceof Double decimal) {
            if (Double.isNaN(decimal)) warning(caller, "unexpected NAN value was coerced to string");
            return floatingString(decimal);
        }
        if (value instanceof PhpValues.PhpArray) {
            warning(caller, "Array to string conversion");
            return "Array";
        }
        if (value instanceof PhpValues.PhpObject object && object.descriptor instanceof ObjectModel.RuntimeClass type) {
            var method = type.method("__tostring");
            if (method != null && caller != null)
                return ObjectModel.invoke(caller, new ObjectModel.Invocation(method.function(), object, type, null), new Execution.Argument[0], call);
        }
        if (value == null || value instanceof Boolean || value instanceof Number || value instanceof PhpError)
            return Operations.string(value);
        throw new PhpError("Error", "Object of class " + ObjectModel.className(value) + " could not be converted to string");
    }

    static String floatingString(double decimal) {
        if (!Double.isFinite(decimal)) return exactFloat(decimal);
        if (decimal == 0.0) return Double.doubleToRawLongBits(decimal) < 0 ? "-0" : "0";
        var rounded = new BigDecimal(decimal, PRINT_PRECISION).stripTrailingZeros();
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (exponent >= -4 && exponent < PRINT_PRECISION.getPrecision()) return rounded.toPlainString();
        String mantissa = rounded.movePointLeft(exponent).toPlainString();
        if (!mantissa.contains(".")) mantissa += ".0";
        return mantissa + "E" + (exponent >= 0 ? "+" : "") + exponent;
    }

    private static String exactFloat(double decimal) {
        if (Double.isNaN(decimal)) return "NAN";
        if (Double.isInfinite(decimal)) return decimal < 0 ? "-INF" : "INF";
        String text = Double.toString(decimal);
        return text.contains("E-") ? text : text.replace("E", "E+");
    }

    private static void warning(Activation caller, String message) {
        if (caller != null) Diagnostics.origin(caller).warning(message);
    }
}
