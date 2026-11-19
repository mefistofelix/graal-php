package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

public final class TypedPropertyReferenceTest {
    private static final List<Case> CASES = List.of(
        new Case("nullable-and-uninitialized", """
            class Values { public int $number; public ?int $nullable; }
            $object = new Values();
            try { $alias =& $object->number; } catch (Error $error) { echo $error->getMessage(), '|'; }
            $alias =& $object->nullable; echo $alias === null, ':';
            $alias = '42'; echo $object->nullable === 42, ':';
            try { $alias = []; } catch (TypeError $error) { echo get_class($error), ':'; }
            echo $alias;
            """),
        new Case("strict-reference-assignment", """
            declare(strict_types=1);
            class Values { public int $number = 3; }
            $object = new Values(); $alias =& $object->number;
            try { $alias = '42'; } catch (TypeError $error) { echo get_class($error), ':'; }
            echo $alias, ':';
            $alias = 5; echo $object->number;
            """),
        new Case("compatible-sources-and-inconsistent-coercion", """
            class Values { public int $number = 3; public int|string $union = 'start'; }
            $object = new Values();
            $object->union =& $object->number; $alias =& $object->number;
            try { $alias = '6'; } catch (TypeError $error) { echo get_class($error), ':'; }
            $object->number = '7'; echo $alias === 7, ':';
            try { $object->union = '8'; } catch (TypeError $error) { echo get_class($error), ':'; }
            echo $object->union;
            """),
        new Case("incompatible-rebind-is-atomic", """
            class Values { public ?int $number = 7; public string $text = 'initial'; }
            $object = new Values(); $alias =& $object->number;
            try { $object->text =& $alias; } catch (TypeError $error) { echo get_class($error), ':'; }
            echo $object->text, ':', $object->number, ':';
            $alias = 8; echo $object->number;
            """),
        new Case("bind-coerces-existing-untyped-aliases", """
            class Values { public int $number; }
            $object = new Values(); $value = '5'; $alias =& $value;
            $object->number =& $value;
            echo $alias === 5, ':';
            $object->number = 9; echo $value;
            """),
        new Case("unset-removes-only-one-property-source", """
            class Values { public ?int $left = 1; public int $right = 2; }
            $object = new Values(); $alias =& $object->right;
            $object->left =& $alias; unset($object->right);
            $alias = null; echo $object->left === null, ':';
            unset($object->left);
            $alias = []; echo is_array($alias);
            """),
        new Case("clone-retains-own-type-source", """
            class Values { public int $number = 7; }
            $object = new Values(); $alias =& $object->number;
            $copy = clone $object; unset($object);
            $alias = '6'; echo $alias === 6, ':', $copy->number, ':';
            unset($copy);
            $alias = []; echo is_array($alias);
            """),
        new Case("dead-cycle-removes-type-source", """
            class Values { public int $number = 7; public $cycle; }
            $object = new Values(); $object->cycle = $object;
            $alias =& $object->number; unset($object);
            try { $alias = []; } catch (TypeError $error) { echo get_class($error), ':'; }
            echo gc_collect_cycles(), ':';
            $alias = []; echo is_array($alias);
            """),
        new Case("typed-reference-return-and-static-property", """
            class Values { public static int $number = 3; }
            function &get_value() { return Values::$number; }
            $alias =& get_value();
            try { $alias = []; } catch (TypeError $error) { echo get_class($error), ':'; }
            $alias = '12'; echo Values::$number === 12;
            """),
        new Case("suspended-argument-keeps-typed-constraint", """
            class Values { public int $number = 3; }
            function change(&$value) {
                Async\\suspend(); $value = '16';
                try { $value = []; } catch (TypeError $error) { echo get_class($error), ':'; }
            }
            $object = new Values(); change($object->number);
            echo $object->number === 16;
            """, true)
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "typed-property-reference");
    }
}
