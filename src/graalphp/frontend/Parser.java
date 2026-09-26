package graalphp.frontend;

import com.oracle.truffle.api.source.Source;
import graalphp.runtime.PhpError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static graalphp.frontend.Ir.*;

/** Produces semantic IR without depending on frames, ownership or suspension. */
public final class Parser {
    private record Token(String text, Object literal, int start, int end) {}
    private final Source source;
    private final String text;
    private final List<Token> tokens = new ArrayList<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, String> functionAliases = new HashMap<>();
    private String namespace = "";
    private int position;
    private int closureId;

    public Parser(Source source) {
        this.source = source;
        text = source.getCharacters().toString();
        lex();
    }

    public Unit parse() {
        var statements = new ArrayList<Statement>();
        var functions = new ArrayList<Function>();
        var classes = new ArrayList<ClassDeclaration>();
        boolean strictTypes = false;
        boolean declarationAllowed = true;
        while (!at("<eof>")) {
            if (accept(";")) continue;
            if (accept("declare")) {
                if (!declarationAllowed) throw PhpError.fatal("strict_types declaration must be the very first statement in the script");
                take("(");
                if (!accept("strict_types")) throw new PhpError("Only strict_types declarations are currently implemented");
                take("=");
                Object value = peek().literal;
                if (!(value instanceof Long number) || number != 0 && number != 1)
                    throw PhpError.fatal("strict_types declaration must have 0 or 1 as its value");
                position++;
                strictTypes |= ((Long) value) == 1;
                take(")");
                if (!accept(";")) throw PhpError.fatal("strict_types declaration must not use block mode");
                continue;
            }
            declarationAllowed = false;
            var attributes = attributes();
            if (!attributes.isEmpty() && !atTypeDeclaration() && !at("function")) fail("Attributes require a declaration");
            if (accept("namespace")) {
                namespace = identifier();
                take(";");
                aliases.clear();
                functionAliases.clear();
            } else if (accept("use")) {
                boolean function = accept("function");
                do {
                    String name = identifier().replaceFirst("^\\\\", "");
                    String alias = accept("as") ? identifier() : name.substring(name.lastIndexOf('\\') + 1);
                    (function ? functionAliases : aliases).put(alias.toLowerCase(java.util.Locale.ROOT), name);
                } while (accept(","));
                take(";");
            } else if (at("function")) {
                functions.add(function(false, false, attributes));
            } else if (atTypeDeclaration()) {
                int start = peek().start;
                var declaration = classDeclaration(attributes);
                // Only declarations whose inheritance is known here can be bound early.
                if (declaration.kind() != TypeKind.ENUM && declaration.interfaces().isEmpty() && declaration.traits().isEmpty()
                        && (declaration.parent() == null || classes.stream().anyMatch(parent -> parent.name().equalsIgnoreCase(declaration.parent())))) {
                    classes.add(declaration);
                } else {
                    statements.add(new Statement(start, previous().end - start, new DeclareClass(declaration)));
                }
            } else {
                statements.add(statement());
            }
        }
        return new Unit(source, List.copyOf(statements), List.copyOf(functions), List.copyOf(classes), strictTypes);
    }

    private boolean atTypeDeclaration() {
        return at("class") || at("interface") || at("trait") || at("enum") || at("abstract") || at("final");
    }

    private List<Attribute> attributes() {
        var result = new ArrayList<Attribute>();
        while (accept("#[")) {
            do {
                int start = peek().start;
                String name = qualified(identifier(), false);
                var arguments = at("(") ? arguments() : List.<Expression>of();
                result.add(new Attribute(name, arguments, start, previous().end - start));
            } while (accept(",") && !at("]"));
            take("]");
        }
        return List.copyOf(result);
    }

