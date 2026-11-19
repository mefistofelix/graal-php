package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

/** Observe the guest heap through the same PHP programs on the reference and target runtimes. */
public final class WeakReferenceTest {
    private static final List<Case> CASES = List.of(
        new Case("identity-and-final-owner", """
            $object = new stdClass(); $copy = $object;
            $first = WeakReference::create($object);
            $second = WeakReference::create(object: $object);
            echo get_class($first), ':', $first === $second, ':', $first->get() === $object;
            unset($object); echo ':', $second->get() === $copy;
            unset($copy); echo ':', $first->get() === null;
            """),
        new Case("weak-target-temporary", """
            class Example {}
            $factory = 'WeakReference::create';
            $weak = $factory(new Example());
            echo $weak->get() === null;
            """),
        new Case("weak-handle-can-die-first", """
            $target = new stdClass(); $first = WeakReference::create($target);
            $weakHandle = WeakReference::create($first);
            unset($first); echo $weakHandle->get() === null, ':';
            $second = WeakReference::create($target);
            echo $second->get() === $target, ':';
            unset($target); echo $second->get() === null;
            """),
        new Case("get-transfers-strong-owner", """
            $object = new stdClass();
            $weak = WeakReference::create($object);
            $last = $weak->get(); unset($object);
            echo $weak->get() === $last, ':';
            unset($last); echo $weak->get() === null;
            """),
        new Case("cycle-persists-until-collection", """
            class Node { public $edge; }
            function makeCycle() {
                $node = new Node(); $node->edge = $node;
                return WeakReference::create($node);
            }
            $weak = makeCycle();
            echo $weak->get() !== null, ':', gc_collect_cycles(), ':', $weak->get() === null, ':', gc_collect_cycles();
            """),
        new Case("guest-count-excludes-reference-cells", """
            $a = []; $a[] =& $a; unset($a);
            echo gc_collect_cycles(), ':';
            class Node { public $data; }
            $node = new Node(); $node->data = [$node]; unset($node);
            echo gc_collect_cycles();
            """),
        new Case("live-tail-after-cycle-collection", """
            class Node { public $self; public $tail; }
            $tail = new stdClass();
            $weakTail = WeakReference::create($tail);
            $node = new Node(); $node->self = $node; $node->tail = $tail;
            unset($node); echo gc_collect_cycles(), ':', $weakTail->get() === $tail;
            unset($tail); echo ':', $weakTail->get() === null;
            """),
        new Case("manual-collection-while-disabled", """
            echo gc_enabled(), ':';
            gc_disable();
            class Node { public $edge; }
            $node = new Node(); $node->edge = $node; $weak = WeakReference::create($node);
            unset($node);
            echo gc_enabled() === false, ':', $weak->get() !== null, ':';
            echo gc_collect_cycles(), ':', $weak->get() === null, ':';
            gc_enable(); echo gc_enabled();
            """),
        new Case("automatic-collection-at-loop-checkpoint", """
            class Node { public $edge; }
            $first = new Node(); $first->edge = $first;
            $weak = WeakReference::create($first); unset($first);
            for ($i = 0; $i < 10500; $i++) {
                $node = new Node(); $node->edge = $node; unset($node);
            }
            echo $weak->get() === null;
            """),
        new Case("property-receiver-survives-suspending-write", """
            class Node { public $value; }
            function receiver() { global $object; return $object; }
            function replacement($weak) {
                global $object;
                $object = null;
                Async\\suspend();
                echo $weak->get() !== null, ':';
                return 42;
            }
            $object = new Node();
            $weak = WeakReference::create($object);
            receiver()->value = replacement($weak);
            echo $weak->get() === null;
            """, true),
        new Case("property-receiver-released-on-expression-error", """
            class Node { public $value = 1; }
            function receiver() { global $object; return $object; }
            function fail() { global $object; $object = null; throw new Exception('operation'); }
            $object = new Node(); $weak = WeakReference::create($object);
            try { receiver()->value += fail(); }
            catch (Throwable $error) { echo $error->getMessage(), ':', $weak->get() === null; }
            """),
        new Case("variable-receiver-rebound-by-right-operand", """
            class Node { public $value = 1; }
            function replace() { global $object; $object = new Node(); return 6; }
            $object = new Node(); $old = $object;
            $object->value = replace();
            echo $old->value, ':', $object->value, ':';
            $old = $object;
            $object->value += replace();
            echo $old->value, ':', $object->value;
            """),
        new Case("clone-new-and-type-rejected", """
            $target = new stdClass(); $weak = WeakReference::create($target);
            try { new WeakReference(); } catch (Error $e) { echo $e->getMessage(), '|'; }
            try { clone $weak; } catch (Error $e) { echo $e->getMessage(), '|'; }
            try { WeakReference::create(1); } catch (TypeError $e) { echo get_class($e); }
            """),
        new Case("weak-closure", """
            $closure = fn() => 42;
            $weak = WeakReference::create($closure);
            $target = $weak->get(); echo $target(), ':';
            unset($target, $closure); echo $weak->get() === null;
            """),
        new Case("weak-reference-through-suspension", """
            $object = new stdClass();
            $weak = WeakReference::create($object);
            $task = Async\\spawn(function() use ($object) { Async\\suspend(); return 42; });
            unset($object);
            echo $weak->get() !== null, ':', Async\\await($task), ':', $weak->get() === null;
            """, true),
        new Case("weak-reference-is-final", """
            class Invalid extends WeakReference {}
            echo 'UNEXPECTED_AFTER';
            """, "final")
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "weak-reference");
    }
}
