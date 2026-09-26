package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;
import graalphp.frontend.Ir;
import graalphp.frontend.Parser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static graalphp.runtime.Execution.*;

/** Request-owned enum cases and native-backed methods compiled through ordinary PHP bytecode. */
public final class EnumApi {
    private EnumApi() {}
    public static final String INTERFACES = """
        interface UnitEnum { public static function cases(): array; }
        interface BackedEnum extends UnitEnum {
            public static function from(int|string $value): static;
            public static function tryFrom(int|string $value): ?static;
        }
        """;
    private static final Source METHOD_SOURCE = Source.newBuilder("php", """
        class EnumBuiltins {
            public static function cases(): array { return __enum_cases(self::class); }
            public static function from(int|string $value): static { return __enum_from(self::class, $value, false); }
            public static function tryFrom(int|string $value): ?static { return __enum_from(self::class, $value, true); }
        }
        """, "<enum-builtins>").internal(true).build();
    // The immutable syntax template is shared, but must not enter a Native Image heap
    // while frontend IR classes retain their normal runtime initialization policy.
    private static Ir.ClassDeclaration methods;
    public static Source methodSource() { return METHOD_SOURCE; }
    @TruffleBoundary
    public static synchronized Ir.ClassDeclaration generatedMethods() {
        if (methods == null) methods = new Parser(METHOD_SOURCE).parse().classes().getFirst();
        return methods;
    }

    public static final class State {
        private final ObjectModel.RuntimeClass type;
        private final Map<String, Ir.EnumCase> declarations = new LinkedHashMap<>();
        private final Map<String, PhpValues.PhpObject> cases = new LinkedHashMap<>();
        private final Map<Object, String> byValue = new LinkedHashMap<>();
        private final java.util.Set<String> evaluating = new java.util.HashSet<>();
        private boolean lookupPrepared;
        private boolean preparingLookup;

        State(ObjectModel.RuntimeClass type) {
            this.type = type;
            for (var declaration : type.definition.cases()) declarations.put(declaration.name(), declaration);
        }

        @TruffleBoundary
        public Object caseObject(Request request, String name) {
            var existing = cases.get(name);
            if (existing != null) return PhpValues.own(existing);
            var declaration = declarations.get(name);
            if (declaration == null) throw new PhpError("Error", "Undefined enum case " + type.definition.name() + "::" + name);
            if (!evaluating.add(name)) throw new PhpError("Error", "Cyclic enum case initializer " + type.definition.name() + "::" + name);
            try {
                Object value = declaration.value() == null ? null : ObjectModel.constant(request, declaration.value(), type.definition.name());
                try (var scope = new PhpValues.Scope(request.heap)) {
                    var object = new PhpValues.PhpObject(request.heap, type);
                    scope.variable(object);
                    object.field("name").set(name);
                    if (declaration.value() != null) object.field("value").set(PhpValues.unwrap(value));
                    object.sealEnum(type.definition.name());
                    type.statics.field("\0enum:" + name).set(object);
                    cases.put(name, object);
                    return PhpValues.own(object);
                } finally { PhpValues.drop(value); }
            } finally { evaluating.remove(name); }
        }

        /** PHP validates the backing lookup on constant access/from, but cases() can enumerate unvalidated values. */
        @TruffleBoundary
        public void prepareConstantAccess(Request request) {
            if (type.definition.backingType() == null || lookupPrepared || preparingLookup || !evaluating.isEmpty()) return;
            preparingLookup = true;
            try {
                var table = new LinkedHashMap<Object, String>();
                for (String name : declarations.keySet()) {
                    Object owned = caseObject(request, name);
                    try {
                        Object value = ((PhpValues.PhpObject) PhpValues.unwrap(owned)).field("value").read();
                        boolean valid = type.definition.backingType().equals("int") ? value instanceof Long
                                : value instanceof String || value instanceof PhpString;
                        if (!valid) throw new PhpError("TypeError", "Enum case type " + valueType(value)
                                + " does not match enum backing type " + type.definition.backingType());
                        String previous = table.putIfAbsent(value, name);
                        if (previous != null) throw new PhpError("Error", "Duplicate value in enum " + type.definition.name()
                                + " for cases " + previous + " and " + name);
                    } finally { PhpValues.drop(owned); }
                }
                byValue.putAll(table);
                lookupPrepared = true;
            } finally { preparingLookup = false; }
        }

        private Object allCases(Activation caller) {
            try (var scope = new PhpValues.Scope(caller.request.heap)) {
                var result = scope.variable(scope.emptyArray());
                for (String name : declarations.keySet()) {
                    Object value = caseObject(caller.request, name);
                    try { result.append().set(PhpValues.unwrap(value)); }
                    finally { PhpValues.drop(value); }
                }
                return caller.track(PhpValues.own(result.read()));
            }
        }