    private ClassDeclaration classDeclaration(List<Attribute> attributes) {
        boolean abstractType = false;
        boolean finalType = false;
        while (at("abstract") || at("final")) {
            if (accept("abstract")) { if (abstractType) fail("Repeated abstract modifier"); abstractType = true; }
            else { take("final"); if (finalType) fail("Repeated final modifier"); finalType = true; }
        }
        TypeKind kind;
        if (accept("interface")) kind = TypeKind.INTERFACE;
        else if (accept("trait")) kind = TypeKind.TRAIT;
        else if (accept("enum")) kind = TypeKind.ENUM;
        else { take("class"); kind = TypeKind.CLASS; }
        if (abstractType && finalType || kind != TypeKind.CLASS && (abstractType || finalType)) fail("Invalid type modifiers");
        String name = declared(identifier());
        String backingType = null;
        if (kind == TypeKind.ENUM && accept(":")) {
            backingType = identifier();
            if (!List.of("int", "string").contains(backingType)) throw PhpError.fatal("Enum backing type must be int or string");
        }
        String parent = null;
        var interfaces = new ArrayList<String>();
        if (accept("extends")) {
            if (kind == TypeKind.INTERFACE) {
                do { interfaces.add(qualified(identifier(), false)); } while (accept(","));
            } else if (kind == TypeKind.CLASS) parent = qualified(identifier(), false);
            else fail("A trait cannot extend a class");
        }
        if (accept("implements")) {
            if (kind != TypeKind.CLASS && kind != TypeKind.ENUM) fail("Only classes and enums can implement interfaces");
            do { interfaces.add(qualified(identifier(), false)); } while (accept(","));
        }
        var properties = new ArrayList<PropertyDeclaration>();
        var methods = new ArrayList<MethodDeclaration>();
        var traits = new ArrayList<TraitUse>();
        var constants = new ArrayList<ClassConstantDeclaration>();
        var cases = new ArrayList<EnumCase>();
        take("{");
        while (!accept("}")) {
            var memberAttributes = attributes();
            if (kind == TypeKind.ENUM && accept("case")) {
                String caseName = identifier();
                Expression value = accept("=") ? expression(0) : null;
                take(";");
                if ((backingType == null) != (value == null))
                    throw PhpError.fatal("Enum case " + name + "::" + caseName + " must match its backing declaration");
                cases.add(new EnumCase(caseName, value, memberAttributes));
                continue;
            }
            if (accept("use")) {
                if (!memberAttributes.isEmpty()) fail("Attributes cannot precede a trait use");
                if (kind == TypeKind.INTERFACE) fail("Interfaces cannot use traits");
                traits.add(traitUse());
                continue;
            }
            String visibility = "public";
            boolean shared = false;
            boolean abstractMethod = false;
            boolean finalMember = false;
            var modifiers = new java.util.HashSet<String>();
            while (List.of("public", "private", "protected", "static", "abstract", "final", "var").contains(peek().text)) {
                String modifier = tokens.get(position++).text;
                if (!modifiers.add(modifier)) fail("Repeated member modifier " + modifier);
                switch (modifier) {
                    case "static" -> shared = true;
                    case "abstract" -> abstractMethod = true;
                    case "final" -> finalMember = true;
                    case "var" -> visibility = "public";
                    default -> visibility = modifier;
                }
            }
            long visibilityCount = modifiers.stream().filter(value -> List.of("public", "private", "protected", "var").contains(value)).count();
            if (visibilityCount > 1) fail("Multiple visibility modifiers");
            if (at("function")) {
                boolean signatureOnly = kind == TypeKind.INTERFACE || abstractMethod;
                if (kind == TypeKind.INTERFACE && (!visibility.equals("public") || finalMember || abstractMethod)) fail("Invalid interface method modifiers");
                if (signatureOnly && finalMember) fail("An abstract method cannot be final");
                if (abstractMethod && kind == TypeKind.CLASS && (!abstractType || visibility.equals("private"))) fail("Invalid abstract class method");
                methods.add(new MethodDeclaration(function(true, signatureOnly, memberAttributes), shared, visibility, signatureOnly, finalMember));
            } else if (accept("const")) {
                if (shared || abstractMethod) fail("Invalid constant modifiers");
                String constantType = position + 1 < tokens.size() && tokens.get(position + 1).text.equals("=") ? null : type();
                do {
                    String constantName = identifier(); take("=");
                    constants.add(new ClassConstantDeclaration(constantName, expression(0), visibility, finalMember, constantType, memberAttributes));
                } while (accept(","));
                take(";");
            } else {
                if (kind == TypeKind.INTERFACE || abstractMethod || finalMember) fail("Unsupported property declaration modifiers");
                String propertyType = peek().text.startsWith("$") ? null : type();
                do {
                    String property = variableName();
                    Expression value = accept("=") ? expression(0) : null;
                    properties.add(new PropertyDeclaration(property, value, shared, visibility, propertyType, memberAttributes));
                } while (accept(","));
                take(";");
            }
        }
        return new ClassDeclaration(name, parent, List.copyOf(properties), List.copyOf(methods), kind,
                abstractType || kind == TypeKind.INTERFACE, finalType, List.copyOf(interfaces),
                List.copyOf(traits), List.copyOf(constants), backingType, List.copyOf(cases), attributes);
    }

