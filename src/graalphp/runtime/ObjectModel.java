package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import graalphp.frontend.Ir;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static graalphp.runtime.Execution.*;

/** Immutable declarations are shared by generations; class storage belongs to a request. */
public final class ObjectModel {
    private ObjectModel() {}

    public record Method(Function function, boolean shared, String visibility) {}
    public record Definition(String name, String parent, List<Ir.PropertyDeclaration> properties,
                             Map<String, Method> methods) {}
    public record ClosureData(Function function, List<Ir.Capture> captures) {}
    public record ClosureTemplate(Function function, List<Ir.Capture> captures, boolean arrow) {}
    public record Invocation(Function function, PhpValues.PhpObject receiver, RuntimeClass calledClass,
                             PhpValues.PhpObject environment) {}

    public static final class RuntimeClass {
        public final Definition definition;
        public final RuntimeClass parent;
        public final PhpValues.PhpObject statics;

        RuntimeClass(Request request, Definition definition, RuntimeClass parent) {
            this.definition = definition;
            this.parent = parent;
            statics = new PhpValues.PhpObject(request.heap, this);
            request.globals.variable(statics);
            for (var property : definition.properties) {
                if (property.shared()) initialize(request, statics, definition.name, property);
            }
        }

        public boolean isA(String name) {
            return definition.name.equalsIgnoreCase(name) || parent != null && parent.isA(name);
        }

        public Method method(String name) {
            var method = definition.methods.get(name.toLowerCase(Locale.ROOT));
            return method != null ? method : parent == null ? null : parent.method(name);
        }
    }

    public static RuntimeClass resolve(Activation caller, String name) {
        String lexical = caller.function.owner();
        if (name.equals("static")) {
            if (caller.calledClass == null) throw new PhpError("static used outside a class");
            return caller.calledClass;
        }
        if (name.equals("self") || name.equals("parent")) {
            if (lexical == null) throw new PhpError(name + " used outside a class");
            var type = caller.request.type(lexical);
            if (name.equals("parent")) type = type.parent;
            if (type == null) throw new PhpError("Class has no parent");
            return type;
        }
        return caller.request.type(name);
    }

    @TruffleBoundary
    public static Object create(Activation caller, String name) {
        Object builtin = AsyncApi.allocate(caller, name);
        if (builtin != AsyncApi.UNHANDLED) return builtin;
        builtin = NetworkApi.allocate(caller, name);
        if (builtin != AsyncApi.UNHANDLED) return builtin;
        builtin = SqliteApi.allocate(caller, name);
        if (builtin != AsyncApi.UNHANDLED) return builtin;
        var type = resolve(caller, name);
        var object = new PhpValues.PhpObject(caller.request.heap, type);
        Object owned = caller.track(PhpValues.own(object));
        initializeObject(caller.request, object, type);
        return owned;
    }

    private static void initializeObject(Request request, PhpValues.PhpObject object, RuntimeClass type) {
        if (type.parent != null) initializeObject(request, object, type.parent);
        for (var property : type.definition.properties) {
            if (!property.shared()) initialize(request, object, type.definition.name, property);
        }
    }

    private static void initialize(Request request, PhpValues.PhpObject object, String owner, Ir.PropertyDeclaration property) {
        var location = object.field(storageName(owner, property));
        if (property.value() != null || property.type() == null) {
            Object value = property.value() == null ? null : constant(request, property.value());
            try { location.set(property.type() == null ? PhpValues.unwrap(value) : checkType(PhpValues.unwrap(value), property.type())); }
            finally { PhpValues.drop(value); }
        }
        object.fieldType(storageName(owner, property), property.type());
    }

    private static String storageName(String owner, Ir.PropertyDeclaration property) {
        return property.visibility().equals("private") ? owner + "\0" + property.name() : property.name();
    }

    @TruffleBoundary
    public static PhpValues.Location property(Activation caller, Object value, String name) {
        if (PhpValues.unwrap(value) instanceof WebSocketConnection.Message message) {
            Object field = switch (name) {
                case "data" -> message.data(); case "binary" -> message.binary();
                default -> throw new PhpError("Undefined WebSocketMessage property " + name);
            };
            return caller.locals.variable(field).freeze();
        }
        if (!(PhpValues.unwrap(value) instanceof PhpValues.PhpObject object)
                || !(object.descriptor instanceof RuntimeClass type)) throw new PhpError("Property access requires an object");
        if (caller.function.owner() != null && type.isA(caller.function.owner())) {
            var lexical = caller.request.type(caller.function.owner());
            for (var property : lexical.definition.properties) {
                if (!property.shared() && property.visibility().equals("private") && property.name().equals(name)) {
                    return object.field(storageName(lexical.definition.name, property));
                }
            }
        }
        for (var current = type; current != null; current = current.parent) {
            for (var property : current.definition.properties) {
                if (!property.shared() && property.name().equals(name)) {
                    access(caller, current, property.visibility());
                    return object.field(storageName(current.definition.name, property));
                }
            }
        }
        return object.field(name);
    }

