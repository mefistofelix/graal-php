package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import graalphp.frontend.Ir;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static graalphp.runtime.Execution.*;

/** Reflection objects expose immutable declaration metadata without moving it into request-global state. */
public final class ReflectionApi {
    private ReflectionApi() {}

    public static final long IS_INSTANCEOF = 2;
    private static final long TARGET_CLASS = 1;
    private static final long TARGET_FUNCTION = 2;
    private static final long TARGET_METHOD = 4;
    private static final long TARGET_PROPERTY = 8;
    private static final long TARGET_CLASS_CONSTANT = 16;
    private static final long TARGET_PARAMETER = 32;
    private static final long TARGET_ALL = 63;
    private static final long IS_REPEATABLE = 64;

    public static final String TYPES = """
        interface Reflector {}
        abstract class ReflectionFunctionAbstract implements Reflector {}
        class ReflectionFunction extends ReflectionFunctionAbstract {}
        class ReflectionMethod extends ReflectionFunctionAbstract {}
        class ReflectionClass implements Reflector {}
        class ReflectionObject extends ReflectionClass {}
        class ReflectionProperty implements Reflector {}
        class ReflectionClassConstant implements Reflector {}
        class ReflectionParameter implements Reflector {}
        final class ReflectionAttribute implements Reflector { public const IS_INSTANCEOF = 2; }
        class ReflectionException {}
        """;

    public static final String SOURCE = """
        function reflection_set_class($state, $class) {
            __class_require($class);
            return __reflection_set_class($state, $class);
        }
        function reflection_set_member($state, $class, $member) {
            __class_require($class);
            return __reflection_set_member($state, $class, $member);
        }
        function reflection_filter_attributes($attributes, $name) {
            __class_require($name);
            $result = [];
            foreach ($attributes as $attribute) {
                $class = $attribute->getName();
                __class_require($class);
                if (is_a($class, $name, true)) $result[] = $attribute;
            }
            return $result;
        }
        function reflection_attribute_new($attribute) {
            $class = $attribute->getName();
            __class_require($class);
            __reflection_validate_attribute($attribute);
            $arguments = $attribute->getArguments();
            return new $class(...$arguments);
        }
        """;

    public interface Value extends TruffleObject {
        String phpClass();
    }

    public static final class ClassValue implements Value {
        private final String reflectionClass;
        String name;
        ClassValue(String reflectionClass) { this.reflectionClass = reflectionClass; }
        @Override public String phpClass() { return reflectionClass; }
    }

    public static final class FunctionValue implements Value {
        private final String reflectionClass;
        Function function;
        String declaringClass;
        FunctionValue(String reflectionClass) { this.reflectionClass = reflectionClass; }
        FunctionValue(String reflectionClass, Function function, String declaringClass) {
            this.reflectionClass = reflectionClass;
            this.function = function;
            this.declaringClass = declaringClass;
        }
        @Override public String phpClass() { return reflectionClass; }
    }

    public static final class PropertyValue implements Value {
        String className;
        String owner;
        Ir.PropertyDeclaration property;
        @Override public String phpClass() { return "ReflectionProperty"; }
    }

    public static final class ConstantValue implements Value {
        String className;
        String owner;
        Ir.ClassConstantDeclaration constant;
        @Override public String phpClass() { return "ReflectionClassConstant"; }
    }

    public static final class ParameterValue implements Value {
        Function function;
        int index;
        ParameterValue(Function function, int index) { this.function = function; this.index = index; }
        @Override public String phpClass() { return "ReflectionParameter"; }
    }

    public static final class AttributeValue implements Value {
        final String lexicalClass;
        final Ir.Attribute attribute;
        final long target;
        final boolean repeated;
        AttributeValue(String lexicalClass, Ir.Attribute attribute, long target, boolean repeated) {
            this.lexicalClass = lexicalClass;
            this.attribute = attribute;
            this.target = target;
            this.repeated = repeated;
        }
        @Override public String phpClass() { return "ReflectionAttribute"; }
    }

