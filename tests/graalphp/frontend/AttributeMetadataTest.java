package graalphp.frontend;

import com.oracle.truffle.api.source.Source;
import java.util.List;
import java.util.Objects;

/** Attributes are preserved as syntax metadata; unresolved argument expressions are not executed. */
public final class AttributeMetadataTest {
    private static int checks;
    private static final String PROGRAM = """
        <?php
        declare(strict_types=1);
        namespace Demo;
        use Vendor\\Tag as Label;
        # Ordinary comments remain comments.
        #[Label(1, mode: Missing::VALUE), \\Second]
        #[Label(2)]
        class C {
            #[Label('property')] public $property = 1;
            #[Label('constant')] public const VALUE = 2;
            #[Label('method')] public function method(#[Label('parameter')] int $value): int { return $value; }
        }
        #[Label('enum')] enum E { #[Label('case')] case A; }
        #[Label('function')] function f(#[Label('parameter')] $value) { return $value; }
        $f = #[Label('closure')] function(#[Label('parameter')] $value) { return $value; };
        $g = #[Label('arrow')] fn(#[Label('parameter')] $value) => $value;
        """;

    public static void main(String[] args) {
        var source = Source.newBuilder("php", PROGRAM, "attribute-metadata.php").build();
        var unit = new Parser(source).parse();
        check(unit.strictTypes(), "source mode retained alongside attributes");
        var type = unit.classes().stream().filter(value -> value.name().equals("Demo\\C")).findFirst().orElseThrow();
        equal(List.of("Vendor\\Tag", "Second", "Vendor\\Tag"), type.attributes().stream().map(Ir.Attribute::name).toList(), "groups, aliases and global names");
        var first = type.attributes().getFirst();
        equal("Label(1, mode: Missing::VALUE)", PROGRAM.substring(first.start(), first.start() + first.length()), "attribute source span");
        equal(2, first.arguments().size(), "argument count");
        equal(new Ir.Literal(1L), first.arguments().getFirst(), "positional argument syntax");
        var named = (Ir.NamedArgument) first.arguments().get(1);
        equal("mode", named.name(), "named argument preserved");
        check(named.value() instanceof Ir.ClassConstant, "unresolved class constant remains syntax");
        var constant = (Ir.ClassConstant) named.value();
        equal(new Ir.Literal("Demo\\Missing"), constant.type(), "constant class name resolved lexically");
        equal("VALUE", constant.name(), "constant member name");
        marker(type.properties().getFirst().attributes(), "property");
        marker(type.constants().getFirst().attributes(), "constant");
        var method = type.methods().getFirst().function();
        marker(method.attributes(), "method");
        marker(method.parameters().getFirst().attributes(), "parameter");
        equal("int", method.returnType(), "method return type unchanged");
        var enumeration = unit.statements().stream().filter(value -> value.form() instanceof Ir.DeclareClass)
                .map(value -> ((Ir.DeclareClass) value.form()).declaration())
                .filter(value -> value.name().equals("Demo\\E")).findFirst().orElseThrow();
        marker(enumeration.attributes(), "enum");
        marker(enumeration.cases().getFirst().attributes(), "case");
        var function = unit.functions().getFirst();
        marker(function.attributes(), "function");
        marker(function.parameters().getFirst().attributes(), "parameter");
        var expressions = unit.statements().stream().filter(value -> value.form() instanceof Ir.ExpressionStatement).toList();
        equal(2, expressions.size(), "attributes do not emit standalone expressions");
        equal(3, unit.statements().size(), "enum declaration remains a runtime declaration");
        var closure = closure(expressions.getFirst());
        marker(closure.function().attributes(), "closure");
        marker(closure.function().parameters().getFirst().attributes(), "parameter");
        check(!closure.arrow(), "ordinary closure kind");
        var arrow = closure(expressions.get(1));
        marker(arrow.function().attributes(), "arrow");
        marker(arrow.function().parameters().getFirst().attributes(), "parameter");
        check(arrow.arrow(), "arrow closure kind");
        immutable(type.attributes(), "attribute list immutable");
        immutable(first.arguments(), "attribute arguments immutable");
        System.out.println("PASS: " + checks + " attribute metadata and source-range checks");
    }

    private static Ir.Closure closure(Ir.Statement statement) {
        return (Ir.Closure) ((Ir.Assign) ((Ir.ExpressionStatement) statement.form()).expression()).value();
    }
    private static void marker(List<Ir.Attribute> attributes, String value) {
        equal(1, attributes.size(), value + " attribute count");
        equal("Vendor\\Tag", attributes.getFirst().name(), value + " attribute name");
        equal(new Ir.Literal(value), attributes.getFirst().arguments().getFirst(), value + " argument");
    }
    private static void immutable(List<?> value, String label) {
        boolean rejected = false;
        try { value.clear(); } catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected, label);
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static void equal(Object expected, Object actual, String label) {
        check(Objects.equals(expected, actual), label + ": expected=" + expected + ", actual=" + actual);
    }
}
