package graalphp.runtime;

import graalphp.frontend.Ir;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import static graalphp.runtime.Execution.*;
import static graalphp.runtime.ObjectModel.*;

/** Resolves declarations before publication; immutable source definitions are never modified. */
public final class ClassLinker {
    private ClassLinker() {}
    private record Imported(String trait, Method method) {}
    private record DefaultCheck(Ir.Expression left, Ir.Expression right, String owner, String member) {}
    public record Linked(RuntimeClass type, List<DefaultCheck> defaults) {
        public void validateDefaults(Request request) {
            for (var check : defaults) {
                Object left = ObjectModel.constant(request, check.left, check.owner);
                Object right = null;
                try {
                    right = ObjectModel.constant(request, check.right, check.owner);
                    if (!equalValues(PhpValues.unwrap(left), PhpValues.unwrap(right)))
                        throw failure("Incompatible trait member " + check.owner + "::" + check.member);
                } finally { PhpValues.drop(left); PhpValues.drop(right); }
            }
        }
    }

    public static Linked link(Request request, Definition definition) {
        RuntimeClass parent = definition.parent() == null ? null : request.type(definition.parent());
        if (parent != null && (parent.definition.kind() != Ir.TypeKind.CLASS || parent.definition.finalType()))
            throw failure("Class " + definition.name() + " cannot extend " + parent.definition.name());
        var interfaces = new ArrayList<RuntimeClass>();
        var interfaceNames = new java.util.HashSet<String>();
        for (String name : definition.interfaces()) {
            if (!interfaceNames.add(key(name))) throw failure("Repeated interface " + name);
            var type = request.type(name);
            if (type.definition.kind() != Ir.TypeKind.INTERFACE) throw failure(name + " is not an interface");
            if (definition.kind() == Ir.TypeKind.CLASS && type.isA("UnitEnum"))
                throw failure("Non-enum class " + definition.name() + " cannot implement interface " + name);
            if (definition.kind() == Ir.TypeKind.ENUM && definition.backingType() == null && type.isA("BackedEnum"))
                throw failure("Non-backed enum " + definition.name() + " cannot implement BackedEnum");
            interfaces.add(type);
        }
        IterationApi.validate(definition, parent, interfaces);
        var traits = new LinkedHashMap<String, RuntimeClass>();
        for (var use : definition.traits()) for (String name : use.names()) {
            var type = request.type(name);
            if (type.definition.kind() != Ir.TypeKind.TRAIT) throw failure(name + " is not a trait");
            traits.putIfAbsent(key(name), type);
        }
        var properties = new LinkedHashMap<String, Ir.PropertyDeclaration>();
        var constants = new LinkedHashMap<String, ClassConstant>();
        var defaults = new ArrayList<DefaultCheck>();
        for (var property : definition.properties()) {
            if (properties.putIfAbsent(property.name(), property) != null) throw failure("Cannot redeclare property " + property.name());
        }
        for (var constant : definition.constants()) {
            if (definition.kind() == Ir.TypeKind.INTERFACE && !constant.visibility().equals("public"))
                throw failure("Interface constants must be public");
            if (constants.putIfAbsent(constant.name(), new ClassConstant(constant, definition.name())) != null)
                throw failure("Cannot redeclare constant " + constant.name());
        }
        var requirements = new ArrayList<Method>();
        var imported = new LinkedHashMap<String, List<Imported>>();
        for (var entry : traits.entrySet()) {
            var trait = entry.getValue();
            for (var method : trait.methods.entrySet()) {
                imported.computeIfAbsent(method.getKey(), ignored -> new ArrayList<>())
                        .add(new Imported(entry.getKey(), method.getValue().inClass(definition.name())));
            }
            for (var requirement : trait.requirements) requirements.add(requirement.inClass(definition.name()));
            for (var property : trait.properties) {
                var previous = properties.putIfAbsent(property.name(), property);
                if (previous != null) compatibleProperty(request, definition.name(), previous, property, defaults);
            }
            for (var constant : trait.constants.values()) {
                var declaration = constant.declaration();
                var previous = constants.putIfAbsent(declaration.name(), new ClassConstant(declaration, definition.name()));
                if (previous != null) compatibleConstant(previous.declaration(), declaration, definition.name(), defaults);
            }
        }
        var available = new LinkedHashMap<String, List<Imported>>();
        imported.forEach((name, methods) -> available.put(name, new ArrayList<>(methods)));
        // Precedence rules select bodies; aliases may still select an excluded body from the original set.
        for (var use : definition.traits()) for (var adaptation : use.adaptations()) {
            if (adaptation.excluded().isEmpty()) continue;
            selected(imported, adaptation);
            var candidates = available.get(key(adaptation.method()));
            for (String excluded : adaptation.excluded()) {
                if (excluded.equalsIgnoreCase(adaptation.trait())) throw failure("A trait method cannot exclude itself");
                if (!traits.containsKey(key(excluded))) throw failure("Excluded trait is not used: " + excluded);
                candidates.removeIf(candidate -> candidate.trait.equals(key(excluded)));
            }
        }
        var aliases = new LinkedHashMap<String, Method>();
        for (var use : definition.traits()) for (var adaptation : use.adaptations()) {
            if (!adaptation.excluded().isEmpty()) continue;
            var candidate = selected(adaptation.trait() == null ? available : imported, adaptation);
            var method = candidate.method;
            var changed = new Method(method.function(), method.shared(),
                    adaptation.visibility() == null ? method.visibility() : adaptation.visibility(),
                    method.abstractMethod(), method.finalMethod() || adaptation.finalMethod());
            if (changed.abstractMethod() && changed.finalMethod()) throw failure("An abstract trait method cannot be final");
            if (adaptation.alias() != null) {
                var function = changed.function();
                var aliasFunction = new Function(adaptation.alias(), function.parameters(), function.target(), function.file(), function.owner(), function.returnType(), function.builtin(), function.strictTypes(), function.declaration());
                changed = new Method(aliasFunction, changed.shared(), changed.visibility(), changed.abstractMethod(), changed.finalMethod());
                if (aliases.putIfAbsent(key(adaptation.alias()), changed) != null) throw failure("Duplicate trait alias " + adaptation.alias());
            } else {
                var candidates = available.get(key(adaptation.method()));
                for (int index = 0; index < candidates.size(); index++) {
                    if (candidates.get(index).trait.equals(candidate.trait)) candidates.set(index, new Imported(candidate.trait, changed));
                }
            }
        }
        var methods = new LinkedHashMap<String, Method>();
        for (var entry : available.entrySet()) {
            var concrete = new ArrayList<Method>();
            Method abstractMethod = null;
            for (var candidate : entry.getValue()) {
                var method = candidate.method;
                if (method.abstractMethod()) {
                    requirements.add(method);
                    abstractMethod = method;
                } else if (concrete.stream().noneMatch(existing -> sameBody(existing, method))) concrete.add(method);
            }
            if (definition.methods().containsKey(entry.getKey())) continue;
            if (concrete.size() > 1) throw failure("Trait method collision: " + definition.name() + "::" + entry.getKey());
            if (!concrete.isEmpty()) methods.put(entry.getKey(), concrete.getFirst());
            else if (abstractMethod != null && (parent == null || parent.method(entry.getKey()) == null)) methods.put(entry.getKey(), abstractMethod);
        }
        for (var entry : aliases.entrySet()) {
            if (definition.methods().containsKey(entry.getKey())) continue;
            var previous = methods.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !sameBody(previous, entry.getValue())) throw failure("Trait alias collision: " + entry.getKey());
            if (entry.getValue().abstractMethod()) requirements.add(entry.getValue());
        }
        methods.putAll(definition.methods());
        if (parent != null) {
            requirements.addAll(parent.requirements);
            for (var entry : methods.entrySet()) {
                var previous = parent.method(entry.getKey());
                if (previous == null || previous.visibility().equals("private") && !entry.getKey().equals("__construct")) continue;
                var method = entry.getValue();
                if (previous.finalMethod()) throw failure("Cannot override final method " + previous.function().owner() + "::" + entry.getKey());
                if (method.abstractMethod() && !previous.abstractMethod()) throw failure("Cannot make a concrete method abstract");
                if (method.shared() != previous.shared() || TypeRelations.visibility(method.visibility()) < TypeRelations.visibility(previous.visibility()))
                    throw failure("Incompatible inherited method " + definition.name() + "::" + entry.getKey());
                if (!entry.getKey().equals("__construct") || previous.abstractMethod()) TypeRelations.compatible(request, method, previous);
            }
            for (var property : properties.values()) {
                for (var ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
                    var inherited = ancestor.properties.stream().filter(item -> item.name().equals(property.name())).findFirst();
                    if (inherited.isPresent()) {
                        if (!inherited.get().visibility().equals("private")) {
                            var declared = inherited.get();
                            if (property.shared() != declared.shared()
                                    || TypeRelations.visibility(property.visibility()) < TypeRelations.visibility(declared.visibility())
                                    || !equivalentTypes(request, property.type(), definition.name(), declared.type(), ancestor.definition.name()))
                                throw failure("Incompatible inherited property " + definition.name() + "::$" + property.name());
                        }
                        break;
                    }
                }
            }
        }
        EnumApi.validate(definition, new ArrayList<>(properties.values()), methods);
        for (var method : methods.values()) if (method.abstractMethod()) requirements.add(method);
        for (var contract : interfaces) {
            for (var entry : contract.methods.entrySet()) {
                requirements.add(entry.getValue());
                if (definition.kind() == Ir.TypeKind.INTERFACE) {
                    var own = methods.get(entry.getKey());
                    if (own == null) methods.put(entry.getKey(), entry.getValue());
                    else TypeRelations.compatible(request, own, entry.getValue());
                }
            }
            requirements.addAll(contract.requirements);
        }
        for (var requirement : requirements) {
            Method implementation = methods.get(key(requirement.function().name()));
            if (implementation == null && parent != null) implementation = parent.method(requirement.function().name());
            if (implementation == null || implementation.abstractMethod()) {
                if ((definition.kind() == Ir.TypeKind.CLASS || definition.kind() == Ir.TypeKind.ENUM) && !definition.abstractType())
                    throw failure("Class " + definition.name() + " must implement abstract method " + requirement.function().name());
                if (implementation != null && implementation != requirement) TypeRelations.compatible(request, implementation, requirement);
            } else TypeRelations.compatible(request, implementation, requirement);
        }
        var inheritedConstants = new LinkedHashMap<String, ClassConstant>();
        if (parent != null) inheritedConstants.putAll(parent.constants);
        for (var contract : interfaces) for (var entry : contract.constants.entrySet()) {
            var own = constants.get(entry.getKey());
            if (own != null) compatibleInheritedConstant(request, definition.name(), own.declaration(), entry.getValue());
            var existing = inheritedConstants.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && !existing.owner().equalsIgnoreCase(entry.getValue().owner()) && !constants.containsKey(entry.getKey()))
                throw failure("Ambiguous inherited constant " + definition.name() + "::" + entry.getKey());
        }
        for (var entry : constants.entrySet()) {
            var inherited = inheritedConstants.get(entry.getKey());
            if (inherited != null && !inherited.declaration().visibility().equals("private")) {
                compatibleInheritedConstant(request, definition.name(), entry.getValue().declaration(), inherited);
            }
        }
        inheritedConstants.putAll(constants);
        return new Linked(new RuntimeClass(request, definition, parent, interfaces, methods, requirements,
                new ArrayList<>(properties.values()), inheritedConstants), List.copyOf(defaults));
    }

    private static void compatibleInheritedConstant(Request request, String owner, Ir.ClassConstantDeclaration actual, ClassConstant inherited) {
        var expected = inherited.declaration();
        if (expected.finalConstant() || TypeRelations.visibility(actual.visibility()) < TypeRelations.visibility(expected.visibility())
                || expected.type() != null && (actual.type() == null || !TypeRelations.subtype(request,
                TypeRelations.signature(actual.type(), request, owner), TypeRelations.signature(expected.type(), request, inherited.owner()))))
            throw failure("Incompatible inherited constant " + owner + "::" + actual.name());
    }

    private static Imported selected(Map<String, List<Imported>> methods, Ir.TraitAdaptation adaptation) {
        var matches = methods.get(key(adaptation.method()));
        if (matches == null) throw failure("Unknown trait method " + adaptation.method());
        var selected = matches.stream().filter(item -> adaptation.trait() == null || item.trait.equals(key(adaptation.trait()))).toList();
        if (selected.size() != 1) throw failure("Ambiguous or unknown trait method " + adaptation.method());
        return selected.getFirst();
    }

    private static boolean sameBody(Method left, Method right) {
        return left.function().target() == right.function().target() && left.shared() == right.shared()
                && left.visibility().equals(right.visibility()) && left.finalMethod() == right.finalMethod();
    }

    private static void compatibleProperty(Request request, String owner, Ir.PropertyDeclaration left,
            Ir.PropertyDeclaration right, List<DefaultCheck> defaults) {
        if (left.shared() != right.shared() || !left.visibility().equals(right.visibility())
                || !equivalentTypes(request, left.type(), owner, right.type(), owner))
            throw failure("Incompatible trait property " + owner + "::$" + left.name());
        if (left.type() != null && (left.value() == null) != (right.value() == null))
            throw failure("Incompatible initial value for " + owner + "::$" + left.name());
        defaults.add(new DefaultCheck(left.value() == null ? new Ir.Literal(null) : left.value(),
                right.value() == null ? new Ir.Literal(null) : right.value(), owner, left.name()));
    }

    private static boolean equivalentTypes(Request request, String left, String leftOwner, String right, String rightOwner) {
        if ((left == null) != (right == null)) return false;
        String first = TypeRelations.signature(left, request, leftOwner);
        String second = TypeRelations.signature(right, request, rightOwner);
        return TypeRelations.subtype(request, first, second) && TypeRelations.subtype(request, second, first);
    }

    private static void compatibleConstant(Ir.ClassConstantDeclaration left, Ir.ClassConstantDeclaration right,
            String owner, List<DefaultCheck> defaults) {
        if (!left.visibility().equals(right.visibility()) || left.finalConstant() != right.finalConstant()
                || !Objects.equals(left.type(), right.type())) throw failure("Incompatible trait constant " + owner + "::" + left.name());
        defaults.add(new DefaultCheck(left.value(), right.value(), owner, left.name()));
    }

    private static boolean equalValues(Object left, Object right) {
        if (left instanceof PhpValues.PhpArray && right instanceof PhpValues.PhpArray) {
            var keys = PhpValues.keys(left);
            if (!keys.equals(PhpValues.keys(right))) return false;
            for (Object key : keys) {
                Object first = PhpValues.element(left, key);
                Object second = PhpValues.element(right, key);
                try { if (!equalValues(PhpValues.unwrap(first), PhpValues.unwrap(second))) return false; }
                finally { PhpValues.drop(first); PhpValues.drop(second); }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static PhpError failure(String message) { return PhpError.fatal(message); }
}