    private TraitUse traitUse() {
        var names = new ArrayList<String>();
        do { names.add(qualified(identifier(), false)); } while (accept(","));
        var adaptations = new ArrayList<TraitAdaptation>();
        if (accept(";")) return new TraitUse(List.copyOf(names), List.of());
        take("{");
        while (!accept("}")) {
            String first = identifier();
            String trait = null;
            String method = first;
            if (accept("::")) { trait = qualified(first, false); method = identifier(); }
            var excluded = new ArrayList<String>();
            String alias = null;
            String visibility = null;
            boolean finalMethod = false;
            if (accept("insteadof")) {
                if (trait == null) fail("Trait precedence requires a qualified method");
                do { excluded.add(qualified(identifier(), false)); } while (accept(","));
            } else {
                take("as");
                if (at("public") || at("protected") || at("private")) visibility = tokens.get(position++).text;
                finalMethod = accept("final");
                if (!at(";")) alias = identifier();
                if (alias == null && visibility == null && !finalMethod) fail("Empty trait alias");
            }
            take(";");
            adaptations.add(new TraitAdaptation(trait, method, List.copyOf(excluded), alias, visibility, finalMethod));
        }
        return new TraitUse(List.copyOf(names), List.copyOf(adaptations));
    }

    private Function function(boolean method, boolean signatureOnly, List<Attribute> attributes) {
        int start = take("function").start;
        String name = identifier();
        if (!method) name = declared(name);
        var parameters = parameters();
        String returnType = accept(":") ? type() : null;
        List<Statement> body;
        if (signatureOnly) { take(";"); body = List.of(); }
        else body = ((Block) block().form()).statements();
        return new Function(name, parameters, body, start, previous().end - start, returnType, attributes);
    }

    private List<Parameter> parameters() {
        take("(");
        var parameters = new ArrayList<Parameter>();
        if (!at(")")) do {
            var attributes = attributes();
            String type = at("&") || at("...") || peek().text.startsWith("$") ? null : type();
            boolean reference = accept("&");
            boolean variadic = accept("...");
            String name = variableName();
            Expression defaultValue = accept("=") ? expression(0) : null;
            if (variadic && defaultValue != null) fail("A variadic parameter cannot have a default");
            parameters.add(new Parameter(name, reference, type, defaultValue, variadic, attributes));
            if (variadic && !at(")")) fail("A variadic parameter must be last");
        } while (accept(",") && !at(")"));
        take(")");
        return List.copyOf(parameters);
    }

    private String type() {
        boolean nullable = accept("?");
        String result = intersectionType();
        while (accept("|")) result += "|" + intersectionType();
        if (nullable && (result.contains("|") || result.contains("&"))) fail("Nullable shorthand requires one type");
        return nullable ? "?" + result : result;
    }

    private String intersectionType() {
        String result = typeAtom();
        while (at("&") && position + 1 < tokens.size()
                && !tokens.get(position + 1).text.startsWith("$") && !tokens.get(position + 1).text.equals("...")) {
            take("&"); result += "&" + typeAtom();
        }
        return result;
    }

    private String typeAtom() {
        if (accept("(")) { String nested = type(); take(")"); return "(" + nested + ")"; }
        String name = identifier();
        return List.of("int", "float", "string", "bool", "array", "object", "callable", "iterable", "mixed", "void",
                "never", "null", "true", "false", "self", "parent", "static").contains(name) ? name : qualified(name, false);
    }

    private Statement block() {
        int start = take("{").start;
        var statements = new ArrayList<Statement>();
        while (!at("}")) statements.add(statement());
        take("}");
        return new Statement(start, previous().end - start, new Block(List.copyOf(statements)));
    }

