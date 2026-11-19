package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

public final class DestructorTest {
    private static final List<Case> CASES = List.of(
        new Case("shutdown-reverse-creation-order", """
            class Example {
                public $name;
                public function __construct($name) { $this->name = $name; }
                public function __destruct() { echo $this->name, ':'; }
            }
            $first = new Example('first'); $second = new Example('second');
            echo 'end:';
            """),
        new Case("weak-reference-and-resurrection", """
            class Example {
                public function __destruct() {
                    global $weak, $saved;
                    echo $weak->get() === $this, ':';
                    $saved = $this;
                }
            }
            $value = new Example(); $weak = WeakReference::create($value);
            unset($value); echo $weak->get() === $saved, ':';
            unset($saved); echo $weak->get() === null;
            """),
        new Case("replaced-slot-is-visible-in-destructor", """
            class Example {
                public function __destruct() { global $value; echo $value, ':'; }
            }
            $value = new Example();
            $value = 42;
            echo 'end';
            """),
        new Case("failed-construction-skips-destructor", """
            class Example {
                public function __construct() { throw new Exception('constructor'); }
                public function __destruct() { echo 'UNEXPECTED'; }
            }
            try { $object = new Example(); } catch (Throwable $e) { echo $e->getMessage(), ':'; }
            echo 'end';
            """),
        new Case("throw-from-unset", """
            class Example {
                public function __destruct() { throw new Exception('destructor'); }
            }
            try {
                $object = new Example(); unset($object); echo 'UNEXPECTED';
            } catch (Throwable $e) { echo $e->getMessage(); }
            """),
        new Case("private-destructor-access-and-shutdown-warning", """
            class Hidden {
                private function __destruct() { echo 'destroyed:'; }
                public static function release() { $value = new Hidden(); unset($value); }
            }
            Hidden::release();
            try { $object = new Hidden(); unset($object); }
            catch (Error $e) { echo $e->getMessage(), ':'; }
            $remaining = new Hidden(); echo 'shutdown:';
            """),
        new Case("invalid-destructor-signature", """
            class Invalid { public function __destruct(): void {} }
            echo 'UNEXPECTED_AFTER';
            """, "declare a return type"),
        new Case("children-finalize-after-owner", """
            class Child { public function __destruct() { echo 'child:'; } }
            class Owner {
                public $child;
                public function __destruct() { echo 'owner:'; }
            }
            $owner = new Owner(); $owner->child = new Child();
            unset($owner); echo 'end';
            """),
        new Case("frame-finalization-before-return-consumer", """
            class Example { public function __destruct() { echo 'last-owner:'; } }
            function value() { $object = new Example(); return 42; }
            echo value(), ':end';
            """),
        new Case("cycle-finalization-clears-weak-after-callback", """
            class Example {
                public $cycle;
                public function __destruct() { global $weak; echo $weak->get() === $this, ':'; }
            }
            $object = new Example(); $object->cycle = $object;
            $weak = WeakReference::create($object);
            unset($object); echo 'collect:', gc_collect_cycles(), ':', $weak->get() === null;
            """),
        new Case("destructor-suspends-on-originating-task", """
            class Example {
                public $origin;
                public function __destruct() {
                    echo Async\\current_coroutine() === $this->origin, ':';
                    Async\\suspend(); echo 'resumed:';
                }
            }
            $child = Async\\spawn(function() {
                $object = new Example(); $object->origin = Async\\current_coroutine();
                unset($object); echo 'after:';
            });
            Async\\await($child); echo 'end';
            """, true)
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "destructor");
    }
}
