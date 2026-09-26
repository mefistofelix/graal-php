package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import graalphp.frontend.Ir;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import static graalphp.runtime.Execution.*;

/** Shared type algebra for invocation checks and declaration variance. No autoload or guest execution. */
public final class TypeRelations {
    private TypeRelations() {}
    private static final Pattern NAMES = Pattern.compile("[A-Za-z_\\\\][A-Za-z_0-9\\\\]*");
    private static final Set<String> SCALARS = Set.of("int", "float", "string", "bool", "true", "false", "null",
            "mixed", "void", "never", "array", "object", "callable", "iterable");

    @TruffleBoundary
    public static String contextual(String type, Request request, String owner, String calledClass) {
        if (type == null) return null;
        var matcher = NAMES.matcher(type);
        var result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group();
            String replacement = name;
            switch (name.toLowerCase(Locale.ROOT)) {
                case "self" -> {
                    if (owner == null) throw new PhpError("Error", "self used outside a class");
                    replacement = owner;
                }
                case "static" -> {
                    if (calledClass == null) throw new PhpError("Error", "static used outside a class");
                    replacement = calledClass;
                }
                case "parent" -> {
                    var definition = owner == null ? null : request.classes.get(owner.toLowerCase(Locale.ROOT));
                    if (definition == null || definition.parent() == null) throw new PhpError("Error", "Class has no parent");
                    replacement = definition.parent();
                }
                default -> { }
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String signature(String type, Request request, String owner) {
        return contextual(type, request, owner, "@static:" + owner);
    }

    private static List<String> split(String type, char separator) {
        var parts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < type.length(); index++) {
            char character = type.charAt(index);
            if (character == '(') depth++;
            else if (character == ')') depth--;
            else if (character == separator && depth == 0) {
                parts.add(type.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(type.substring(start));
        return parts;
    }

    /** A union of intersections. Distribution also handles parenthesized DNF types. */
    static List<List<String>> alternatives(String type) {
        if (type == null) return List.of(List.of("mixed"));
        if (type.startsWith("?")) return alternatives(type.substring(1) + "|null");
        var union = split(type, '|');
        if (union.size() > 1) {
            var result = new ArrayList<List<String>>();
            for (String item : union) result.addAll(alternatives(item));
            return result;
        }
        var intersection = split(type, '&');
        if (intersection.size() > 1) {
            List<List<String>> product = List.of(List.of());
            for (String item : intersection) {
                var next = new ArrayList<List<String>>();
                for (var left : product) for (var right : alternatives(item)) {
                    var combined = new ArrayList<>(left);
                    combined.addAll(right);
                    next.add(List.copyOf(combined));
                }
                product = next;
            }
            return product;
        }
        if (type.startsWith("(") && type.endsWith(")")) return alternatives(type.substring(1, type.length() - 1));
        if (type.equalsIgnoreCase("iterable")) return List.of(List.of("array"), List.of("traversable"));
        return List.of(List.of(type.toLowerCase(Locale.ROOT)));
    }

    public static boolean subtype(Request request, String source, String target) {
        for (var offered : alternatives(source)) {
            boolean accepted = false;
            for (var expected : alternatives(target)) {
                if (expected.stream().allMatch(required -> offered.stream().anyMatch(actual -> atomSubtype(request, actual, required)))) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) return false;
        }
        return true;
    }

    private static boolean atomSubtype(Request request, String source, String target) {
        if (source.equals(target) || source.equals("never") || target.equals("mixed")) return true;
        if (target.startsWith("@static:")) {
            return source.startsWith("@static:") && classSubtype(request, source.substring(8), target.substring(8), new HashSet<>());
        }
        if (source.startsWith("@static:")) source = source.substring(8);
        if (target.equals("bool") && (source.equals("true") || source.equals("false"))) return true;
        if (target.equals("object") && !SCALARS.contains(source)) return true;
        if (SCALARS.contains(source) || SCALARS.contains(target)) return false;
        return classSubtype(request, source, target, new HashSet<>());
    }

    private static boolean classSubtype(Request request, String source, String target, Set<String> visiting) {
        if (source.equalsIgnoreCase(target)) return true;
        String key = source.toLowerCase(Locale.ROOT);
        if (!visiting.add(key)) return false;
        var definition = request.classes.get(key);
        if (definition == null || definition.kind() == Ir.TypeKind.TRAIT) return false;
        if (definition.parent() != null && classSubtype(request, definition.parent(), target, visiting)) return true;
        for (String name : definition.interfaces()) if (classSubtype(request, name, target, visiting)) return true;
        return false;
    }

    @TruffleBoundary
    public static boolean accepts(Object value, String type) {
        value = PhpValues.unwrap(value);
        for (var intersection : alternatives(type)) {
            boolean accepted = true;
            for (String atom : intersection) {
                if (!acceptsAtom(value, atom)) { accepted = false; break; }
            }
            if (accepted) return true;
        }
        return false;
    }

    private static boolean acceptsAtom(Object value, String type) {
        String builtin = ObjectModel.className(value);
        return switch (type) {
            case "mixed" -> true;
            case "null", "void" -> value == null;
            case "never" -> false;
            case "int" -> value instanceof Long;
            case "float" -> value instanceof Double;
            case "string" -> value instanceof String || value instanceof PhpString;
            case "bool" -> value instanceof Boolean;
            case "true" -> Boolean.TRUE.equals(value);
            case "false" -> Boolean.FALSE.equals(value);
            case "array" -> value instanceof PhpValues.PhpArray;
            case "object" -> builtin != null;
            case "callable" -> value instanceof String || value instanceof PhpValues.PhpArray
                    || value instanceof PhpValues.PhpObject object && (object.descriptor instanceof ObjectModel.ClosureData
                    || object.descriptor instanceof ObjectModel.RuntimeClass clazz && clazz.method("__invoke") != null);
            default -> {
                if (value instanceof PhpValues.PhpObject object && object.descriptor instanceof ObjectModel.RuntimeClass clazz)
                    yield clazz.isA(type);
                if (value instanceof PhpError error) yield error.matches(type);
                yield builtin != null && (builtin.equalsIgnoreCase(type)
                        || type.equals("async\\completable") && value instanceof Scheduler.Future);
            }
        };
    }

    @TruffleBoundary
    public static Object check(Object value, String type) { return check(value, type, false, null); }

    @TruffleBoundary
    public static Object check(Object value, String type, boolean strict, Diagnostics.Origin origin) {
        value = PhpValues.unwrap(value);
        if (accepts(value, type)) return value;
        if (strict) {
            if (value instanceof Long number && alternatives(type).contains(List.of("float"))) return number.doubleValue();
            throw new PhpError("TypeError", "Value does not satisfy type " + type);
        }
        if (value != null && (value instanceof Number || value instanceof String || value instanceof Boolean)) {
            // Exact union membership is tested first: an existing string is not coerced by int|string.
            var alternatives = alternatives(type);
            boolean preferFloat = false;
            if (value instanceof String && alternatives.contains(List.of("int")) && alternatives.contains(List.of("float"))) {
                try { preferFloat = Operations.number(value) instanceof Double; }
                catch (PhpError invalidNumber) { /* The scalar alternatives below determine acceptance or TypeError. */ }
            }
            for (String scalar : preferFloat ? List.of("float", "int", "string", "bool") : List.of("int", "float", "string", "bool")) {
                if (!alternatives.contains(List.of(scalar))) continue;
                try {
                    return switch (scalar) {
                        case "int" -> integer(value, origin);
                        case "float" -> Operations.number(value).doubleValue();
                        case "string" -> Operations.string(value);
                        default -> Operations.truth(value);
                    };
                } catch (PhpError invalid) {
                    if (!scalar.equals("int") && !scalar.equals("float")) throw invalid;
                }
            }
        }
        throw new PhpError("TypeError", "Value does not satisfy type " + type);
    }

    private static long integer(Object value, Diagnostics.Origin origin) {
        Number number = Operations.number(value);
        if (number instanceof Long integer) return integer;
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal < -0x1.0p63 || decimal >= 0x1.0p63)
            throw new PhpError("TypeError", "Value cannot be converted to int");
        long integer = (long) decimal;
        if (decimal != (double) integer && origin != null) {
            String from = value instanceof String text ? "float-string \"" + text + "\"" : "float " + Operations.string(decimal);
            origin.deprecated("Implicit conversion from " + from + " to int loses precision");
        }
        return integer;
    }

    static void compatible(Request request, ObjectModel.Method implementation, ObjectModel.Method contract) {
        var actual = implementation.function();
        var expected = contract.function();
        String message = "Declaration of " + actual.owner() + "::" + actual.name()
                + " must be compatible with " + expected.owner() + "::" + expected.name();
        if (implementation.shared() != contract.shared() || visibility(implementation.visibility()) < visibility(contract.visibility()))
            throw PhpError.fatal(message);
        var actualParameters = actual.parameters();
        var expectedParameters = expected.parameters();
        long actualRequired = required(actualParameters);
        long expectedRequired = required(expectedParameters);
        boolean actualVariadic = !actualParameters.isEmpty() && actualParameters.getLast().variadic();
        boolean expectedVariadic = !expectedParameters.isEmpty() && expectedParameters.getLast().variadic();
        if (actualRequired > expectedRequired || expectedVariadic && !actualVariadic
                || !actualVariadic && actualParameters.size() < expectedParameters.size()) throw PhpError.fatal(message);
        for (int index = 0; index < expectedParameters.size(); index++) {
            var formal = expectedParameters.get(index);
            var parameter = index < actualParameters.size() ? actualParameters.get(index) : actualParameters.getLast();
            if (formal.reference() != parameter.reference()
                    || !subtype(request, signature(formal.type(), request, expected.owner()), signature(parameter.type(), request, actual.owner())))
                throw PhpError.fatal(message);
        }
        if (expectedVariadic) {
            var formal = expectedParameters.getLast();
            for (int index = expectedParameters.size(); index < actualParameters.size(); index++) {
                var parameter = actualParameters.get(index);
                if (formal.reference() != parameter.reference() || !subtype(request,
                        signature(formal.type(), request, expected.owner()), signature(parameter.type(), request, actual.owner())))
                    throw PhpError.fatal(message);
            }
        }
        if (expected.returnType() != null && (actual.returnType() == null
                || !subtype(request, signature(actual.returnType(), request, actual.owner()), signature(expected.returnType(), request, expected.owner()))))
            throw PhpError.fatal(message);
    }

    private static int required(List<Ir.Parameter> parameters) {
        int required = 0;
        for (int index = 0; index < parameters.size(); index++) {
            if (!parameters.get(index).variadic() && parameters.get(index).defaultValue() == null) required = index + 1;
        }
        return required;
    }

    static int visibility(String visibility) {
        return switch (visibility) { case "public" -> 2; case "protected" -> 1; default -> 0; };
    }
}