    private Statement conditional() {
        int start = previous().start;
        take("(");
        var condition = expression(0);
        take(")");
        var yes = statement();
        Statement no = null;
        if (accept("elseif")) no = conditional();
        else if (accept("else")) no = statement();
        return new Statement(start, previous().end - start, new If(condition, yes, no));
    }

    private Statement statement() {
        int start = peek().start;
        Form form;
        var attributes = attributes();
        if (!attributes.isEmpty() && !atTypeDeclaration()) fail("Attributes require a class declaration in this position");
        if (at("{")) return block();
        if (at("declare")) throw PhpError.fatal("strict_types declaration must be the very first statement in the script");
        if (atTypeDeclaration()) {
            var declaration = classDeclaration(attributes);
            return new Statement(start, previous().end - start, new DeclareClass(declaration));
        }
        if (accept(";")) form = new Block(List.of());
        else if (accept("echo")) { form = new Echo(expressionList(";")); take(";"); }
        else if (accept("return")) { form = new Return(at(";") ? new Literal(null) : expression(0)); take(";"); }
        else if (accept("if")) return conditional();
        else if (accept("while")) {
            take("("); var condition = expression(0); take(")"); form = new While(condition, statement());
        } else if (accept("do")) {
            var body = statement();
            take("while"); take("("); var condition = expression(0); take(")"); take(";");
            form = new DoWhile(body, condition);
        } else if (accept("for")) {
            take("(");
            var initialize = expressionList(";"); take(";");
            var conditions = expressionList(";"); take(";");
            var update = expressionList(")"); take(")");
            form = new For(initialize, conditions, update, statement());
        } else if (accept("foreach")) {
            take("("); var array = expression(0); take("as"); boolean reference = accept("&");
            String value = variableName(); String key = null;
            if (accept("=>")) { key = value; reference = accept("&"); value = variableName(); }
            take(")"); form = new Foreach(array, key, value, reference, statement());
        } else if (accept("try")) {
            var body = block(); String caught = null; String caughtType = null; Statement handler = null; Statement cleanup = null;
            if (accept("catch")) {
                take("("); caughtType = qualified(identifier(), false);
                while (accept("|")) caughtType += "|" + qualified(identifier(), false);
                caught = variableName(); take(")"); handler = block();
            }
            if (accept("finally")) cleanup = block();
            if (handler == null && cleanup == null) fail("Expected catch or finally");
            form = new Try(body, caught, caughtType, handler, cleanup);
        } else if (accept("throw")) { form = new Throw(expression(0)); take(";"); }
        else if (accept("unset")) { take("("); form = new Unset(expressionList(")")); take(")"); take(";"); }
        else if (accept("global")) {
            var names = new ArrayList<String>(); do { names.add(variableName()); } while (accept(","));
            take(";"); form = new Global(List.copyOf(names));
        } else if (accept("break")) { take(";"); form = new Break(); }
        else if (accept("continue")) { take(";"); form = new Continue(); }
        else { form = new ExpressionStatement(expression(0)); take(";"); }
        return new Statement(start, Math.max(1, previous().end - start), form);
    }

    private List<Expression> expressionList(String end) {
        var values = new ArrayList<Expression>();
        if (!at(end)) do { values.add(expression(0)); } while (accept(",") && !at(end));
        return List.copyOf(values);
    }

    private Expression matchExpression() {
        take("("); Expression subject = expression(0); take(")"); take("{");
        var arms = new ArrayList<MatchArm>();
        boolean defaultSeen = false;
        while (!accept("}")) {
            var conditions = new ArrayList<Expression>();
            if (accept("default")) {
                if (defaultSeen) throw PhpError.fatal("Match expressions may only contain one default arm");
                defaultSeen = true;
            } else {
                do { conditions.add(expression(0)); } while (accept(",") && !at("=>"));
            }
            take("=>"); arms.add(new MatchArm(List.copyOf(conditions), expression(0)));
            if (!accept(",")) { take("}"); break; }
        }
        return new Match(subject, List.copyOf(arms));
    }

