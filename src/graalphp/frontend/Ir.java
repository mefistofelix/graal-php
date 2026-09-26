package graalphp.frontend;

import com.oracle.truffle.api.source.Source;
import java.util.List;

/** Syntax-independent semantic input to Truffle lowering. No runtime values or frames. */
public final class Ir {
    private Ir() {}
    public record Unit(Source source, List<Statement> statements, List<Function> functions,
                       List<ClassDeclaration> classes, boolean strictTypes) {}
    public record Attribute(String name, List<Expression> arguments, int start, int length) {}
    public record Function(String name, List<Parameter> parameters, List<Statement> body, int start, int length,
                           String returnType, List<Attribute> attributes) {
        public Function(String name, List<Parameter> parameters, List<Statement> body, int start, int length, String returnType) {
            this(name, parameters, body, start, length, returnType, List.of());
        }
    }
    public record Parameter(String name, boolean reference, String type, Expression defaultValue, boolean variadic, List<Attribute> attributes) {
        public Parameter(String name, boolean reference, String type, Expression defaultValue, boolean variadic) {
            this(name, reference, type, defaultValue, variadic, List.of());
        }
    }
    public enum TypeKind { CLASS, INTERFACE, TRAIT, ENUM }
    public record ClassDeclaration(String name, String parent, List<PropertyDeclaration> properties,
                                   List<MethodDeclaration> methods, TypeKind kind, boolean abstractType,
                                   boolean finalType, List<String> interfaces, List<TraitUse> traits,
                                   List<ClassConstantDeclaration> constants, String backingType,
                                   List<EnumCase> cases, List<Attribute> attributes) {}
    public record EnumCase(String name, Expression value, List<Attribute> attributes) {}
    public record TraitUse(List<String> names, List<TraitAdaptation> adaptations) {}
    public record TraitAdaptation(String trait, String method, List<String> excluded, String alias,
                                  String visibility, boolean finalMethod) {}
    public record ClassConstantDeclaration(String name, Expression value, String visibility,
                                           boolean finalConstant, String type, List<Attribute> attributes) {
        public ClassConstantDeclaration(String name, Expression value, String visibility, boolean finalConstant, String type) {
            this(name, value, visibility, finalConstant, type, List.of());
        }
    }
    public record PropertyDeclaration(String name, Expression value, boolean shared, String visibility, String type, List<Attribute> attributes) {
        public PropertyDeclaration(String name, Expression value, boolean shared, String visibility, String type) {
            this(name, value, shared, visibility, type, List.of());
        }
    }
    public record MethodDeclaration(Function function, boolean shared, String visibility,
                                    boolean abstractMethod, boolean finalMethod) {}
    public record Capture(String name, boolean reference) {}
    public record Statement(int start, int length, Form form) {}
    public sealed interface Form permits ExpressionStatement, Echo, Return, If, While, Foreach, Try,
            Throw, Unset, Global, Block, Break, Continue, For, DoWhile, DeclareClass {}
    public record DeclareClass(ClassDeclaration declaration) implements Form {}
    public record Block(List<Statement> statements) implements Form {}
    public record ExpressionStatement(Expression expression) implements Form {}
    public record Echo(List<Expression> expressions) implements Form {}
    public record Return(Expression value) implements Form {}
    public record If(Expression condition, Statement yes, Statement no) implements Form {}
    public record While(Expression condition, Statement body) implements Form {}
    public record For(List<Expression> initialize, List<Expression> conditions, List<Expression> update, Statement body) implements Form {}
    public record DoWhile(Statement body, Expression condition) implements Form {}
    public record Foreach(Expression source, String key, String value, boolean reference, Statement body) implements Form {}
    public record Try(Statement body, String caught, String caughtType, Statement handler, Statement cleanup) implements Form {}
    public record Throw(Expression value) implements Form {}
    public record Unset(List<Expression> locations) implements Form {}
    public record Global(List<String> names) implements Form {}
    public record Break() implements Form {}
    public record Continue() implements Form {}
    public sealed interface Expression permits Literal, Variable, Index, Assign, Binary, Unary, Call, ArrayLiteral,
            CompoundAssign, Increment, Conditional, Coalesce, Property, StaticProperty, MethodCall, StaticCall,
            Construct, DynamicConstruct, DynamicCall, Closure, Constant, NamedArgument, ClassConstant, ClassName, InstanceOf, EnumCaseValue,
            Match, ThrowExpression, Clone, DynamicStaticCall {}
    public record NamedArgument(String name, Expression value) implements Expression {}
    public record Literal(Object value) implements Expression {}
    public record Variable(String name) implements Expression {}
    public record Index(Expression array, Expression key) implements Expression {}
    public record Assign(Expression target, Expression value, boolean reference) implements Expression {}
    public record CompoundAssign(String operator, Expression target, Expression value) implements Expression {}
    public record Increment(Expression target, int delta, boolean before) implements Expression {}
    public record Conditional(Expression condition, Expression yes, Expression no) implements Expression {}
    public record Coalesce(Expression left, Expression right) implements Expression {}
    public record Property(Expression object, String name) implements Expression {}
    public record StaticProperty(String type, String name) implements Expression {}
    public record MethodCall(Expression object, String name, List<Expression> arguments) implements Expression {}
    public record StaticCall(String type, String name, List<Expression> arguments) implements Expression {}
    public record Construct(String type, List<Expression> arguments) implements Expression {}
    public record DynamicConstruct(Expression type, List<Expression> arguments) implements Expression {}
    public record DynamicCall(Expression callable, List<Expression> arguments) implements Expression {}
    public record Closure(Function function, List<Capture> captures, boolean arrow) implements Expression {}
    public record Constant(String name) implements Expression {}
    public record ClassConstant(Expression type, String name) implements Expression {}
    public record ClassName(String type) implements Expression {}
    public record InstanceOf(Expression value, Expression type) implements Expression {}
    public record EnumCaseValue(String name) implements Expression {}
    public record Match(Expression subject, List<MatchArm> arms) implements Expression {}
    public record MatchArm(List<Expression> conditions, Expression value) {}
    public record ThrowExpression(Expression value) implements Expression {}
    public record Clone(Expression value) implements Expression {}
    public record DynamicStaticCall(Expression type, String name, List<Expression> arguments) implements Expression {}
    public record Binary(String operator, Expression left, Expression right) implements Expression {}
    public record Unary(String operator, Expression value) implements Expression {}
    public record Call(String name, List<Expression> arguments, boolean globalFallback) implements Expression {
        public Call(String name, List<Expression> arguments) { this(name, arguments, false); }
    }
    public record ArrayLiteral(List<ArrayEntry> entries) implements Expression {}
    public record ArrayEntry(Expression key, Expression value, boolean reference) {}
}