    public static Object allocate(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "reflectionclass" -> new ClassValue("ReflectionClass");
            case "reflectionobject" -> new ClassValue("ReflectionObject");
            case "reflectionfunction" -> new FunctionValue("ReflectionFunction");
            case "reflectionmethod" -> new FunctionValue("ReflectionMethod");
            case "reflectionproperty" -> new PropertyValue();
            case "reflectionclassconstant" -> new ConstantValue();
            case "reflectionattribute" -> new AttributeValue(null, new Ir.Attribute("", List.of(), 0, 0), 0, false);
            default -> AsyncApi.UNHANDLED;
        };
    }

    public static String className(Object value) {
        value = PhpValues.unwrap(value);
        return value instanceof Value reflection ? reflection.phpClass() : null;
    }

    public static boolean isA(Object value, String type) {
        String actual = className(value);
        if (actual == null) return false;
        if (actual.equalsIgnoreCase(type)) return true;
        if (type.equalsIgnoreCase("Reflector")) return true;
        return type.equalsIgnoreCase("ReflectionFunctionAbstract")
                && (actual.equalsIgnoreCase("ReflectionFunction") || actual.equalsIgnoreCase("ReflectionMethod"))
                || type.equalsIgnoreCase("ReflectionClass") && actual.equalsIgnoreCase("ReflectionObject");
    }

    @TruffleBoundary
    public static Object method(Activation caller, Object receiver, String name, Argument[] arguments, IndirectCallNode call) {
        Object raw = PhpValues.unwrap(receiver);
        if (!(raw instanceof Value reflection)) return AsyncApi.UNHANDLED;
        String method = name.toLowerCase(Locale.ROOT);
        if (method.equals("__construct")) return construct(caller, reflection, arguments, call);
        if (raw instanceof ClassValue value) return classMethod(caller, value, method, arguments, call);
        if (raw instanceof FunctionValue value) return functionMethod(caller, value, method, arguments, call);
        if (raw instanceof PropertyValue value) return propertyMethod(caller, value, method, arguments, call);
        if (raw instanceof ConstantValue value) return constantMethod(caller, value, method, arguments, call);
        if (raw instanceof ParameterValue value) return parameterMethod(caller, value, method, arguments, call);
        if (raw instanceof AttributeValue value) return attributeMethod(caller, value, method, arguments, call);
        return AsyncApi.UNHANDLED;
    }

    private static Object construct(Activation caller, Value value, Argument[] arguments, IndirectCallNode call) {
        if (value instanceof AttributeValue) throw new PhpError("Error", "Cannot directly instantiate ReflectionAttribute");
        if (value instanceof ClassValue reflection) {
            var args = CallArguments.builtin(reflection.phpClass(), arguments, List.of("objectOrClass"), 1);
            Object target = PhpValues.unwrap(args[0].value());
            if (reflection.phpClass().equals("ReflectionObject")) {
                String name = ObjectModel.className(target);
                if (name == null || target instanceof String)
                    throw new PhpError("ReflectionException", "ReflectionObject expects an object");
                reflection.name = name;
                return null;
            }
            String className = target instanceof String text ? clean(text) : ObjectModel.className(target);
            if (className == null) throw new PhpError("ReflectionException", "ReflectionClass expects an object or class name");
            return Operations.invokeFunction(caller, caller.request.context.asyncFunction("reflection_set_class"),
                    new Argument[] {new Argument(reflection, null), new Argument(className, null)}, call);
        }
        if (value instanceof FunctionValue reflection) {
            if (reflection.phpClass().equals("ReflectionFunction")) {
                var args = CallArguments.builtin("ReflectionFunction", arguments, List.of("function"), 1);
                setFunction(caller, reflection, PhpValues.unwrap(args[0].value()));
                return null;
            }
            var args = CallArguments.builtin("ReflectionMethod", arguments, List.of("objectOrClass", "method"), 2);
            String className = targetClass(args[0].value());
            if (className == null) throw new PhpError("ReflectionException", "ReflectionMethod expects a class or object");
            return Operations.invokeFunction(caller, caller.request.context.asyncFunction("reflection_set_member"),
                    new Argument[] {new Argument(reflection, null), new Argument(className, null),
                            new Argument(Operations.string(args[1].value()), null)}, call);
        }
        if (value instanceof PropertyValue || value instanceof ConstantValue) {
            String type = value instanceof PropertyValue ? "ReflectionProperty" : "ReflectionClassConstant";
            var args = CallArguments.builtin(type, arguments, List.of("class", "name"), 2);
            String className = targetClass(args[0].value());
            if (className == null) throw new PhpError("ReflectionException", type + " expects a class or object");
            return Operations.invokeFunction(caller, caller.request.context.asyncFunction("reflection_set_member"),
                    new Argument[] {new Argument(value, null), new Argument(className, null),
                            new Argument(Operations.string(args[1].value()), null)}, call);
        }
        throw new PhpError("ReflectionException", "Unsupported reflection constructor");
    }

    private static void setFunction(Activation caller, FunctionValue reflection, Object value) {
        value = PhpValues.unwrap(value);
        if (value instanceof String name) {
            var function = caller.request.functions.get(name.toLowerCase(Locale.ROOT));
            if (function == null) throw new PhpError("ReflectionException", "Function " + name + "() does not exist");
            reflection.function = function;
            reflection.declaringClass = null;
            return;
        }
        if (value instanceof PhpValues.PhpObject object && object.descriptor instanceof ObjectModel.ClosureData closure) {
            reflection.function = closure.function();
            reflection.declaringClass = closure.function().owner();
            return;
        }
        throw new PhpError("ReflectionException", "ReflectionFunction expects a function name or Closure");
    }

    private static Object classMethod(Activation caller, ClassValue reflection, String method, Argument[] arguments, IndirectCallNode call) {
        requireInitialized(reflection.name, reflection.phpClass());
        var type = caller.request.type(reflection.name);
        return switch (method) {
            case "getname" -> empty(arguments, method, reflection.name);
            case "getshortname" -> empty(arguments, method, shortName(reflection.name));
            case "getnamespacename" -> empty(arguments, method, namespace(reflection.name));
            case "innamespace" -> empty(arguments, method, reflection.name.contains("\\"));
            case "isinterface" -> empty(arguments, method, type.definition.kind() == Ir.TypeKind.INTERFACE);
            case "istrait" -> empty(arguments, method, type.definition.kind() == Ir.TypeKind.TRAIT);
            case "isenum" -> empty(arguments, method, type.definition.kind() == Ir.TypeKind.ENUM);
            case "isabstract" -> empty(arguments, method, type.definition.abstractType());
            case "isfinal" -> empty(arguments, method, type.definition.finalType() || type.definition.kind() == Ir.TypeKind.ENUM);
            case "isinternal" -> empty(arguments, method, false);
            case "isuserdefined" -> empty(arguments, method, true);
            case "getattributes" -> attributes(caller, type.definition.attributes(), reflection.name, TARGET_CLASS, arguments, call);
            case "getmethod" -> {
                var args = CallArguments.builtin(method, arguments, List.of("name"), 1);
                String name = Operations.string(args[0].value());
                var member = type.method(name);
                if (member == null) throw new PhpError("ReflectionException", "Method " + reflection.name + "::" + name + "() does not exist");
                yield new FunctionValue("ReflectionMethod", member.function(), member.function().owner());
            }
            case "hasmethod" -> {
                var args = CallArguments.builtin(method, arguments, List.of("name"), 1);
                yield type.method(Operations.string(args[0].value())) != null;
            }
            case "getmethods" -> {
                CallArguments.builtin(method, arguments, List.of("filter"), 0, (Object) null);
                var methods = new LinkedHashMap<String, ObjectModel.Method>();
                for (var current = type; current != null; current = current.parent)
                    for (var entry : current.methods.entrySet()) methods.putIfAbsent(entry.getKey(), entry.getValue());
                var values = new ArrayList<Object>();
                for (var member : methods.values())
                    values.add(new FunctionValue("ReflectionMethod", member.function(), member.function().owner()));
                yield array(caller, values);
            }
            case "getproperty" -> {
                var args = CallArguments.builtin(method, arguments, List.of("name"), 1);
                yield property(type, Operations.string(args[0].value()), reflection.name);
            }
            case "hasproperty" -> {
                var args = CallArguments.builtin(method, arguments, List.of("name"), 1);
                yield findProperty(type, Operations.string(args[0].value())) != null;
            }
            case "getproperties" -> {
                CallArguments.builtin(method, arguments, List.of("filter"), 0, (Object) null);
                var values = new ArrayList<Object>();
                var seen = new java.util.HashSet<String>();
                for (var current = type; current != null; current = current.parent) {
                    for (var property : current.properties) {
                        if (seen.add(property.name())) values.add(property(current, property, reflection.name));
                    }
                }
                yield array(caller, values);
            }
            case "getreflectionconstant" -> {
                var args = CallArguments.builtin(method, arguments, List.of("name"), 1);
                var member = type.constants.get(Operations.string(args[0].value()));
                yield member == null ? false : constant(reflection.name, member);
            }
            case "getreflectionconstants" -> {
                CallArguments.builtin(method, arguments, List.of("filter"), 0, (Object) null);
                var values = new ArrayList<Object>();
                for (var member : type.constants.values()) values.add(constant(reflection.name, member));
                yield array(caller, values);
            }
            case "getconstructor" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                var constructor = type.method("__construct");
                yield constructor == null ? false : new FunctionValue("ReflectionMethod", constructor.function(), constructor.function().owner());
            }
            case "getparentclass" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                yield type.parent == null ? false : initializedClass("ReflectionClass", type.parent.definition.name());
            }
            case "getinterfacenames" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                var names = new ArrayList<Object>();
                collectInterfaces(type, names, new java.util.HashSet<>());
                yield array(caller, names);
            }
            default -> throw undefined(reflection.phpClass(), method);
        };
    }

    private static Object functionMethod(Activation caller, FunctionValue reflection, String method, Argument[] arguments, IndirectCallNode call) {
        if (reflection.function == null) throw new PhpError("ReflectionException", reflection.phpClass() + " is not initialized");
        var function = reflection.function;
        return switch (method) {
            case "getname" -> {
                String reflectedName = function.name();
                if (reflection.phpClass().equals("ReflectionFunction") && reflectedName.startsWith("{closure#")) {
                    var declaration = declaration(function);
                    reflectedName = function.file() == null || declaration.section() == null ? "{closure}"
                            : "{closure:" + function.file() + ":" + declaration.section().getStartLine() + "}";
                }
                yield empty(arguments, method, reflectedName);
            }
            case "isclosure" -> empty(arguments, method, function.name().startsWith("{closure#"));
            case "isinternal" -> empty(arguments, method, function.builtin());
            case "isuserdefined" -> empty(arguments, method, !function.builtin());
            case "isstatic" -> empty(arguments, method, reflection.phpClass().equals("ReflectionMethod")
                    && caller.request.type(reflection.declaringClass).method(function.name()).shared());
            case "getdeclaringclass" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                yield reflection.declaringClass == null ? null : initializedClass("ReflectionClass", reflection.declaringClass);
            }
            case "getattributes" -> attributes(caller, declaration(function).attributes(), function.owner(),
                    reflection.phpClass().equals("ReflectionMethod") ? TARGET_METHOD : TARGET_FUNCTION, arguments, call);
            case "getparameters" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                var values = new ArrayList<Object>();
                for (int i = 0; i < function.parameters().size(); i++) values.add(new ParameterValue(function, i));
                yield array(caller, values);
            }
            case "getnumberofparameters" -> empty(arguments, method, (long) function.parameters().size());
            case "getnumberofrequiredparameters" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                long required = function.parameters().stream().filter(parameter -> parameter.defaultValue() == null && !parameter.variadic()).count();
                yield required;
            }
            case "isvariadic" -> empty(arguments, method, !function.parameters().isEmpty() && function.parameters().getLast().variadic());
            case "isgenerator" -> empty(arguments, method, function.generator());
            case "returnsreference" -> empty(arguments, method, function.returnsReference());
            case "hasreturntype" -> empty(arguments, method, function.returnType() != null);
            case "getfilename" -> empty(arguments, method, function.file() == null ? false : function.file().toString());
            case "getstartline" -> empty(arguments, method, declaration(function).section() == null ? false : (long) declaration(function).section().getStartLine());
            case "getendline" -> empty(arguments, method, declaration(function).section() == null ? false : (long) declaration(function).section().getEndLine());
            default -> throw undefined(reflection.phpClass(), method);
        };
    }

    private static Object propertyMethod(Activation caller, PropertyValue reflection, String method, Argument[] arguments, IndirectCallNode call) {
        requireInitialized(reflection.property == null ? null : reflection.className, "ReflectionProperty");
        return switch (method) {
            case "getname" -> empty(arguments, method, reflection.property.name());
            case "getdeclaringclass" -> empty(arguments, method, initializedClass("ReflectionClass", reflection.owner));
            case "isstatic" -> empty(arguments, method, reflection.property.shared());
            case "ispublic" -> empty(arguments, method, reflection.property.visibility().equals("public"));
            case "isprotected" -> empty(arguments, method, reflection.property.visibility().equals("protected"));
            case "isprivate" -> empty(arguments, method, reflection.property.visibility().equals("private"));
            case "getattributes" -> attributes(caller, reflection.property.attributes(), reflection.owner, TARGET_PROPERTY, arguments, call);
            default -> throw undefined("ReflectionProperty", method);
        };
    }

    private static Object constantMethod(Activation caller, ConstantValue reflection, String method, Argument[] arguments, IndirectCallNode call) {
        requireInitialized(reflection.constant == null ? null : reflection.className, "ReflectionClassConstant");
        return switch (method) {
            case "getname" -> empty(arguments, method, reflection.constant.name());
            case "getdeclaringclass" -> empty(arguments, method, initializedClass("ReflectionClass", reflection.owner));
            case "getvalue" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                yield caller.track(caller.request.type(reflection.className).constant(caller.request, reflection.constant.name()));
            }
            case "ispublic" -> empty(arguments, method, reflection.constant.visibility().equals("public"));
            case "isprotected" -> empty(arguments, method, reflection.constant.visibility().equals("protected"));
            case "isprivate" -> empty(arguments, method, reflection.constant.visibility().equals("private"));
            case "isfinal" -> empty(arguments, method, reflection.constant.finalConstant());
            case "getattributes" -> attributes(caller, reflection.constant.attributes(), reflection.owner, TARGET_CLASS_CONSTANT, arguments, call);
            default -> throw undefined("ReflectionClassConstant", method);
        };
    }

    private static Object parameterMethod(Activation caller, ParameterValue reflection, String method, Argument[] arguments, IndirectCallNode call) {
        var parameter = reflection.function.parameters().get(reflection.index);
        return switch (method) {
            case "getname" -> empty(arguments, method, parameter.name());
            case "getposition" -> empty(arguments, method, (long) reflection.index);
            case "isoptional" -> empty(arguments, method, parameter.defaultValue() != null || parameter.variadic());
            case "isvariadic" -> empty(arguments, method, parameter.variadic());
            case "ispassedbyreference" -> empty(arguments, method, parameter.reference());
            case "getattributes" -> attributes(caller, parameter.attributes(), reflection.function.owner(), TARGET_PARAMETER, arguments, call);
            case "getdeclaringfunction" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                yield new FunctionValue(reflection.function.owner() == null ? "ReflectionFunction" : "ReflectionMethod",
                        reflection.function, reflection.function.owner());
            }
            case "getdeclaringclass" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                yield reflection.function.owner() == null ? null : initializedClass("ReflectionClass", reflection.function.owner());
            }
            case "isdefaultvalueavailable" -> empty(arguments, method, parameter.defaultValue() != null);
            case "getdefaultvalue" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                if (parameter.defaultValue() == null) throw new PhpError("ReflectionException", "Internal error: Failed to retrieve the default value");
                yield caller.track(ObjectModel.constant(caller.request, parameter.defaultValue(), reflection.function.owner()));
            }
            default -> throw undefined("ReflectionParameter", method);
        };
    }

    private static Object attributeMethod(Activation caller, AttributeValue reflection, String method, Argument[] arguments, IndirectCallNode call) {
        return switch (method) {
            case "getname" -> empty(arguments, method, reflection.attribute.name());
            case "gettarget" -> empty(arguments, method, reflection.target);
            case "isrepeated" -> empty(arguments, method, reflection.repeated);
            case "getarguments" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                yield attributeArguments(caller, reflection);
            }
            case "newinstance" -> {
                CallArguments.builtin(method, arguments, List.of(), 0);
                yield Operations.invokeFunction(caller, caller.request.context.asyncFunction("reflection_attribute_new"),
                        new Argument[] {new Argument(reflection, null)}, call);
            }
            default -> throw undefined("ReflectionAttribute", method);
        };
    }

    private static Object attributes(Activation caller, List<Ir.Attribute> metadata, String lexicalClass, long target,
                                     Argument[] arguments, IndirectCallNode call) {
        var args = CallArguments.builtin("getAttributes", arguments, List.of("name", "flags"), 0, null, 0L);
        Object filterValue = PhpValues.unwrap(args[0].value());
        String filter = filterValue == null ? null : clean(Operations.string(filterValue));
        long flags = Operations.number(args[1].value()).longValue();
        if (flags != 0 && flags != IS_INSTANCEOF)
            throw new PhpError("ValueError", "ReflectionAttribute flags must be 0 or ReflectionAttribute::IS_INSTANCEOF");
        if (flags == IS_INSTANCEOF && filter == null)
            throw new PhpError("ValueError", "ReflectionAttribute::IS_INSTANCEOF requires an attribute class name");
        var values = new ArrayList<Object>();
        for (var attribute : metadata) {
            if (flags == 0 && filter != null && !attribute.name().equalsIgnoreCase(filter)) continue;
            long count = metadata.stream().filter(other -> other.name().equalsIgnoreCase(attribute.name())).count();
            values.add(new AttributeValue(lexicalClass, attribute, target, count > 1));
        }
        Object list = array(caller, values);
        if (flags == 0) return list;
        return Operations.invokeFunction(caller, caller.request.context.asyncFunction("reflection_filter_attributes"),
                new Argument[] {new Argument(list, null), new Argument(filter, null)}, call);
    }

    private static Object attributeArguments(Activation caller, AttributeValue reflection) {
        try (var scope = new PhpValues.Scope(caller.request.heap)) {
            var result = scope.variable(scope.emptyArray());
            for (var argument : reflection.attribute.arguments()) {
                String name = null;
                Ir.Expression expression = argument;
                if (argument instanceof Ir.NamedArgument named) {
                    name = named.name();
                    expression = named.value();
                }
                Object value = ObjectModel.constant(caller.request, expression, reflection.lexicalClass);
                try {
                    if (name == null) result.append().set(PhpValues.unwrap(value));
                    else result.element(name).set(PhpValues.unwrap(value));
                } finally { PhpValues.drop(value); }
            }
            return caller.track(PhpValues.own(result.read()));
        }
    }

    public static Object function(Activation caller, String name, Argument[] arguments) {
        return switch (name) {
            case "__reflection_set_class" -> {
                var reflection = (ClassValue) arguments[0].value();
                String className = clean(Operations.string(arguments[1].value()));
                caller.request.type(className);
                reflection.name = className;
                yield null;
            }
            case "__reflection_set_member" -> {
                Value value = (Value) arguments[0].value();
                String className = clean(Operations.string(arguments[1].value()));
                String member = Operations.string(arguments[2].value());
                var type = caller.request.type(className);
                if (value instanceof FunctionValue reflection) {
                    var method = type.method(member);
                    if (method == null) throw new PhpError("ReflectionException", "Method " + className + "::" + member + "() does not exist");
                    reflection.function = method.function();
                    reflection.declaringClass = method.function().owner();
                } else if (value instanceof PropertyValue reflection) {
                    var found = findProperty(type, member);
                    if (found == null) throw new PhpError("ReflectionException", "Property " + className + "::$" + member + " does not exist");
                    reflection.className = className;
                    reflection.owner = found.owner;
                    reflection.property = found.property;
                } else if (value instanceof ConstantValue reflection) {
                    var constant = type.constants.get(member);
                    if (constant == null) throw new PhpError("ReflectionException", "Constant " + className + "::" + member + " does not exist");
                    reflection.className = className;
                    reflection.owner = constant.owner();
                    reflection.constant = constant.declaration();
                }
                yield null;
            }
            case "__reflection_validate_attribute" -> {
                validateAttribute(caller, (AttributeValue) arguments[0].value());
                yield null;
            }
            default -> AsyncApi.UNHANDLED;
        };
    }

    private static void validateAttribute(Activation caller, AttributeValue attribute) {
        var type = caller.request.type(attribute.attribute.name());
        var marker = type.definition.attributes().stream()
                .filter(value -> value.name().equalsIgnoreCase("Attribute")).findFirst().orElse(null);
        if (marker == null)
            throw new PhpError("Error", "Attempting to use non-attribute class " + attribute.attribute.name() + " as attribute");
        long flags = TARGET_ALL;
        if (!marker.arguments().isEmpty()) {
            Ir.Expression expression = marker.arguments().getFirst();
            if (expression instanceof Ir.NamedArgument named) expression = named.value();
            Object value = ObjectModel.constant(caller.request, expression, type.definition.name());
            try { flags = Operations.number(value).longValue(); }
            finally { PhpValues.drop(value); }
        }
        if ((flags & attribute.target) == 0)
            throw new PhpError("Error", "Attribute \"" + attribute.attribute.name() + "\" cannot target this declaration");
        if (attribute.repeated && (flags & IS_REPEATABLE) == 0)
            throw new PhpError("Error", "Attribute \"" + attribute.attribute.name() + "\" must not be repeated");
    }

    private static Execution.Declaration declaration(Function function) {
        return function.declaration() == null
                ? new Execution.Declaration(null, List.of(), false)
                : function.declaration();
    }

    private static Object empty(Argument[] arguments, String method, Object value) {
        CallArguments.builtin(method, arguments, List.of(), 0);
        return value;
    }

    private static Object array(Activation caller, List<?> values) {
        try (var scope = new PhpValues.Scope(caller.request.heap)) {
            var result = scope.variable(scope.emptyArray());
            for (var value : values) result.append().set(value);
            return caller.track(PhpValues.own(result.read()));
        }
    }

    private static ClassValue initializedClass(String reflectionClass, String name) {
        var value = new ClassValue(reflectionClass);
        value.name = name;
        return value;
    }

    private record FoundProperty(String owner, Ir.PropertyDeclaration property) {}

    private static FoundProperty findProperty(ObjectModel.RuntimeClass type, String name) {
        for (var current = type; current != null; current = current.parent) {
            for (var property : current.properties) {
                if (property.name().equals(name)) return new FoundProperty(current.definition.name(), property);
            }
        }
        return null;
    }

    private static PropertyValue property(ObjectModel.RuntimeClass type, String name, String reflectedClass) {
        var found = findProperty(type, name);
        if (found == null) throw new PhpError("ReflectionException", "Property " + reflectedClass + "::$" + name + " does not exist");
        return property(typeForOwner(type, found.owner), found.property, reflectedClass);
    }

    private static PropertyValue property(ObjectModel.RuntimeClass owner, Ir.PropertyDeclaration declaration, String reflectedClass) {
        var value = new PropertyValue();
        value.className = reflectedClass;
        value.owner = owner.definition.name();
        value.property = declaration;
        return value;
    }

    private static ObjectModel.RuntimeClass typeForOwner(ObjectModel.RuntimeClass type, String owner) {
        for (var current = type; current != null; current = current.parent)
            if (current.definition.name().equalsIgnoreCase(owner)) return current;
        return type;
    }

    private static ConstantValue constant(String reflectedClass, ObjectModel.ClassConstant member) {
        var value = new ConstantValue();
        value.className = reflectedClass;
        value.owner = member.owner();
        value.constant = member.declaration();
        return value;
    }

    private static String targetClass(Object value) {
        value = PhpValues.unwrap(value);
        if (value instanceof String text) return clean(text);
        return ObjectModel.className(value);
    }

    private static String clean(String name) { return name.startsWith("\\") ? name.substring(1) : name; }

    private static String shortName(String name) {
        int index = name.lastIndexOf('\\');
        return index < 0 ? name : name.substring(index + 1);
    }

    private static String namespace(String name) {
        int index = name.lastIndexOf('\\');
        return index < 0 ? "" : name.substring(0, index);
    }

    private static void collectInterfaces(ObjectModel.RuntimeClass type, List<Object> result, java.util.Set<String> seen) {
        for (var contract : type.interfaces) {
            if (seen.add(contract.definition.name().toLowerCase(Locale.ROOT))) result.add(contract.definition.name());
            collectInterfaces(contract, result, seen);
        }
        if (type.parent != null) collectInterfaces(type.parent, result, seen);
    }

    private static void requireInitialized(String value, String reflectionClass) {
        if (value == null) throw new PhpError("ReflectionException", reflectionClass + " is not initialized");
    }

    private static PhpError undefined(String type, String method) {
        return new PhpError("Error", "Call to undefined method " + type + "::" + method + "()");
    }
}