    private List<Expression> arguments() {
        take("(");
        var args = new ArrayList<Expression>();
        var names = new java.util.HashSet<String>();
        boolean unpacked = false;
        if (!at(")")) do {
            if (accept("...")) {
                if (!names.isEmpty()) fail("Cannot use argument unpacking after named arguments");
                unpacked = true;
                args.add(new UnpackArgument(expression(0)));
            } else if (position + 1 < tokens.size() && tokens.get(position + 1).text().equals(":")) {
                String name = identifier();
                take(":");
                if (!names.add(name)) fail("Duplicate named argument " + name);
                args.add(new NamedArgument(name, expression(0)));
            } else {
                if (!names.isEmpty()) fail("Positional argument after named argument");
                if (unpacked) fail("Cannot use positional argument after argument unpacking");
                args.add(expression(0));
            }
        } while (accept(",") && !at(")"));
        take(")");
        return List.copyOf(args);
    }

    private Expression expression(int minimum) {
        Expression left = primary();
        while (true) {
            if (accept("[")) {
                var key = at("]") ? null : expression(0);
                take("]"); left = new Index(left, key); continue;
            }
            if (accept("->")) {
                String member = identifier();
                left = at("(") ? new MethodCall(left, member, arguments()) : new Property(left, member);
                continue;
            }
            if (accept("::")) {
                String member = identifier();
                left = at("(") ? new DynamicStaticCall(left, member, arguments()) : new ClassConstant(left, member);
                continue;
            }
            if (at("(")) { left = new DynamicCall(left, arguments()); continue; }
            if (at("++") || at("--")) {
                String increment = tokens.get(position++).text;
                left = new Increment(left, increment.equals("++") ? 1 : -1, false);
                continue;
            }
            if (at("?") && minimum <= 2) {
                take("?");
                Expression yes = at(":") ? null : expression(0);
                take(":");
                left = new Conditional(left, yes, expression(3));
                continue;
            }
            String operator = peek().text;
            int precedence = precedence(operator);
            if (precedence < minimum) break;
            position++;
            if (operator.equals("instanceof")) {
                Expression type = peek().text.startsWith("$") || at("(") ? expression(precedence + 1)
                        : new Literal(qualified(identifier(), false));
                left = new InstanceOf(left, type);
                continue;
            }
            boolean assignment = precedence == 1;
            boolean reference = operator.equals("=") && accept("&");
            var right = expression(assignment || operator.equals("??") ? precedence : precedence + 1);
            if (assignment) {
                if (operator.equals("=")) left = new Assign(left, right, reference);
                else left = new CompoundAssign(operator.substring(0, operator.length() - 1), left, right);
            } else if (operator.equals("??")) left = new Coalesce(left, right);
            else left = new Binary(operator, left, right);
        }
        return left;
    }

    private Expression primary() {
        var attributes = attributes();
        if (!attributes.isEmpty() && !at("function") && !at("fn")) fail("Attributes require a closure in this expression");
        var token = peek();
        if (accept("match")) return matchExpression();
        if (accept("throw")) return new ThrowExpression(expression(0));
        if (accept("clone")) return new Clone(expression(12));
        if (accept("(")) { var value = expression(0); take(")"); return value; }
        if (accept("!") || accept("-") || accept("+")) return new Unary(token.text, expression(11));
        if (accept("++") || accept("--")) return new Increment(expression(11), token.text.equals("++") ? 1 : -1, true);
        if (accept("[")) {
            var entries = new ArrayList<ArrayEntry>();
            if (!at("]")) do {
                if (accept("...")) {
                    entries.add(new ArrayEntry(null, expression(0), false, true));
                    continue;
                }
                boolean reference = accept("&"); var value = expression(0); Expression key = null;
                if (accept("=>")) { key = value; reference = accept("&"); value = expression(0); }
                entries.add(new ArrayEntry(key, value, reference, false));
            } while (accept(",") && !at("]"));
            take("]"); return new ArrayLiteral(List.copyOf(entries));
        }
        if (at("function") || at("fn")) return closure(attributes);
        if (token.text.startsWith("$")) { position++; return new Variable(token.text.substring(1)); }
        if (at("<literal>")) {
            position++;
            return token.literal instanceof Expression interpolated ? interpolated : new Literal(token.literal);
        }
        if (accept("true")) return new Literal(true);
        if (accept("false")) return new Literal(false);
        if (accept("null")) return new Literal(null);
        if (accept("new")) {
            if (peek().text.startsWith("$") || at("(")) {
                Expression type;
                if (accept("(")) { type = expression(0); take(")"); }
                else type = new Variable(variableName());
                return new DynamicConstruct(type, at("(") ? arguments() : List.of());
            }
            String type = qualified(identifier(), false);
            var args = at("(") ? arguments() : List.<Expression>of();
            if (type.equalsIgnoreCase("Exception")) return new Call("exception", args);
            if (type.startsWith("Async\\") && (type.endsWith("Exception") || type.endsWith("Cancellation"))
                    || List.of("RuntimeException", "LogicException", "InvalidArgumentException", "Error", "TypeError", "ValueError", "UnhandledMatchError").contains(type)) {
                var values = new ArrayList<Expression>();
                values.add(new Literal(type));
                values.addAll(args);
                return new Call("__exception", List.copyOf(values));
            }
            return new Construct(type, args);
        }
        if (at("include") || at("require") || at("include_once") || at("require_once")) {
            position++; return new Call(token.text, List.of(expression(2)));
        }
        var name = identifier();
        if (accept("::")) {
            String type = qualified(name, false);
            if (peek().text.startsWith("$")) return new StaticProperty(type, variableName());
            String member = identifier();
            if (member.equals("class")) return List.of("self", "parent", "static").contains(type) ? new ClassName(type) : new Literal(type);
            return at("(") ? new StaticCall(type, member, arguments()) : new ClassConstant(new Literal(type), member);
        }
        if (at("(")) return new Call(qualified(name, true), arguments(), !namespace.isEmpty() && !name.contains("\\")
                && !functionAliases.containsKey(name.toLowerCase(java.util.Locale.ROOT)));
        if (name.equals("__NAMESPACE__")) return new Literal(namespace);
        return new Constant(name);
    }

