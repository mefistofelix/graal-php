package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict PHP equality: ordered array contents, exact scalar types and object identity. */
public final class ValueEquality {
    private ValueEquality() {}
    private record Pair(PhpValues.PhpArray left, PhpValues.PhpArray right) {}

    @TruffleBoundary
    public static boolean identical(Object left, Object right) {
        return identical(PhpValues.unwrap(left), PhpValues.unwrap(right), new HashSet<>(), 0);
    }

    private static boolean identical(Object left, Object right, Set<Pair> visiting, int depth) {
        if (left instanceof Double first && right instanceof Double second) return first.doubleValue() == second.doubleValue();
        if (left == right) return true;
        if (left instanceof PhpValues.PhpArray first && right instanceof PhpValues.PhpArray second) {
            var pair = new Pair(first, second);
            if (depth > 512 || !visiting.add(pair)) throw new PhpError("Error", "Nesting level too deep - recursive dependency in array comparison");
            try {
                List<Object> keys = PhpValues.keys(first);
                if (!keys.equals(PhpValues.keys(second))) return false;
                for (Object key : keys) {
                    Object a = PhpValues.element(first, key);
                    Object b = PhpValues.element(second, key);
                    try {
                        if (!identical(PhpValues.unwrap(a), PhpValues.unwrap(b), visiting, depth + 1)) return false;
                    } finally { PhpValues.drop(a); PhpValues.drop(b); }
                }
                return true;
            } finally { visiting.remove(pair); }
        }
        return Objects.equals(left, right);
    }
}