    @TruffleBoundary
    public static PhpValues.Location staticProperty(Activation caller, String className, String name) {
        for (var type = resolve(caller, className); type != null; type = type.parent) {
            for (var property : type.definition.properties) {
                if (property.shared() && property.name().equals(name)) {
                    access(caller, type, property.visibility());
                    return type.statics.field(storageName(type.definition.name, property));
                }
            }
        }
        throw new PhpError("Undefined static property " + className + "::$" + name);
    }

    private static void access(Activation caller, RuntimeClass owner, String visibility) {
        if (visibility.equals("public")) return;
        String scope = caller.function.owner();
        if (scope != null && (scope.equalsIgnoreCase(owner.definition.name)
                || visibility.equals("protected") && (caller.request.type(scope).isA(owner.definition.name) || owner.isA(scope)))) return;
        throw new PhpError("Cannot access " + visibility + " member of " + owner.definition.name);
    }

    @TruffleBoundary
    public static Invocation method(Activation caller, Object value, String name, boolean constructor) {
        if (!(PhpValues.unwrap(value) instanceof PhpValues.PhpObject object)
                || !(object.descriptor instanceof RuntimeClass type)) throw new PhpError("Method call requires an object");
        var method = type.method(name);
        if (method == null && constructor) return null;
        if (method == null) throw new PhpError("Undefined method " + type.definition.name + "::" + name);
        access(caller, caller.request.type(method.function.owner()), method.visibility);
        return new Invocation(method.function, method.shared ? null : object, type, null);
    }

    @TruffleBoundary
    public static Invocation staticMethod(Activation caller, String className, String name) {
        var type = resolve(caller, className);
        var method = type.method(name);
        if (method == null) throw new PhpError("Undefined method " + className + "::" + name);
        access(caller, caller.request.type(method.function.owner()), method.visibility);
        PhpValues.PhpObject receiver = null;
        if (!method.shared) {
            Object value = caller.variable("this").readOrNull();
            if (!(value instanceof PhpValues.PhpObject object) || !(object.descriptor instanceof RuntimeClass receiverType)
                    || !receiverType.isA(type.definition.name)) throw new PhpError("Non-static method requires an object");
            receiver = object;
        }
        var called = List.of("self", "parent", "static").contains(className) && caller.calledClass != null ? caller.calledClass : type;
        return new Invocation(method.function, receiver, called, null);
    }

    @TruffleBoundary
    public static Object closure(Activation caller, Function function, List<Ir.Capture> captures, boolean arrow) {
        var object = new PhpValues.PhpObject(caller.request.heap, new ClosureData(function, captures));
        Object owned = caller.track(PhpValues.own(object));
        if (arrow) {
            var names = caller.topLevel ? caller.request.variables.keySet() : caller.variables.keySet();
            for (String name : List.copyOf(names)) object.field(name).set(caller.variable(name).readOrNull());
        } else {
            for (var capture : captures) {
                if (capture.reference()) object.field(capture.name()).bind(caller.variable(capture.name()));
                else object.field(capture.name()).set(caller.variable(capture.name()).readOrNull());
            }
        }
        Object receiver = caller.variable("this").readOrNull();
        if (receiver != null) object.field("this").set(receiver);
        return owned;
    }

    @TruffleBoundary
    public static Invocation callable(Activation caller, Object value) {
        value = PhpValues.unwrap(value);
        if (value instanceof String name) {
            int separator = name.indexOf("::");
            if (separator >= 0) return staticMethod(caller, name.substring(0, separator), name.substring(separator + 2));
            return new Invocation(caller.request.function(name), null, null, null);
        }
        if (value instanceof PhpValues.PhpObject object) {
            if (object.descriptor instanceof ClosureData closure) {
                Object receiver = object.field("this").readOrNull();
                var self = receiver instanceof PhpValues.PhpObject instance ? instance : null;
                return new Invocation(closure.function, self,
                        self != null && self.descriptor instanceof RuntimeClass type ? type : null, object);
            }
            return method(caller, value, "__invoke", false);
        }
        if (value instanceof PhpValues.PhpArray && PhpValues.keys(value).size() == 2) {
            Object receiver = PhpValues.element(value, 0L);
            Object member = PhpValues.element(value, 1L);
            try {
                return PhpValues.unwrap(receiver) instanceof String type
                        ? staticMethod(caller, type, Operations.string(member))
                        : method(caller, receiver, Operations.string(member), false);
            } finally { PhpValues.drop(receiver); PhpValues.drop(member); }
        }
        throw new PhpError("Value is not callable");
    }

    @TruffleBoundary
    public static Object invoke(Activation caller, Invocation invocation, Argument[] args, IndirectCallNode call) {
        try {
            if (invocation == null) return null;
            var child = new Activation(caller.request, invocation.function, caller.task, false, args);
            bind(child, invocation);
            return Operations.executeChild(caller, child, call);
        } finally { for (var argument : args) argument.close(); }
    }