    private Expression closure(List<Attribute> attributes) {
        var token = tokens.get(position++);
        boolean arrow = token.text.equals("fn");
        var parameters = parameters();
        var captures = new ArrayList<Capture>();
        if (!arrow && accept("use")) {
            take("(");
            if (!at(")")) do {
                boolean reference = accept("&");
                captures.add(new Capture(variableName(), reference));
            } while (accept(",") && !at(")"));
            take(")");
        }
        String returnType = accept(":") ? type() : null;
        List<Statement> body;
        if (arrow) {
            take("=>");
            var value = expression(1);
            body = List.of(new Statement(token.start, previous().end - token.start, new Return(value)));
        } else body = ((Block) block().form()).statements();
        var function = new Function("{closure#" + ++closureId + "}", parameters, body, token.start,
                previous().end - token.start, returnType, attributes);
        return new Closure(function, List.copyOf(captures), arrow);
    }

    private String declared(String name) { return namespace.isEmpty() ? name : namespace + "\\" + name; }

    private String qualified(String name, boolean function) {
        if (name.startsWith("\\")) return name.substring(1);
        if (List.of("self", "parent", "static").contains(name)) return name;
        int separator = name.indexOf('\\');
        String first = separator < 0 ? name : name.substring(0, separator);
        String alias = (function && separator < 0 ? functionAliases : aliases).get(first.toLowerCase(java.util.Locale.ROOT));
        if (alias != null) return alias + (separator < 0 ? "" : name.substring(separator));
        if (name.startsWith("namespace\\")) return declared(name.substring(10));
        return declared(name);
    }

    private int precedence(String operator) {
        return switch (operator) {
            case "=", "+=", "-=", ".=", "*=", "/=", "%=", "??=" -> 1;
            case "??" -> 3; case "||" -> 4; case "&&" -> 5;
            case "==", "!=", "===", "!==" -> 6;
            case "<", "<=", ">", ">=" -> 7;
            case "instanceof" -> 11;
            case "." -> 8; case "+", "-" -> 9; case "*", "/", "%" -> 10;
            default -> -1;
        };
    }

