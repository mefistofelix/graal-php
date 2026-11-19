package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

public final class ScalarCastTest {
    private static final List<Case> CASES = List.of(
        new Case("integer-coercion", """
            foreach ([null, false, true, -3.9, 3.9, [], [9], '  -12.7tail', '1e3ignored', 'nonnumeric'] as $v) {
                echo (int)$v, '|';
            }
            """),
        new Case("numeric-prefix-and-large-strings", """
            foreach (['9223372036854775808', '-9223372036854775809', '1e400', '1e-999',
                      '0xAB', '+ 2', '.5x', '5e-', "7\\0remaining"] as $v) {
                echo (int)$v, ':', (string)(float)$v, '|';
            }
            """),
        new Case("floating-output-and-precision", """
            foreach ([0.0, -0.0, 1.0, 1e-5, 1e-4, 1e13, 1e14, 1.2345678901234574, 1e308] as $v) {
                echo (string)$v, '|';
            }
            """),
        new Case("out-of-range-double-integer-result", """
            error_reporting(0);
            foreach ([9223372036854775808.0, -9223372036854777856.0, 18446744073709551616.0,
                      (float)'1e999', (float)'-1e999'] as $v) {
                echo (int)$v, '|';
            }
            """),
        new Case("boolean-and-float-targets", """
            foreach (['0', '0.0', '', -0.0, [], [0], null, false, true, new stdClass()] as $v) {
                echo (bool)$v ? 'T' : 'F';
            }
            echo ':', (float)' .25tail' === 0.25, ':', (float)[] === 0.0, ':', (float)true === 1.0;
            """),
        new Case("binary-string-preserved-and-scanned", """
            $binary = hex2bin('3132ff0041');
            echo bin2hex((string)$binary), ':', (int)$binary, ':', (float)$binary === 12.0;
            """),
        new Case("binary-literals-with-interpolation", """
            $part = 'A';
            echo bin2hex((string)"π$part\\xff\\000\\101"), ':', bin2hex("\\xE2\\x82\\xAC"), ':', "\\q";
            """),
        new Case("decimal-literals", """
            echo .25 === 0.25, ':', 2. === 2.0, ':', 1.e-1 === 0.1, ':', 9223372036854775808 === 9223372036854775808.0;
            """),
        new Case("cast-precedence-and-postfix", """
            $values = ['2.7']; $i = 0;
            echo ( int )$values[$i++] * 2, ':', $i, ':', (int)-2.7 * 2, ':', (BOOL)'0' === false;
            """),
        new Case("constant-expressions-and-weak-call-types", """
            class Constants { const VALUE = (int)'42.7'; }
            function argument($value = (float)'7.5') { return $value === 7.5; }
            function get_string(string $text): string { return $text; }
            echo Constants::VALUE, ':', argument(), ':', get_string(1.0);
            """),
        new Case("cast-warnings-and-errors", """
            error_reporting(0);
            $value = (string)[1]; echo $value, ':', error_get_last()['message'], '|';
            $value = (int)new stdClass(); echo $value, ':', error_get_last()['message'], '|';
            try { echo (string)new stdClass(); } catch (Error $error) { echo $error->getMessage(); }
            """),
        new Case("stringable-trait-and-weak-return", """
            trait Rendered { public function __toString() { return 8; } }
            class Text { use Rendered; }
            class Derived extends Text {}
            function render(Stringable $value) { return (string)$value; }
            $value = new Derived();
            echo $value instanceof Stringable, ':', render($value);
            """),
        new Case("strict-magic-return-type", """
            declare(strict_types=1);
            class Text { public function __toString() { return 3; } }
            try { echo (string)new Text(); } catch (TypeError $error) { echo get_class($error); }
            """),
        new Case("invalid-magic-return-type", """
            class Text { public function __toString(): int { return 3; } }
            """, "string"),
        new Case("suspended-object-cast", """
            class Text {
                public function __toString(): string { Async\\suspend(); return 'value'; }
                public function __destruct() { echo 'D|'; }
            }
            echo (string)new Text(), '|';
            """, true)
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "scalar-cast");
    }
}
