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

    public record Method(Function function, boolean shared, String visibility, boolean abstractMethod,
                         boolean finalMethod) {
        public Method inClass(String owner) {
            var rebound = new Function(function.name(), function.parameters(), function.target(), function.file(), owner, function.returnType(), function.builtin(), function.strictTypes(), function.declaration());
            return new Method(rebound, shared, visibility, abstractMethod, finalMethod);
        }
    }
    public record Definition(String name, String parent, List<Ir.PropertyDeclaration> properties,
                             Map<String, Method> methods, Ir.TypeKind kind, boolean abstractType,
                             boolean finalType, List<String> interfaces, List<Ir.TraitUse> traits,
                             List<Ir.ClassConstantDeclaration> constants, String backingType,
                             List<Ir.EnumCase> cases, List<Ir.Attribute> attributes) implements com.oracle.truffle.api.interop.TruffleObject {
        public List<String> dependencies() {
            var dependencies = new java.util.ArrayList<String>();
            if (parent != null) dependencies.add(parent);
            for (var use : traits) dependencies.addAll(use.names());
            dependencies.addAll(interfaces);
            return List.copyOf(dependencies);
        }
    }
    public record ClassConstant(Ir.ClassConstantDeclaration declaration, String owner) {}
    public record ClosureData(Function function, List<Ir.Capture> captures) {}
    public record ClosureTemplate(Function function, List<Ir.Capture> captures, boolean arrow) {}
    public record Invocation(Function function, PhpValues.PhpObject receiver, RuntimeClass calledClass,
                             PhpValues.PhpObject environment, Diagnostics.Site site) {
        public Invocation(Function function, PhpValues.PhpObject receiver, RuntimeClass calledClass, PhpValues.PhpObject environment) {
            this(function, receiver, calledClass, environment, null);
        }
        // A diagnostic call site is not part of callable identity (SPL registration, aliases).
        @Override public boolean equals(Object other) {
            return other instanceof Invocation invocation && function.equals(invocation.function)
                    && receiver == invocation.receiver && calledClass == invocation.calledClass && environment == invocation.environment;
        }
        @Override public int hashCode() { return java.util.Objects.hash(function, receiver, calledClass, environment); }
    }

    public static final class RuntimeClass {
        public final Definition definition;
        public final RuntimeClass parent;
        public final PhpValues.PhpObject statics;
        public final List<RuntimeClass> interfaces;
        public final Map<String, Method> methods;
        public final List<Method> requirements;
        public final List<Ir.PropertyDeclaration> properties;
        public final Map<String, ClassConstant> constants;
        public final EnumApi.State enumState;
        private final java.util.Set<String> initializedConstants = new java.util.HashSet<>();
        private final java.util.Set<String> evaluatingConstants = new java.util.HashSet<>();
        private boolean staticsInitialized;

        RuntimeClass(Request request, Definition definition, RuntimeClass parent, List<RuntimeClass> interfaces,
                     Map<String, Method> methods, List<Method> requirements,
                     List<Ir.PropertyDeclaration> properties, Map<String, ClassConstant> constants) {
            this.definition = definition;
            this.parent = parent;
            this.interfaces = List.copyOf(interfaces);
            this.methods = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(methods));
            this.requirements = List.copyOf(requirements);
            this.properties = List.copyOf(properties);
            this.constants = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(constants));
            enumState = definition.kind() == Ir.TypeKind.ENUM ? new EnumApi.State(this) : null;
            statics = new PhpValues.PhpObject(request.heap, this);
            request.globals.variable(statics);
        }

        void initializeStatics(Request request) {
            if (staticsInitialized) return;
            for (var property : properties) {
                if (property.shared()) initialize(request, statics, definition.name, property);
            }
            staticsInitialized = true;
        }

        public boolean isA(String name) {
            if (definition.kind() == Ir.TypeKind.TRAIT) return false;
            return definition.name.equalsIgnoreCase(name) || parent != null && parent.isA(name)
                    || interfaces.stream().anyMatch(type -> type.isA(name));
        }

        public Method method(String name) {
            var method = methods.get(name.toLowerCase(Locale.ROOT));
            return method != null ? method : parent == null ? null : parent.method(name);
        }

        Object constant(Request request, String name) {
            if (enumState != null) enumState.prepareConstantAccess(request);
            var member = constants.get(name);
            if (member == null) throw new PhpError("Error", "Undefined constant " + definition.name + "::" + name);
            if (!member.owner.equals(definition.name)) return request.type(member.owner).constant(request, name);
            String storage = "\0constant:" + name;
            if (initializedConstants.contains(name)) return PhpValues.own(statics.field(storage).read());
            if (!evaluatingConstants.add(name)) throw new PhpError("Error", "Cyclic class constant " + definition.name + "::" + name);
            try {
                Object value = ObjectModel.constant(request, member.declaration.value(), definition.name);
                try {
                    String declaredType = TypeRelations.contextual(member.declaration.type(), request, definition.name, definition.name);
                    if (declaredType != null && !TypeRelations.accepts(PhpValues.unwrap(value), declaredType))
                        throw new PhpError("TypeError", "Class constant does not satisfy type " + declaredType);
                    statics.field(storage).set(PhpValues.unwrap(value));
                    initializedConstants.add(name);
                    return PhpValues.own(statics.field(storage).read());
                } finally { PhpValues.drop(value); }
            } finally { evaluatingConstants.remove(name); }
        }
    }

    @TruffleBoundary
    public static RuntimeClass resolve(Activation caller, String name) {
        if (name.startsWith("\\")) name = name.substring(1);
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
        if (type.definition.kind != Ir.TypeKind.CLASS || type.definition.abstractType)
            throw new PhpError("Error", "Cannot instantiate " + type.definition.kind.name().toLowerCase(Locale.ROOT) + " " + type.definition.name);
        var object = new PhpValues.PhpObject(caller.request.heap, type);
        Object owned = caller.track(PhpValues.own(object));
        initializeObject(caller.request, object, type);
        return owned;
    }

    @TruffleBoundary
    public static Object cloneObject(Activation caller, Object value) {
        try {
            Object raw = PhpValues.unwrap(value);
            if (!(raw instanceof PhpValues.PhpObject object)) throw new PhpError("Error", "__clone method called on non-object");
            if (object.descriptor instanceof RuntimeClass type && type.definition.kind() == Ir.TypeKind.ENUM)
                throw new PhpError("Error", "Trying to clone an uncloneable object of class " + type.definition.name());
            return caller.track(PhpValues.own(object.copyObject()));
        } finally { PhpValues.drop(value); }
    }

    private static void initializeObject(Request request, PhpValues.PhpObject object, RuntimeClass type) {
        if (type.parent != null) initializeObject(request, object, type.parent);
        for (var property : type.properties) {
            if (!property.shared()) initialize(request, object, type.definition.name, property);
        }
    }

    private static void initialize(Request request, PhpValues.PhpObject object, String owner, Ir.PropertyDeclaration property) {
        var location = object.field(storageName(owner, property));
        if (property.value() != null || property.type() == null) {
            Object value = property.value() == null ? null : constant(request, property.value(), owner);
            try { location.set(property.type() == null ? PhpValues.unwrap(value) : checkType(PhpValues.unwrap(value), TypeRelations.contextual(property.type(), request, owner, owner))); }
            finally { PhpValues.drop(value); }
        }
        object.fieldType(storageName(owner, property), TypeRelations.contextual(property.type(), request, owner, owner));
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
            for (var property : lexical.properties) {
                if (!property.shared() && property.visibility().equals("private") && property.name().equals(name)) {
                    return object.field(storageName(lexical.definition.name, property));
                }
            }
        }
        for (var current = type; current != null; current = current.parent) {
            for (var property : current.properties) {
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
        var resolved = resolve(caller, className);
        if (resolved.definition.kind == Ir.TypeKind.TRAIT) {
            Diagnostics.deprecated(caller, "Accessing static trait property " + resolved.definition.name + "::$" + name
                    + " is deprecated, it should only be accessed on a class using the trait");
            resolved.initializeStatics(caller.request);
        }
        for (var type = resolved; type != null; type = type.parent) {
            for (var property : type.properties) {
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
        throw new PhpError("Error", "Cannot access " + visibility + " member of " + owner.definition.name);
    }

    @TruffleBoundary
    public static Invocation method(Activation caller, Object value, String name, boolean constructor) {
        if (constructor && name.equals("__clone") && PhpValues.unwrap(value) instanceof PhpValues.PhpObject closure
                && closure.descriptor instanceof ClosureData) return null;
        if (!(PhpValues.unwrap(value) instanceof PhpValues.PhpObject object)
                || !(object.descriptor instanceof RuntimeClass type)) throw new PhpError("Method call requires an object");
        var method = type.method(name);
        if (!constructor && caller.function.owner() != null && type.isA(caller.function.owner())) {
            var lexical = caller.request.type(caller.function.owner()).methods.get(name.toLowerCase(Locale.ROOT));
            if (lexical != null && lexical.visibility.equals("private")) method = lexical;
        }
        if (method == null && constructor) return null;
        if (method == null) throw new PhpError("Undefined method " + type.definition.name + "::" + name);
        if (method.abstractMethod) throw new PhpError("Error", "Cannot call abstract method " + method.function.owner() + "::" + name);
        access(caller, caller.request.type(method.function.owner()), method.visibility);
        return new Invocation(method.function, method.shared ? null : object, type, null,
                method.function.builtin() ? Diagnostics.site(caller) : null);
    }

    @TruffleBoundary
    public static Invocation staticMethod(Activation caller, String className, String name) {
        var type = resolve(caller, className);
        var method = type.method(name);
        if (method == null) throw new PhpError("Undefined method " + className + "::" + name);
        if (method.abstractMethod) throw new PhpError("Error", "Cannot call abstract method " + method.function.owner() + "::" + name);
        access(caller, caller.request.type(method.function.owner()), method.visibility);
        PhpValues.PhpObject receiver = null;
        if (!method.shared) {
            Object value = caller.variable("this").readOrNull();
            if (!(value instanceof PhpValues.PhpObject object) || !(object.descriptor instanceof RuntimeClass receiverType)
                    || !receiverType.isA(type.definition.name)) throw new PhpError("Non-static method requires an object");
            receiver = object;
        }
        var called = List.of("self", "parent", "static").contains(className) && caller.calledClass != null ? caller.calledClass : type;
        return new Invocation(method.function, receiver, called, null, method.function.builtin() ? Diagnostics.site(caller) : null);
    }

    @TruffleBoundary
    public static Object closure(Activation caller, Function function, List<Ir.Capture> captures, boolean arrow) {
        if (function.owner() != null && caller.function.owner() != null && !function.owner().equals(caller.function.owner()))
            function = new Function(function.name(), function.parameters(), function.target(), function.file(), caller.function.owner(), function.returnType(), function.builtin(), function.strictTypes(), function.declaration());
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
            if (name.startsWith("\\")) name = name.substring(1);
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
            if (invocation.calledClass != null && invocation.calledClass.definition.kind == Ir.TypeKind.TRAIT
                    && invocation.environment == null && invocation.receiver == null) {
                Diagnostics.deprecated(caller, "Calling static trait method " + invocation.calledClass.definition.name + "::" + invocation.function.name()
                        + " is deprecated, it should only be called on a class using the trait");
            }
            var child = new Activation(caller.request, invocation.function, caller.task, false, args, Diagnostics.origin(caller));
            bind(child, invocation);
            return Operations.executeChild(caller, child, call);
        } finally { for (var argument : args) argument.close(); }
    }

    public static void bind(Activation child, Invocation invocation) {
        child.calledClass = invocation.calledClass;
        child.builtinSite = invocation.site;
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
    public static Object constant(Request request, Ir.Expression expression) { return constant(request, expression, null); }

    @TruffleBoundary
    public static Object constant(Request request, Ir.Expression expression, String lexicalClass) {
        return switch (expression) {
            case Ir.Literal literal -> literal.value();
            case Ir.EnumCaseValue value -> request.type(lexicalClass).enumState.caseObject(request, value.name());
            case Ir.Property property -> {
                Object receiver = constant(request, property.object(), lexicalClass);
                try {
                    Object raw = PhpValues.unwrap(receiver);
                    if (!(raw instanceof PhpValues.PhpObject object) || !(object.descriptor instanceof RuntimeClass type)
                            || type.enumState == null || !List.of("name", "value").contains(property.name()))
                        throw new PhpError("Error", "Only enum properties are allowed in constant expressions");
                    yield PhpValues.own(object.field(property.name()).read());
                } finally { PhpValues.drop(receiver); }
            }
            case Ir.Index index -> {
                Object source = constant(request, index.array(), lexicalClass);
                Object key = null;
                try {
                    if (index.key() == null) throw new PhpError("Error", "Cannot append in a constant expression");
                    key = constant(request, index.key(), lexicalClass);
                    yield PhpValues.element(source, PhpValues.unwrap(key));
                } finally { PhpValues.drop(source); PhpValues.drop(key); }
            }
            case Ir.Unary unary -> {
                Object operand = constant(request, unary.value(), lexicalClass);
                try {
                    if (unary.operator().equals("!")) yield !Operations.truth(operand);
                    var number = Operations.number(operand);
                    if (unary.operator().equals("+")) yield number;
                    if (number instanceof Long value && value != Long.MIN_VALUE) yield -value;
                    yield -number.doubleValue();
                } finally { PhpValues.drop(operand); }
            }
            case Ir.Binary binary -> {
                Object left = constant(request, binary.left(), lexicalClass);
                try {
                    if (binary.operator().equals("&&") && !Operations.truth(left)) yield false;
                    if (binary.operator().equals("||") && Operations.truth(left)) yield true;
                    Object right = constant(request, binary.right(), lexicalClass);
                    if (binary.operator().equals("&&") || binary.operator().equals("||")) {
                        try { yield Operations.truth(right); } finally { PhpValues.drop(right); }
                    }
                    yield Operations.binary(binary.operator(), left, right);
                } finally { PhpValues.drop(left); }
            }
            case Ir.Coalesce coalesce -> {
                Object left = constant(request, coalesce.left(), lexicalClass);
                if (PhpValues.unwrap(left) != null) yield left;
                PhpValues.drop(left);
                yield constant(request, coalesce.right(), lexicalClass);
            }
            case Ir.Conditional conditional -> {
                Object condition = constant(request, conditional.condition(), lexicalClass);
                try {
                    if (!Operations.truth(condition)) yield constant(request, conditional.no(), lexicalClass);
                    yield conditional.yes() == null ? PhpValues.own(PhpValues.unwrap(condition)) : constant(request, conditional.yes(), lexicalClass);
                } finally { PhpValues.drop(condition); }
            }
            case Ir.ArrayLiteral literal -> {
                try (var scope = new PhpValues.Scope(request.heap)) {
                    var array = scope.variable(scope.emptyArray());
                    for (var entry : literal.entries()) {
                        if (entry.reference()) throw new PhpError("References are not allowed in constant expressions");
                        if (entry.unpack()) {
                            Object value = constant(request, entry.value(), lexicalClass);
                            try {
                                Object source = PhpValues.unwrap(value);
                                if (!(source instanceof PhpValues.PhpArray))
                                    throw new PhpError("Error", "Only arrays can be unpacked in constant expressions");
                                for (Object key : PhpValues.keys(source)) {
                                    var destination = key instanceof Long ? array.append() : array.element(key);
                                    PhpValues.copyElement(source, key, destination);
                                }
                            } finally { PhpValues.drop(value); }
                            continue;
                        }
                        Object key = entry.key() == null ? null : constant(request, entry.key(), lexicalClass);
                        Object value = constant(request, entry.value(), lexicalClass);
                        try { (entry.key() == null ? array.append() : array.element(PhpValues.unwrap(key))).set(PhpValues.unwrap(value)); }
                        finally { PhpValues.drop(key); PhpValues.drop(value); }
                    }
                    yield PhpValues.own(array.read());
                }
            }
            case Ir.Constant constant -> constant.name().equals("__CLASS__") ? (lexicalClass == null ? "" : lexicalClass) : namedConstant(constant.name());
            case Ir.ClassName name -> TypeRelations.contextual(name.type(), request, lexicalClass, lexicalClass);
            case Ir.ClassConstant constant -> {
                if (!(constant.type() instanceof Ir.Literal literal) || !(literal.value() instanceof String name))
                    throw new PhpError("Error", "Dynamic class name in constant expression");
                String type = TypeRelations.contextual(name, request, lexicalClass, lexicalClass);
                if (constant.name().equals("class")) yield type;
                var runtimeClass = request.type(type);
                var member = runtimeClass.constants.get(constant.name());
                if (member == null) throw new PhpError("Error", "Undefined constant " + type + "::" + constant.name());
                if (!member.declaration.visibility().equals("public") && (lexicalClass == null
                        || !lexicalClass.equalsIgnoreCase(member.owner) && (member.declaration.visibility().equals("private")
                        || !request.type(lexicalClass).isA(member.owner) && !request.type(member.owner).isA(lexicalClass))))
                    throw new PhpError("Error", "Cannot access class constant " + type + "::" + constant.name());
                yield runtimeClass.constant(request, constant.name());
            }
            default -> throw new PhpError("Unsupported constant expression");
        };
    }

    public static Object namedConstant(String name) {
        if (Diagnostics.CONSTANTS.containsKey(name)) return Diagnostics.CONSTANTS.get(name);
        if (CurlApi.CONSTANTS.containsKey(name)) return CurlApi.CONSTANTS.get(name);
        if (CurlMultiApi.CONSTANTS.containsKey(name)) return CurlMultiApi.CONSTANTS.get(name);
        if (SqliteApi.CONSTANTS.containsKey(name)) return SqliteApi.CONSTANTS.get(name);
        return switch (name) {
            case "PHP_VERSION" -> "8.6.0-graalphp";
            case "PHP_INT_MAX" -> Long.MAX_VALUE;
            case "PHP_INT_MIN" -> Long.MIN_VALUE;
            case "PHP_INT_SIZE" -> 8L;
            case "COUNT_NORMAL" -> 0L;
            case "COUNT_RECURSIVE" -> 1L;
            case "PHP_EOL" -> System.lineSeparator();
            case "DIRECTORY_SEPARATOR" -> java.io.File.separator;
            default -> throw new PhpError("Undefined constant " + name);
        };
    }

    @TruffleBoundary
    public static Object checkType(Object value, String declaredType) { return TypeRelations.check(value, declaredType); }

    @TruffleBoundary
    public static String className(Object value) {
        value = PhpValues.unwrap(value);
        if (value instanceof PhpValues.PhpObject object)
            return object.descriptor instanceof RuntimeClass type ? type.definition.name : object.descriptor instanceof ClosureData ? "Closure" : null;
        String name = AsyncApi.className(value);
        if (name == null) name = NetworkApi.className(value);
        if (name == null) name = SqliteApi.className(value);
        if (value instanceof CurlApi.Handle) return "CurlHandle";
        if (value instanceof CurlMultiApi.Handle) return "CurlMultiHandle";
        if (value instanceof FfiApi.Binding) return "FFI";
        return name;
    }

    @TruffleBoundary
    public static Object readClassConstant(Activation caller, Object value, String name) {
        try {
            Object raw = PhpValues.unwrap(value);
            String className = raw instanceof String text ? text : className(raw);
            if (className == null) throw new PhpError("Error", "Class constant access requires an object or class name");
            if (name.equals("class")) {
                if (raw instanceof String) throw new PhpError("TypeError", "Cannot use \"::class\" on string");
                return className;
            }
            var type = resolve(caller, className);
            if (type.definition.kind == Ir.TypeKind.TRAIT) throw new PhpError("Error", "Cannot access a trait constant directly");
            var member = type.constants.get(name);
            if (member == null) throw new PhpError("Error", "Undefined constant " + className + "::" + name);
            access(caller, caller.request.type(member.owner), member.declaration.visibility());
            return caller.track(type.constant(caller.request, name));
        } finally { PhpValues.drop(value); }
    }

    @TruffleBoundary
    public static boolean instanceOf(Activation caller, Object value, Object type) {
        try {
            Object rawType = PhpValues.unwrap(type);
            String name = rawType instanceof String text ? text : className(rawType);
            if (name == null) throw new PhpError("Error", "instanceof requires a class name or an object");
            name = TypeRelations.contextual(name, caller.request, caller.function.owner(),
                    caller.calledClass == null ? caller.function.owner() : caller.calledClass.definition.name);
            if (name.startsWith("\\")) name = name.substring(1);
            Object raw = PhpValues.unwrap(value);
            if (raw instanceof PhpValues.PhpObject object && object.descriptor instanceof RuntimeClass runtimeClass)
                return runtimeClass.isA(name);
            if (raw instanceof PhpError error) return error.matches(name);
            String actual = className(raw);
            return actual != null && actual.equalsIgnoreCase(name);
        } finally { PhpValues.drop(value); PhpValues.drop(type); }
    }
}
