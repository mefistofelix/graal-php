package graalphp.truffle;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.source.Source;
import graalphp.frontend.Ir;
import graalphp.frontend.Parser;
import graalphp.runtime.Execution;
import graalphp.runtime.PhpError;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;

/** One lowering path is used by files, include, eval, reload and native images. */
public final class PhpCompiler {
    private record Loop(BytecodeLabel exit, BytecodeLabel next) {}
    private final PhpRootGen.Builder builder;
    private final PhpLanguage language;
    private final Source source;
    private final Path path;
    private final String owner;
    private final String functionName;
    private final String traitName;
    private final boolean strictTypes;
    private final ArrayDeque<Loop> loops = new ArrayDeque<>();
    private PhpCompiler(PhpRootGen.Builder builder, PhpLanguage language, Source source, Path path, String owner, String functionName, boolean strictTypes, String traitName) {
        this.builder = builder;
        this.language = language;
        this.source = source;
        this.path = path;
        this.owner = owner;
        this.functionName = functionName;
        this.traitName = traitName;
        this.strictTypes = strictTypes;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Execution.Unit compile(PhpLanguage language, Source source) {
        var ir = new Parser(source).parse();
        Path path = "file".equals(source.getURI().getScheme()) ? Path.of(source.getURI()).toAbsolutePath().normalize() : null;
        var functions = new HashMap<String, Execution.Function>();
        for (var function : ir.functions()) {
            String key = function.name().toLowerCase(java.util.Locale.ROOT);
            var compiled = function(language, source, path, function.name(), function.parameters(), function.body(), false, null, function.returnType(), ir.strictTypes()).annotated(source, function, false);
            if (functions.putIfAbsent(key, compiled) != null) throw new PhpError("Cannot redeclare " + key);
        }
        var classes = new java.util.LinkedHashMap<String, graalphp.runtime.ObjectModel.Definition>();
        for (var declaration : ir.classes()) {
            var definition = definition(language, source, path, declaration, ir.strictTypes());
            if (classes.putIfAbsent(declaration.name().toLowerCase(java.util.Locale.ROOT), definition) != null) {
                throw new PhpError("Cannot redeclare class " + declaration.name());
            }
        }
        return new Execution.Unit(path, source.getCharacters().toString(),
                function(language, source, path, source.getName(), List.of(), ir.statements(), true, null, null, ir.strictTypes()),
                java.util.Map.copyOf(functions), java.util.Collections.unmodifiableMap(classes));
    }
    public static java.util.Map<String, graalphp.runtime.ObjectModel.Definition> builtinTypes(PhpLanguage language, Source source) {
        var parsed = new Parser(source).parse();
        var declarations = new java.util.ArrayList<>(parsed.classes());
        for (var statement : parsed.statements()) {
            if (!(statement.form() instanceof Ir.DeclareClass declared)) throw new IllegalArgumentException("Builtin type source must contain declarations only");
            declarations.add(declared.declaration());
        }
        var result = new java.util.LinkedHashMap<String, graalphp.runtime.ObjectModel.Definition>();
        for (var declaration : declarations) result.put(declaration.name().toLowerCase(java.util.Locale.ROOT), definition(language, source, null, declaration, false));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static graalphp.runtime.ObjectModel.Definition definition(PhpLanguage language, Source source, Path path, Ir.ClassDeclaration declaration, boolean strictTypes) {
        var methods = new java.util.LinkedHashMap<String, graalphp.runtime.ObjectModel.Method>();
        for (var method : declaration.methods()) {
            var value = method.function();
            var compiled = function(language, source, path, value.name(), value.parameters(), value.body(),
                    false, declaration.name(), value.returnType(), strictTypes, declaration.kind() == Ir.TypeKind.TRAIT ? declaration.name() : null)
                    .annotated(source, value, source.isInternal() && graalphp.runtime.IterationApi.tentativeType(declaration.name()));
            String key = value.name().toLowerCase(java.util.Locale.ROOT);
            if (methods.putIfAbsent(key, new graalphp.runtime.ObjectModel.Method(compiled, method.shared(), method.visibility(), method.abstractMethod(), method.finalMethod())) != null) {
                throw new PhpError("Cannot redeclare method " + declaration.name() + "::" + value.name());
            }
        }
        var constants = new java.util.ArrayList<>(declaration.constants());
        var interfaces = new java.util.ArrayList<>(declaration.interfaces());
        if (declaration.kind() == Ir.TypeKind.ENUM) {
            interfaces.add("UnitEnum");
            if (declaration.backingType() != null) interfaces.add("BackedEnum");
            for (var enumCase : declaration.cases()) constants.add(new Ir.ClassConstantDeclaration(enumCase.name(),
                    new Ir.EnumCaseValue(enumCase.name()), "public", false, null, enumCase.attributes()));
            for (var generated : graalphp.runtime.EnumApi.generatedMethods().methods()) {
                var value = generated.function();
                String key = value.name().toLowerCase(java.util.Locale.ROOT);
                if (declaration.backingType() == null && !key.equals("cases")) continue;
                if (methods.containsKey(key)) throw PhpError.fatal("Cannot redeclare enum method " + declaration.name() + "::" + value.name());
                Source builtinSource = graalphp.runtime.EnumApi.methodSource();
                var body = function(language, builtinSource, null, value.name(), value.parameters(), value.body(), false,
                        declaration.name(), value.returnType(), false);
                var builtin = new Execution.Function(body.name(), body.parameters(), body.target(), body.file(), body.owner(), body.returnType(), true);
                methods.put(key, new graalphp.runtime.ObjectModel.Method(builtin, true, "public", false, false));
            }
        }
        return new graalphp.runtime.ObjectModel.Definition(declaration.name(), declaration.parent(),
                declaration.properties(), java.util.Collections.unmodifiableMap(methods), declaration.kind(), declaration.abstractType(),
                declaration.finalType() || declaration.kind() == Ir.TypeKind.ENUM, List.copyOf(interfaces), declaration.traits(),
                List.copyOf(constants), declaration.backingType(), declaration.cases(), declaration.attributes());
    }
    private static Execution.Function function(PhpLanguage language, Source source, Path path, String name,
            List<Ir.Parameter> parameters, List<Ir.Statement> body, boolean main, String owner, String returnType, boolean strictTypes) {
        return function(language, source, path, name, parameters, body, main, owner, returnType, strictTypes, null);
    }
    private static Execution.Function function(PhpLanguage language, Source source, Path path, String name,
            List<Ir.Parameter> parameters, List<Ir.Statement> body, boolean main, String owner, String returnType, boolean strictTypes, String traitName) {
        var roots = PhpRootGen.create(language, BytecodeConfig.WITH_SOURCE, builder -> {
            builder.beginRoot();
            builder.beginSource(source);
            var compiler = new PhpCompiler(builder, language, source, path, owner, name, strictTypes, traitName);
            for (var statement : body) compiler.statement(statement);
            builder.beginReturn();
            builder.beginCheckReturn();
            if (main) builder.emitLoadConstant(1L); else builder.emitLoadNull();
            builder.endCheckReturn();
            builder.endReturn();
            builder.endSource();
            builder.endRoot();
        });
        var root = roots.getNode(0); root.name = name;
        return new Execution.Function(name, parameters, root.getCallTarget(), path, owner, returnType, false, strictTypes);
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void statement(Ir.Statement statement) {
        var b = builder;
        b.beginSourceSection(statement.start(), statement.length());
        switch (statement.form()) {
            case Ir.DeclareClass declared -> {
                var definition = definition(language, source, path, declared.declaration(), strictTypes);
                b.beginDrop();
                suspended(() -> b.emitDeclareClass(definition));
                b.endDrop();
            }
            case Ir.Block block -> { b.beginBlock(); for (var child : block.statements()) statement(child); b.endBlock(); }
            case Ir.ExpressionStatement expression -> { b.beginDrop(); expression(expression.expression()); b.endDrop(); }
            case Ir.Echo echo -> { for (var expression : echo.expressions()) { b.beginEcho(); expression(expression); b.endEcho(); } }
            case Ir.Return returned -> { b.beginReturn(); b.beginCheckReturn(); expression(returned.value()); b.endCheckReturn(); b.endReturn(); }
            case Ir.If conditional -> {
                if (conditional.no() == null) b.beginIfThen(); else b.beginIfThenElse();
                truth(conditional.condition()); statement(conditional.yes());
                if (conditional.no() == null) b.endIfThen(); else { statement(conditional.no()); b.endIfThenElse(); }
            }
            case Ir.While loop -> loop(loop.condition(), loop.body());
            case Ir.For loop -> forLoop(loop);
            case Ir.DoWhile loop -> doLoop(loop);
            case Ir.Foreach loop -> foreach(loop);
            case Ir.Try guarded -> {
                if (guarded.cleanup() != null) b.beginTryFinally(() -> {
                    b.beginIfThen(); b.emitCanRunCleanup(); statement(guarded.cleanup()); b.endIfThen();
                });
                if (guarded.handler() != null) b.beginTryCatch();
                statement(guarded.body());
                if (guarded.handler() != null) {
                    b.beginBlock();
                    b.beginIfThen(); b.beginUnary("!"); b.beginMatchesCatch(guarded.caughtType());
                    b.emitLoadException(); b.endMatchesCatch(); b.endUnary();
                    b.beginThrow(); b.emitLoadException(); b.endThrow(); b.endIfThen();
                    b.beginDrop(); b.beginWrite(); b.emitVariable(guarded.caught()); b.emitLoadException(); b.endWrite(); b.endDrop();
                    statement(guarded.handler()); b.endBlock(); b.endTryCatch();
                }
                if (guarded.cleanup() != null) b.endTryFinally();
            }
            case Ir.Throw thrown -> { b.beginThrow(); expression(thrown.value()); b.endThrow(); }
            case Ir.Unset unset -> { for (var variable : unset.locations()) { b.beginUnset(); location(variable); b.endUnset(); } }
            case Ir.Global global -> { for (String name : global.names()) b.emitGlobal(name); }
            case Ir.Break ignored -> { if (loops.isEmpty()) throw new PhpError("break outside a loop"); b.emitBranch(loops.peek().exit()); }
            case Ir.Continue ignored -> { if (loops.isEmpty()) throw new PhpError("continue outside a loop"); b.emitBranch(loops.peek().next()); }
        }
        b.endSourceSection();
    }
    private void truth(Ir.Expression expression) { builder.beginTruth(); expression(expression); builder.endTruth(); }
    private void discard(List<Ir.Expression> expressions) {
        for (var expression : expressions) { builder.beginDrop(); expression(expression); builder.endDrop(); }
    }
    private void forLoop(Ir.For loop) {
        var b = builder;
        b.beginBlock();
        discard(loop.initialize());
        var exit = b.createLabel();
        b.beginWhile();
        b.beginBlock();
        if (loop.conditions().isEmpty()) b.emitLoadConstant(true);
        else {
            discard(loop.conditions().subList(0, loop.conditions().size() - 1));
            truth(loop.conditions().getLast());
        }
        b.endBlock();
        b.beginBlock();
        var next = b.createLabel();
        loops.push(new Loop(exit, next));
        checkpoint();
        statement(loop.body());
        b.emitLabel(next);
        discard(loop.update());
        loops.pop();
        b.endBlock();
        b.endWhile();
        b.emitLabel(exit);
        b.endBlock();
    }
    private void doLoop(Ir.DoWhile loop) {
        var b = builder;
        b.beginBlock();
        var exit = b.createLabel();
        var again = b.createLocal();
        b.beginStoreLocal(again); b.emitLoadConstant(true); b.endStoreLocal();
        b.beginWhile(); b.emitLoadLocal(again);
        b.beginBlock();
        var next = b.createLabel();
        loops.push(new Loop(exit, next));
        checkpoint();
        statement(loop.body());
        b.emitLabel(next);
        b.beginStoreLocal(again); truth(loop.condition()); b.endStoreLocal();
        loops.pop();
        b.endBlock(); b.endWhile(); b.emitLabel(exit); b.endBlock();
    }
    private void loop(Ir.Expression condition, Ir.Statement body) {
        var b = builder;
        b.beginBlock(); var exit = b.createLabel();
        b.beginWhile(); truth(condition);
        b.beginBlock(); var next = b.createLabel(); loops.push(new Loop(exit, next));
        checkpoint(); statement(body); b.emitLabel(next); loops.pop(); b.endBlock();
        b.endWhile(); b.emitLabel(exit); b.endBlock();
    }
    private void foreach(Ir.Foreach loop) {
        var b = builder;
        b.beginBlock(); var cursor = b.createLocal();
        b.beginStoreLocal(cursor); b.beginOpenCursor(loop.reference());
        if (loop.reference() && isLocation(loop.source())) location(loop.source()); else expression(loop.source());
        b.endOpenCursor(); b.endStoreLocal();
        b.beginTryFinally(() -> { b.beginCloseCursor(); b.emitLoadLocal(cursor); b.endCloseCursor(); });
        b.beginBlock(); var exit = b.createLabel();
        b.beginWhile();
        suspended(() -> {
            b.beginNext(); b.emitLoadLocal(cursor); b.emitVariable(loop.value());
            if (loop.key() == null) b.emitLoadNull(); else b.emitVariable(loop.key());
            b.endNext();
        });
        b.beginBlock(); var next = b.createLabel(); loops.push(new Loop(exit, next));
        checkpoint(); statement(loop.body()); b.emitLabel(next); loops.pop(); b.endBlock();
        b.endWhile(); b.emitLabel(exit); b.endBlock(); b.endTryFinally(); b.endBlock();
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void expression(Ir.Expression expression) {
        var b = builder;
        switch (expression) {
            case Ir.Literal literal -> { if (literal.value() == null) b.emitLoadNull(); else b.emitLoadConstant(literal.value()); }
            case Ir.Variable variable -> { b.beginRead(); b.emitVariable(variable.name()); b.endRead(); }
            case Ir.Property property -> { b.beginRead(); location(property); b.endRead(); }
            case Ir.StaticProperty property -> { b.beginRead(); location(property); b.endRead(); }
            case Ir.Constant constant -> {
                switch (constant.name()) {
                    case "__FILE__" -> b.emitLoadConstant(path == null ? source.getName() : path.toString());
                    case "__DIR__" -> b.emitLoadConstant(path == null ? "" : path.getParent().toString());
                    case "__CLASS__" -> b.emitLexicalClass();
                    case "__TRAIT__" -> b.emitLoadConstant(traitName == null ? "" : traitName);
                    case "__FUNCTION__" -> b.emitLoadConstant(functionName);
                    case "__METHOD__" -> b.emitLoadConstant(owner == null ? functionName : owner + "::" + functionName);
                    default -> b.emitNamedConstant(constant.name());
                }
            }
            case Ir.ClassName name -> b.emitResolvedClassName(name.type());
            case Ir.ClassConstant constant -> {
                b.beginReadClassConstant(constant.name());
                if (constant.name().equals("class")) expression(constant.type()); else ensureClass(constant.type());
                b.endReadClassConstant();
            }
            case Ir.InstanceOf instance -> {
                b.beginIsInstanceOf(); expression(instance.value()); expression(instance.type()); b.endIsInstanceOf();
            }
            case Ir.Index index -> {
                if (isLocation(index)) { b.beginRead(); location(index); b.endRead(); }
                else { b.beginLookup(); expression(index.array()); expression(index.key()); b.endLookup(); }
            }
            case Ir.Assign assignment -> {
                if (assignment.reference()) { b.beginBind(); location(assignment.target()); location(assignment.value()); b.endBind(); }
                else { b.beginWrite(); location(assignment.target()); expression(assignment.value()); b.endWrite(); }
            }
            case Ir.CompoundAssign assignment -> compound(assignment);
            case Ir.Increment increment -> {
                b.beginIncrement(increment.delta(), increment.before()); location(increment.target()); b.endIncrement();
            }
            case Ir.Coalesce coalesce -> coalesce(coalesce.left(), coalesce.right());
            case Ir.Conditional conditional -> {
                if (conditional.yes() != null) {
                    b.beginConditional(); truth(conditional.condition()); expression(conditional.yes()); expression(conditional.no()); b.endConditional();
                } else {
                    b.beginBlock(); var value = b.createLocal();
                    b.beginStoreLocal(value); expression(conditional.condition()); b.endStoreLocal();
                    b.beginConditional(); b.beginTruthyKeep(); b.emitLoadLocal(value); b.endTruthyKeep();
                    b.emitLoadLocal(value);
                    b.beginBlock(); b.beginDrop(); b.emitLoadLocal(value); b.endDrop(); expression(conditional.no()); b.endBlock();
                    b.endConditional(); b.endBlock();
                }
            }
            case Ir.Unary unary -> { b.beginUnary(unary.operator()); expression(unary.value()); b.endUnary(); }
            case Ir.Binary binary -> {
                if (binary.operator().equals("&&") || binary.operator().equals("||")) {
                    b.beginConditional(); truth(binary.left());
                    if (binary.operator().equals("&&")) { truth(binary.right()); b.emitLoadConstant(false); }
                    else { b.emitLoadConstant(true); truth(binary.right()); }
                    b.endConditional();
                } else { b.beginBinary(binary.operator()); expression(binary.left()); expression(binary.right()); b.endBinary(); }
            }
            case Ir.Call call -> {
                String simple = call.name().substring(call.name().lastIndexOf('\\') + 1);
                if (simple.equals("isset") || simple.equals("empty")) specialRead(simple, call.arguments(), 0);
                else call(call);
            }
            case Ir.NamedArgument ignored -> throw new graalphp.runtime.PhpError("Named argument outside a call");
            case Ir.EnumCaseValue ignored -> throw new PhpError("Enum case values are declaration constants");
            case Ir.Match match -> match(match);
            case Ir.ThrowExpression thrown -> { b.beginThrowValue(); expression(thrown.value()); b.endThrowValue(); }
            case Ir.Clone clone -> cloneObject(clone.value());
            case Ir.DynamicStaticCall call -> memberCall("static", call.name(), call.type(), call.arguments());
            case Ir.DynamicCall call -> memberCall("callable", "", call.callable(), call.arguments());
            case Ir.MethodCall call -> memberCall("method", call.name(), call.object(), call.arguments());
            case Ir.StaticCall call -> memberCall("static", call.name(), new Ir.Literal(call.type()), call.arguments());
            case Ir.Construct construct -> construct(new Ir.Literal(construct.type()), construct.arguments());
            case Ir.DynamicConstruct construct -> construct(construct.type(), construct.arguments());
            case Ir.Closure closure -> {
                var value = closure.function();
                var compiled = function(language, source, path, value.name(), value.parameters(), value.body(), false, owner, value.returnType(), strictTypes, traitName).annotated(source, value, false);
                b.emitCreateClosure(new graalphp.runtime.ObjectModel.ClosureTemplate(compiled, closure.captures(), closure.arrow()));
            }
            case Ir.ArrayLiteral array -> {
                b.beginArray();
                for (var item : array.entries()) {
                    b.beginItem(item.reference());
                    if (item.key() == null) b.emitLoadConstant(PhpRoot.Append.KEY); else expression(item.key());
                    if (item.reference()) location(item.value()); else expression(item.value());
                    b.endItem();
                }
                b.endArray();
            }
        }
    }
    private void call(Ir.Call call) {
        suspended(() -> {
            builder.beginInvoke(call.name(), call.globalFallback());
            arguments(call.arguments());
            builder.endInvoke();
        });
    }
    private void memberCall(String kind, String name, Ir.Expression receiver, List<Ir.Expression> args) {
        suspended(() -> {
            builder.beginInvokeMember(kind, name);
            if (kind.equals("static")) ensureClass(receiver); else expression(receiver);
            arguments(args);
            builder.endInvokeMember();
        });
    }
    private void ensureClass(Ir.Expression type) {
        suspended(() -> {
            builder.beginEnsureClass(); expression(type); builder.endEnsureClass();
        });
    }
    private void construct(Ir.Expression type, List<Ir.Expression> args) {
        var b = builder;
        b.beginBlock(); var object = b.createLocal();
        b.beginStoreLocal(object);
        b.beginCreateObject(); ensureClass(type); b.endCreateObject();
        b.endStoreLocal();
        b.beginDrop();
        suspended(() -> {
            b.beginInvokeMember("constructor", "__construct"); b.emitLoadLocal(object);
            arguments(args); b.endInvokeMember();
        });
        b.endDrop(); b.emitLoadLocal(object); b.endBlock();
    }
    private void match(Ir.Match match) {
        var b = builder;
        b.beginBlock(); var subject = b.createLocal(); var result = b.createLocal();
        boolean variable = match.subject() instanceof Ir.Variable;
        b.beginStoreLocal(subject);
        if (variable) location(match.subject()); else expression(match.subject());
        b.endStoreLocal();
        var arms = match.arms().stream().filter(arm -> !arm.conditions().isEmpty()).toList();
        var fallback = match.arms().stream().filter(arm -> arm.conditions().isEmpty()).findFirst();
        b.beginTryFinally(() -> { b.beginDrop(); b.emitLoadLocal(subject); b.endDrop(); });
        b.beginStoreLocal(result); matchArm(subject, variable, arms, 0, fallback.map(Ir.MatchArm::value).orElse(null)); b.endStoreLocal();
        b.endTryFinally(); b.emitLoadLocal(result); b.endBlock();
    }
    private void matchArm(com.oracle.truffle.api.bytecode.BytecodeLocal subject, boolean variable, List<Ir.MatchArm> arms, int index, Ir.Expression fallback) {
        var b = builder;
        if (index == arms.size()) {
            if (fallback == null) { b.beginUnmatchedMatch(); matchSubject(subject, variable); b.endUnmatchedMatch(); }
            else expression(fallback);
            return;
        }
        var arm = arms.get(index);
        b.beginConditional(); matchCondition(subject, variable, arm.conditions(), 0);
        expression(arm.value()); matchArm(subject, variable, arms, index + 1, fallback); b.endConditional();
    }
    private void matchCondition(com.oracle.truffle.api.bytecode.BytecodeLocal subject, boolean variable, List<Ir.Expression> conditions, int index) {
        var b = builder;
        if (index + 1 < conditions.size()) b.beginConditional();
        b.beginBinary("==="); expression(conditions.get(index)); matchSubject(subject, variable); b.endBinary();
        if (index + 1 < conditions.size()) { b.emitLoadConstant(true); matchCondition(subject, variable, conditions, index + 1); b.endConditional(); }
    }
    private void matchSubject(com.oracle.truffle.api.bytecode.BytecodeLocal subject, boolean variable) {
        if (variable) builder.beginRead(); else builder.beginRetain();
        builder.emitLoadLocal(subject);
        if (variable) builder.endRead(); else builder.endRetain();
    }
    private void cloneObject(Ir.Expression value) {
        var b = builder;
        b.beginBlock(); var object = b.createLocal();
        b.beginStoreLocal(object); b.beginCloneObject(); expression(value); b.endCloneObject(); b.endStoreLocal();
        b.beginDrop(); suspended(() -> {
            b.beginInvokeMember("clone", "__clone"); b.emitLoadLocal(object); b.endInvokeMember();
        }); b.endDrop(); b.emitLoadLocal(object); b.endBlock();
    }
    private void arguments(List<Ir.Expression> arguments) {
        var b = builder;
        for (var argument : arguments) {
            String name = null;
            if (argument instanceof Ir.NamedArgument named) {
                name = named.name(); argument = named.value(); b.beginArgumentName(name);
            }
            if (isLocation(argument)) { b.beginArgumentLocation(); location(argument); b.endArgumentLocation(); }
            else { b.beginArgumentValue(); expression(argument); b.endArgumentValue(); }
            if (name != null) b.endArgumentName();
        }
    }
    private void suspended(Runnable operation) {
        var b = builder;
        b.beginBlock(); var result = b.createLocal();
        b.beginStoreLocal(result); operation.run(); b.endStoreLocal();
        b.beginIfThen(); b.beginSuspended(); b.emitLoadLocal(result); b.endSuspended();
        b.beginStoreLocal(result); b.beginYield(); b.emitLoadLocal(result); b.endYield(); b.endStoreLocal(); b.endIfThen();
        b.beginResume(); b.emitLoadLocal(result); b.endResume(); b.endBlock();
    }
    private void checkpoint() {
        builder.beginDrop(); suspended(() -> builder.emitPoll()); builder.endDrop();
    }
    private void quietRead(Ir.Expression value) { quietRead(value, false); }
    private void quietRead(Ir.Expression value, boolean probe) {
        if (isLocation(value)) {
            if (probe) builder.beginProbeRead(); else builder.beginQuietRead();
            location(value);
            if (probe) builder.endProbeRead(); else builder.endQuietRead();
        } else expression(value);
    }
    private void coalesce(Ir.Expression left, Ir.Expression right) {
        var b = builder;
        b.beginBlock(); var value = b.createLocal();
        b.beginStoreLocal(value); quietRead(left); b.endStoreLocal();
        b.beginConditional(); b.beginNonNull(); b.emitLoadLocal(value); b.endNonNull();
        b.emitLoadLocal(value); expression(right); b.endConditional(); b.endBlock();
    }
    private void specialRead(String name, List<Ir.Expression> args, int index) {
        if (args.isEmpty()) throw new PhpError(name + " requires arguments");
        var b = builder;
        if (name.equals("empty")) {
            if (args.size() != 1) throw new PhpError("empty requires one argument");
            b.beginUnary("!"); quietRead(args.getFirst(), true); b.endUnary();
        } else {
            b.beginConditional(); b.beginIsSet(); quietRead(args.get(index), true); b.endIsSet();
            if (index + 1 == args.size()) b.emitLoadConstant(true); else specialRead(name, args, index + 1);
            b.emitLoadConstant(false); b.endConditional();
        }
    }
    private void compound(Ir.CompoundAssign assignment) {
        var b = builder;
        b.beginBlock(); var target = b.createLocal(); var old = b.createLocal();
        b.beginStoreLocal(target); location(assignment.target()); b.endStoreLocal();
        b.beginStoreLocal(old);
        if (assignment.operator().equals("??")) b.beginQuietRead(); else b.beginReadForAssignOperation();
        b.emitLoadLocal(target);
        if (assignment.operator().equals("??")) b.endQuietRead(); else b.endReadForAssignOperation();
        b.endStoreLocal();
        if (assignment.operator().equals("??")) {
            b.beginConditional(); b.beginNonNull(); b.emitLoadLocal(old); b.endNonNull();
            b.emitLoadLocal(old);
            b.beginWrite(); b.emitLoadLocal(target); expression(assignment.value()); b.endWrite();
            b.endConditional();
        } else {
            b.beginWrite(); b.emitLoadLocal(target);
            b.beginCompoundValue(assignment.operator()); b.emitLoadLocal(target); b.emitLoadLocal(old);
            expression(assignment.value()); b.endCompoundValue();
            b.endWrite();
        }
        b.endBlock();
    }
    private boolean isLocation(Ir.Expression expression) {
        return expression instanceof Ir.Variable || expression instanceof Ir.Property || expression instanceof Ir.StaticProperty
                || expression instanceof Ir.Index index && isLocation(index.array());
    }
    private void location(Ir.Expression expression) {
        var b = builder;
        if (expression instanceof Ir.Variable variable) b.emitVariable(variable.name());
        else if (expression instanceof Ir.Property property) {
            b.beginProperty(property.name()); expression(property.object()); b.endProperty();
        } else if (expression instanceof Ir.StaticProperty property) {
            b.beginBlock();
            b.beginDrop(); ensureClass(new Ir.Literal(property.type())); b.endDrop();
            b.emitStaticProperty(property.type(), property.name());
            b.endBlock();
        }
        else if (expression instanceof Ir.Index index) {
            b.beginElement(); location(index.array());
            if (index.key() == null) b.emitLoadConstant(PhpRoot.Append.KEY); else expression(index.key());
            b.endElement();
        } else throw new PhpError("Expression is not a writable location");
    }
}