        private Object from(Activation caller, Object input, boolean nullable) {
            if (type.definition.backingType() == null) throw new PhpError("Error", "Non-backed enums have no generated from method");
            String operation = type.definition.name() + "::" + (nullable ? "tryFrom" : "from");
            Object argument = PhpValues.unwrap(input);
            String expected = type.definition.backingType();
            if (caller.strictArguments && (expected.equals("int") ? !(argument instanceof Long)
                    : !(argument instanceof String || argument instanceof PhpString)))
                throw new PhpError("TypeError", operation + "(): Argument #1 ($value) must be of type " + expected + ", " + valueType(argument) + " given");
            if (argument == null) {
                Diagnostics.deprecated(caller, operation + "(): Passing null to parameter #1 ($value) of type string|int is deprecated");
                argument = 0L;
            } else if (argument instanceof Boolean flag) {
                argument = flag ? 1L : 0L;
            } else if (argument instanceof Double number) {
                argument = integer(caller, number, false);
            } else if (!(argument instanceof Long || argument instanceof String || argument instanceof PhpString)) {
                throw new PhpError("TypeError", operation + "(): Argument #1 ($value) must be of type string|int, " + valueType(argument) + " given");
            }
            Object key;
            if (type.definition.backingType().equals("int")) {
                if (argument instanceof Long) key = argument;
                else if (argument instanceof String text) {
                    Number numeric;
                    try { numeric = Operations.number(text); }
                    catch (PhpError invalid) { throw new PhpError("TypeError", operation + "(): Argument #1 ($value) must be of type int, string given"); }
                    key = numeric instanceof Double decimal ? integer(caller, decimal, text) : numeric.longValue();
                } else throw new PhpError("TypeError", operation + "(): Argument #1 ($value) must be of type int, string given");
            } else key = argument instanceof Long ? Operations.string(argument) : argument;
            prepareConstantAccess(caller.request);
            String name = byValue.get(key);
            if (name != null) return caller.track(caseObject(caller.request, name));
            if (nullable) return null;
            String shown = key instanceof String ? "\"" + key + "\"" : key instanceof PhpString ? "Binary string" : Operations.string(key);
            throw new PhpError("ValueError", shown + " is not a valid backing value for enum " + type.definition.name());
        }
    }

    private static long integer(Activation caller, double value, Object text) {
        if (!Double.isFinite(value) || value < Long.MIN_VALUE || value >= 0x1.0p63)
            throw new PhpError("TypeError", "Enum backing conversion is outside the integer range");
        long integer = (long) value;
        if (integer != value) {
            String source = text instanceof String string ? "float-string \"" + string + "\"" : "float " + Operations.string(value);
            Diagnostics.deprecated(caller, "Implicit conversion from " + source + " to int loses precision");
        }
        return integer;
    }

    public static String valueType(Object value) {
        value = PhpValues.unwrap(value);
        if (value == null) return "null";
        if (value instanceof Long) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof Boolean) return "bool";
        if (value instanceof String || value instanceof PhpString) return "string";
        if (value instanceof PhpValues.PhpArray) return "array";
        String type = ObjectModel.className(value);
        return type == null ? "object" : type;
    }

    @TruffleBoundary
    public static Object function(Activation caller, String name, Argument[] arguments) {
        if (!name.equals("__enum_cases") && !name.equals("__enum_from")) return AsyncApi.UNHANDLED;
        if (!caller.function.builtin()) throw new PhpError("Error", "Enum helper cannot be called directly");
        var type = caller.request.type(caller.function.owner());
        if (type.enumState == null) throw new PhpError("Error", "Enum helper requires an enum");
        return name.equals("__enum_cases") ? type.enumState.allCases(caller)
                : type.enumState.from(caller, arguments[1].value(), Operations.truth(arguments[2].value()));
    }

    static void validate(ObjectModel.Definition definition, List<Ir.PropertyDeclaration> properties, Map<String, ObjectModel.Method> methods) {
        if (definition.kind() != Ir.TypeKind.ENUM) return;
        if (!properties.isEmpty()) throw PhpError.fatal("Enum " + definition.name() + " cannot include properties");
        for (var entry : methods.entrySet()) {
            if (entry.getValue().abstractMethod()) throw PhpError.fatal("Enum " + definition.name() + " must implement abstract method " + entry.getKey());
            if (entry.getKey().startsWith("__") && !List.of("__call", "__callstatic", "__invoke").contains(entry.getKey()))
                throw PhpError.fatal("Enum " + definition.name() + " cannot include magic method " + entry.getKey());
        }
    }
}