    public static void bind(Activation child, Invocation invocation) {
        child.calledClass = invocation.calledClass;
        if (invocation.environment != null) {
            child.locals.variable(invocation.environment);
            var parameters = child.function.parameters().stream().map(Ir.Parameter::name).toList();
            var captures = ((ClosureData) invocation.environment.descriptor).captures;
            for (var name : invocation.environment.fieldNames()) {
                if (parameters.contains(name)) continue;
                boolean reference = captures.stream().anyMatch(capture -> capture.name().equals(name) && capture.reference());
                if (reference) child.variable(name).bind(invocation.environment.field(name));
                else child.variable(name).set(invocation.environment.field(name).readOrNull());
            }
        }
        if (invocation.receiver != null) child.variable("this").set(invocation.receiver);
    }

    @TruffleBoundary
    public static Object constant(Request request, Ir.Expression expression) {
        return switch (expression) {
            case Ir.Literal literal -> literal.value();
            case Ir.Unary unary -> {
                var number = Operations.number(constant(request, unary.value()));
                if (unary.operator().equals("+")) yield number;
                if (unary.operator().equals("!")) yield !Operations.truth(number);
                if (number instanceof Long value && value != Long.MIN_VALUE) yield -value;
                yield -number.doubleValue();
            }
            case Ir.Binary binary -> Operations.binary(binary.operator(), constant(request, binary.left()), constant(request, binary.right()));
            case Ir.ArrayLiteral literal -> {
                try (var scope = new PhpValues.Scope(request.heap)) {
                    var array = scope.variable(scope.emptyArray());
                    for (var entry : literal.entries()) {
                        if (entry.reference()) throw new PhpError("References are not allowed in constant expressions");
                        Object key = entry.key() == null ? null : constant(request, entry.key());
                        Object value = constant(request, entry.value());
                        try { (entry.key() == null ? array.append() : array.element(PhpValues.unwrap(key))).set(PhpValues.unwrap(value)); }
                        finally { PhpValues.drop(key); PhpValues.drop(value); }
                    }
                    yield PhpValues.own(array.read());
                }
            }
            case Ir.Constant constant -> namedConstant(constant.name());
            default -> throw new PhpError("Unsupported constant expression");
        };
    }

    public static Object namedConstant(String name) {
        if (CurlApi.CONSTANTS.containsKey(name)) return CurlApi.CONSTANTS.get(name);
        if (CurlMultiApi.CONSTANTS.containsKey(name)) return CurlMultiApi.CONSTANTS.get(name);
        if (SqliteApi.CONSTANTS.containsKey(name)) return SqliteApi.CONSTANTS.get(name);
        return switch (name) {
            case "PHP_VERSION" -> "8.6.0-graalphp";
            case "PHP_INT_MAX" -> Long.MAX_VALUE;
            case "PHP_INT_MIN" -> Long.MIN_VALUE;
            case "PHP_INT_SIZE" -> 8L;
            case "PHP_EOL" -> System.lineSeparator();
            case "DIRECTORY_SEPARATOR" -> java.io.File.separator;
            default -> throw new PhpError("Undefined constant " + name);
        };
    }

    @TruffleBoundary
    public static Object checkType(Object value, String declaredType) {
        value = PhpValues.unwrap(value);
        if (declaredType == null || declaredType.equals("mixed")) return value;
        String builtinClass = AsyncApi.className(value);
        if (builtinClass == null) builtinClass = NetworkApi.className(value);
        if (builtinClass == null) builtinClass = SqliteApi.className(value);
        if (value instanceof CurlApi.Handle) builtinClass = "CurlHandle";
        if (value instanceof CurlMultiApi.Handle) builtinClass = "CurlMultiHandle";
        if (builtinClass != null && (builtinClass.equalsIgnoreCase(declaredType)
                || declaredType.equals("object") || declaredType.equalsIgnoreCase("Async\\Completable") && value instanceof Scheduler.Future)) return value;
        String type = declaredType;
        if (type.startsWith("?")) {
            if (value == null) return null;
            type = type.substring(1);
        }
        boolean valid = switch (type) {
            case "null", "void" -> value == null;
            case "never" -> false;
            case "int" -> value instanceof Long;
            case "float" -> value instanceof Double;
            case "string" -> value instanceof String || value instanceof PhpString;
            case "bool" -> value instanceof Boolean;
            case "true" -> Boolean.TRUE.equals(value);
            case "false" -> Boolean.FALSE.equals(value);
            case "array" -> value instanceof PhpValues.PhpArray;
            case "object" -> value instanceof PhpValues.PhpObject;
            case "callable" -> value instanceof String || value instanceof PhpValues.PhpObject || value instanceof PhpValues.PhpArray;
            default -> value instanceof PhpValues.PhpObject object && object.descriptor instanceof RuntimeClass clazz && clazz.isA(type);
        };
        if (valid) return value;
        if (value != null && (value instanceof Number || value instanceof String || value instanceof Boolean)) {
            switch (type) {
                case "int": return Operations.number(value).longValue();
                case "float": return Operations.number(value).doubleValue();
                case "string": return Operations.string(value);
                case "bool": return Operations.truth(value);
            }
        }
        throw new PhpError("Value does not satisfy type " + declaredType);
    }
}