    private String identifier() {
        var token = peek();
        if (!token.text.matches("[A-Za-z_\\\\][A-Za-z_0-9\\\\]*")) fail("Expected identifier, found " + token.text);
        position++;
        return token.text;
    }
    private String variableName() {
        var token = peek();
        if (!token.text.startsWith("$")) fail("Expected variable");
        position++;
        return token.text.substring(1);
    }
    private Token peek() { return tokens.get(position); }
    private Token previous() { return tokens.get(position - 1); }
    private boolean at(String value) { return peek().text.equals(value); }
    private boolean accept(String value) { if (!at(value)) return false; position++; return true; }
    private Token take(String value) {
        if (!at(value)) fail("Expected '" + value + "', found '" + peek().text + "'");
        return tokens.get(position++);
    }
    private void fail(String message) {
        int line = text.isEmpty() ? 1 : source.getLineNumber(Math.min(peek().start, text.length() - 1));
        throw new PhpError(source.getName() + ":" + line + ": " + message);
    }

    private void lex() {
        int i = text.startsWith("<?php") ? 5 : 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (text.startsWith("?>", i)) { i += 2; if (!text.substring(i).isBlank()) throw new PhpError("Inline HTML is not implemented"); break; }
            if (c == '#' && !text.startsWith("#[", i) || text.startsWith("//", i)) { while (i < text.length() && text.charAt(i) != '\n') i++; continue; }
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2); if (end < 0) throw new PhpError("Unterminated comment"); i = end + 2; continue;
            }
            int start = i;
            if (c == '\'' || c == '"') {
                char quote = c; i++; var literal = new StringBuilder();
                var parts = new ArrayList<Expression>();
                while (i < text.length() && text.charAt(i) != quote) {
                    c = text.charAt(i++);
                    if (c == '\\' && i < text.length()) {
                        char escaped = text.charAt(i++);
                        if (quote == '\'' && escaped != '\\' && escaped != '\'') literal.append('\\').append(escaped);
                        else literal.append(switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> escaped; });
                    } else if (quote == '"' && c == '$' && i < text.length()
                            && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '_')) {
                        parts.add(new Literal(literal.toString()));
                        literal.setLength(0);
                        int nameStart = i;
                        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
                        parts.add(new Variable(text.substring(nameStart, i)));
                    } else if (quote == '"' && c == '{' && i < text.length() && text.charAt(i) == '$') {
                        int end = text.indexOf('}', i);
                        if (end < 0) throw new PhpError("Unterminated string interpolation");
                        parts.add(new Literal(literal.toString()));
                        literal.setLength(0);
                        var parser = new Parser(Source.newBuilder("php", text.substring(i, end), source.getName()).build());
                        parts.add(parser.expression(0));
                        if (!parser.at("<eof>")) throw new PhpError("Invalid string interpolation");
                        i = end + 1;
                    } else {
                        literal.append(c);
                    }
                }
                if (i == text.length()) throw new PhpError("Unterminated string");
                Object value = literal.toString();
                if (!parts.isEmpty()) {
                    parts.add(new Literal(literal.toString()));
                    Expression interpolated = parts.getFirst();
                    for (int part = 1; part < parts.size(); part++) interpolated = new Binary(".", interpolated, parts.get(part));
                    value = interpolated;
                }
                tokens.add(new Token("<literal>", value, start, ++i)); continue;
            }
            if (Character.isDigit(c)) {
                while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
                boolean decimal = i < text.length() - 1 && text.charAt(i) == '.' && Character.isDigit(text.charAt(i + 1));
                if (decimal) { i++; while (i < text.length() && Character.isDigit(text.charAt(i))) i++; }
                String number = text.substring(start, i);
                Object value;
                try { if (decimal) value = Double.parseDouble(number); else value = Long.parseLong(number); }
                catch (NumberFormatException error) { throw new PhpError("Numeric literal out of supported range: " + number); }
                tokens.add(new Token("<literal>", value, start, i)); continue;
            }
            if (c == '$' || c == '_' || c == '\\' || Character.isLetter(c)) {
                i++; while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '\\')) i++;
                tokens.add(new Token(text.substring(start, i), null, start, i)); continue;
            }
            String operator = null;
            for (String candidate : List.of("#[", "??=", "...",  "===", "!==", "=>", "->", "::", "++", "--", "??", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", ".=", "*=", "/=", "%=")) {
                if (text.startsWith(candidate, i)) { operator = candidate; break; }
            }
            if (operator == null) operator = String.valueOf(c);
            i += operator.length(); tokens.add(new Token(operator, null, start, i));
        }
        tokens.add(new Token("<eof>", null, text.length(), text.length()));
    }
}
