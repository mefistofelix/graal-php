package graalphp;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Identical PHP inputs; successful output equality and explicit semantic rejection checks are reported separately. */
public final class ClassContractsTest {
    record Case(String name, String source, Map<String, String> files, boolean async, String rejection) {
        Case(String name, String source) { this(name, source, Map.of(), false, null); }
        Case(String name, String source, boolean async) { this(name, source, Map.of(), async, null); }
        Case(String name, String source, String rejection) { this(name, source, Map.of(), false, rejection); }
        Case(String name, String source, Map<String, String> files, boolean async) { this(name, source, files, async, null); }
    }
    private static final List<Case> CASES = List.of(
        new Case("declaration-kinds-and-timing", """
            echo interface_exists('I', false), trait_exists('T', false), class_exists('C', false) ? 'C' : '-';
            interface I {} trait T {} class C implements I { use T; }
            echo ':', interface_exists('i'), ':', trait_exists('t'), ':', class_exists('C'), ':';
            echo class_exists('I') === false, ':', class_exists('T') === false, ':', interface_exists('C') === false;
            """),
        new Case("interface-methods-and-typing", """
            interface Greeter { public function greet(string $name): string; }
            class Greeting implements Greeter { public function greet(string $name): string { return 'hello '.$name; } }
            function callGreeter(Greeter $value): Greeter { echo $value->greet('world'); return $value; }
            echo ':', get_class(callGreeter(new Greeting)), ':', (new Greeting) instanceof Greeter;
            """),
        new Case("multiple-interface-inheritance", """
            interface A { function a(); } interface B { function b(); }
            interface C extends A, B { function c(); }
            class D implements C { function a(){return 1;} function b(){return 2;} function c(){return 3;} }
            $d = new D; echo $d->a(), $d->b(), $d->c(), ':', $d instanceof A, ':', $d instanceof B, ':', $d instanceof C;
            """),
        new Case("interface-diamond", """
            interface RootContract { const VALUE = 7; function run(): int; }
            interface A extends RootContract {} interface B extends RootContract {}
            interface Combined extends A, B {}
            class C implements Combined { function run(): int {return self::VALUE;} }
            echo (new C)->run(), ':', C::VALUE;
            """),
        new Case("inherited-interface-implementation", """
            interface I { function value(int $n): int; }
            class Base { public function value(int $n): int { return $n+1; } }
            class Child extends Base implements I {}
            echo (new Child)->value(4), ':', (new Child) instanceof I;
            """),
        new Case("abstract-class-contract", """
            interface I { function value(): int; }
            abstract class Base implements I { abstract protected function prefix(): int; public function value(): int { return $this->prefix()+1; } }
            class Child extends Base { protected function prefix(): int { return 8; } }
            echo (new Child)->value(), ':', class_exists('Base');
            try { new Base; } catch (Error $error) { echo ':abstract'; }
            """),
        new Case("abstract-interface-carried-to-child", """
            interface I { function value(): int; }
            abstract class Base implements I {}
            class Child extends Base { function value(): int { return 4; } }
            echo (new Child)->value();
            """),
        new Case("abstract-and-static-methods", """
            interface I { static function value(int $n = 2): int; }
            abstract class Base { abstract public static function value(int $n = 2): int; }
            class C extends Base implements I { public static function value(int $n = 2): int { return $n*3; } }
            echo C::value(), ':', C::value(3);
            """),
        new Case("covariant-return", """
            class Animal {} class Cat extends Animal {}
            interface Factory { function make(): Animal; }
            class CatFactory implements Factory { function make(): Cat { return new Cat; } }
            echo get_class((new CatFactory)->make());
            """),
        new Case("contravariant-parameter", """
            class Animal {} class Cat extends Animal {}
            interface Handler { function handle(Cat $value): int; }
            class H implements Handler { function handle(Animal $value): int { return 42; } }
            echo (new H)->handle(new Animal), ':', (new H)->handle(new Cat);
            """),
        new Case("nullable-and-union-contracts", """
            interface I { function convert(int $value): int|string; }
            class C implements I { function convert(int|string|null $value): string { return 'value:'.$value; } }
            echo (new C)->convert(null), ':', (new C)->convert('x');
            function identity(int|string $value): int|string { return $value; }
            echo ':', identity('007'), ':', identity(7);
            """),
        new Case("intersection-and-dnf-values", """
            interface A {} interface B {} class Both implements A, B {} class Other {}
            function both(A&B $value): A&B { return $value; }
            function either((A&B)|Other $value): (A&B)|Other { return $value; }
            echo get_class(both(new Both)), ':', get_class(either(new Other)), ':';
            try { both(new Other); } catch (TypeError $error) { echo 'type'; }
            """),
        new Case("intersection-variance", """
            interface A {} interface B {} class Both implements A, B {}
            interface I { function f(A&B $value): A; }
            class C implements I { function f(A $value): A&B { return new Both; } }
            echo get_class((new C)->f(new Both));
            """),
        new Case("self-parameter-and-return", """
            interface I { function identity(self $value): self; }
            class C implements I { function identity(I $value): self { return $this; } }
            echo get_class((new C)->identity(new C));
            """),
        new Case("late-static-return", """
            class Base { public static function make(): static { return new static; } }
            class Child extends Base {}
            echo get_class(Child::make()), ':', get_class(Base::make());
            """),
        new Case("reference-and-variadic-contract", """
            interface I { function add(int &$n, int ...$values): int; }
            class C implements I { function add(int &$n, int ...$values): int { foreach($values as $value) $n += $value; return $n; } }
            $n=2; echo (new C)->add($n,3,4), ':', $n;
            """),
        new Case("constructor-signature-exception", """
            class Base { function __construct(int $value) {} }
            class Child extends Base { public $value; function __construct(string $value, int $other) { $this->value = $value.$other; } }
            echo (new Child('x',2))->value;
            """),
        new Case("private-method-lexical-dispatch", """
            class Base { private function value(){ return 'base'; } public function call(){return $this->value();} }
            class Child extends Base { public function value(){return 'child';} }
            echo (new Child)->call(), ':', (new Child)->value();
            """),
        new Case("trait-basic-precedence", """
            class Base { function value(){return 'parent';} }
            trait T { function value(){return 'trait';} function parentValue(){return parent::value();} }
            class C extends Base { use T; }
            class D extends Base { use T; function value(){return 'class';} }
            echo (new C)->value(), ':', (new D)->value(), ':', (new C)->parentValue();
            """),
        new Case("trait-conflict-resolution-and-alias", """
            trait A {function run(){return 'A';}} trait B {function run(){return 'B';}}
            class C {use A,B { A::run insteadof B; B::run as other; }}
            echo (new C)->run(), ':', (new C)->other();
            """),
        new Case("class-method-resolves-trait-collision", """
            trait A {function run(){return 'A';}} trait B {function run(){return 'B';}}
            class C {use A,B; function run(){return 'C';}}
            echo (new C)->run();
            """),
        new Case("trait-visibility-alias", """
            trait T { function value(){return 7;} }
            class C {use T {value as private secret; value as protected;} function run(){return $this->secret()+$this->value();}}
            echo (new C)->run();
            try {(new C)->value();} catch(Error $error){echo ':protected';}
            try {(new C)->secret();} catch(Error $error){echo ':private';}
            """),
        new Case("trait-nesting-and-shared-origin", """
            trait RootTrait { function value(){return 5;} }
            trait A {use RootTrait;} trait B {use RootTrait;}
            trait Combined {use A,B;}
            class C {use Combined;}
            echo (new C)->value();
            """),
        new Case("trait-magic-constants-and-alias", """
            trait T {function value(){return __CLASS__.':'.__TRAIT__.':'.__METHOD__.':'.__FUNCTION__;}}
            class C {use T {value as alias;}} class D {use T;}
            echo (new C)->value(), ';', (new C)->alias(), ';', (new D)->value();
            """),
        new Case("trait-closure-private-scope", """
            trait T {function closure(){return function(){return __CLASS__.':'.$this->value;};}}
            class C {use T; private $value=8;}
            $callback=(new C)->closure(); echo $callback();
            """),
        new Case("trait-self-types", """
            trait T {function same(self $value): self {return $value;} function create(): self {return new self;}}
            class C {use T;} class D {use T;}
            echo get_class((new C)->same(new C)), ':', get_class((new D)->create());
            try {(new C)->same(new D);}catch(TypeError $error){echo ':type';}
            """),
        new Case("trait-abstract-parent-implementation", """
            class Base {protected function value(int $n): int {return $n+3;}}
            trait T {abstract protected function value(int $n): int; function run(){return $this->value(4);}}
            class C extends Base {use T;}
            echo (new C)->run();
            """),
        new Case("trait-private-abstract-requirement", """
            trait T {abstract private function value(): int; function run(){return $this->value();}}
            class C {use T; private function value(): int{return 8;}}
            echo (new C)->run();
            """),
        new Case("trait-properties-compatible-defaults", """
            trait A {public $value=1+1; public $items=[1,2];}
            trait B {public $value=2; public $items=[1,2];}
            class C {use A,B; public $value=2;}
            echo (new C)->value, ':', (new C)->items[1];
            """),
        new Case("trait-static-storage-isolation", """
            trait T {public static $n=1; public static function bump(){static::$n++;}}
            class A {use T;} class B extends A {use T;} class C extends A {}
            A::bump(); B::bump(); B::bump(); echo A::$n, ':', B::$n, ':', C::$n;
            """),
        new Case("trait-static-self-default", """
            trait T {public static $n=self::VALUE;}
            class C {use T; const VALUE=7;} class D {use T; const VALUE=9;}
            echo C::$n, ':', D::$n;
            """),
        new Case("trait-static-parent-default", """
            trait T {public static $n=parent::VALUE;}
            class Base {const VALUE=8;} class C extends Base {use T;}
            echo C::$n;
            """),
        new Case("unused-trait-default-is-not-evaluated", """
            trait T {public static $n=self::MISSING;}
            echo 'declared';
            """),
        new Case("direct-trait-static-deprecations", """
            trait T {public static $n=3; public static function value(){return 4;}}
            echo T::$n, ':', T::value();
            """),
        new Case("direct-trait-storage-is-independent", """
            error_reporting(0);
            trait T {public static $n=1;} class C {use T;}
            T::$n=7; echo T::$n, ':', C::$n;
            C::$n=9; echo ':',T::$n, ':',C::$n;
            """),
        new Case("trait-private-property-rebinding", """
            trait T {private $n=2; function value(){return $this->n;} function change($n){$this->n=$n;}}
            class A {use T; function baseValue(){return $this->n;}}
            class B extends A {use T;}
            $b=new B; $b->change(9); echo $b->value(), ':', $b->baseValue();
            """),
        new Case("trait-final-overridden-by-using-class", """
            trait T {final public function value(){return 1;}}
            class C {use T; public function value(){return 2;}}
            echo (new C)->value();
            """),
        new Case("class-constant-inheritance-and-visibility", """
            class Base {public const VALUE=4; private const SECRET=7; public function secret(){return self::SECRET;}}
            class Child extends Base {public const VALUE=9; public function values(){return parent::VALUE+self::VALUE;}}
            echo Child::VALUE, ':', (new Child)->values(), ':', (new Child)->secret();
            try {echo Base::SECRET;}catch(Error $error){echo ':private';}
            """),
        new Case("typed-constant-and-array-copy", """
            interface I {const int VALUE=7;}
            class C implements I {const array ITEMS=[1,2]; public $value=self::VALUE;}
            $items=C::ITEMS; $items[0]=9;
            echo C::VALUE, ':', (new C)->value, ':', C::ITEMS[0], ':', $items[0];
            """),
        new Case("trait-constant-parent-precedence", """
            class Base {const VALUE=1; public $n=1;}
            trait T {const VALUE=2; public $n=2;}
            class C extends Base {use T;}
            echo C::VALUE, ':', (new C)->n;
            """),
        new Case("trait-constant-composition", """
            trait A {const VALUE=2+3;} trait B {const VALUE=5;}
            class C {use A,B; const VALUE=5;}
            echo C::VALUE;
            """),
        new Case("constant-default-and-self-reference", """
            class C {const FIRST=3; const SECOND=self::FIRST+4; public $n=self::SECOND; function value($n=self::SECOND){return $n;}}
            echo C::SECOND, ':', (new C)->n, ':', (new C)->value();
            """),
        new Case("dynamic-class-constants", """
            class C {const VALUE=6;}
            $name='C'; $object=new C;
            echo $name::VALUE, ':', $object::VALUE, ':', $object::class;
            try {echo $name::class;}catch(TypeError $error){echo ':string-type';}
            """),
        new Case("instanceof-no-autoload", """
            class C {} $calls=0;
            spl_autoload_register(function($name)use(&$calls){$calls++;});
            $object=new C; $name='C';
            echo $object instanceof C, ':', $object instanceof $name, ':', $object instanceof Missing ? 'T':'F', ':', $calls;
            $name='int'; echo ':', 1 instanceof $name ? 'T':'F';
            """),
        new Case("class-relationship-queries", """
            error_reporting(0);
            interface I {} class Base implements I {} class Child extends Base {} trait T {}
            echo is_a(new Child,'I'), ':', is_subclass_of(new Child,'Base'), ':', is_subclass_of(new Child,'Child') === false;
            echo ':', is_a('Child','I') === false, ':', is_a('Child','I',true), ':', is_subclass_of('Child','I');
            echo ':', is_a('T','T',true) === false, ':', is_a(new Child,'mixed') === false;
            """),
        new Case("class-name-scope-and-late-binding", """
            trait T {function names(){return self::class.':'.parent::class.':'.static::class;}}
            class Base {} class C extends Base {use T;} class D extends C {}
            echo (new D)->names();
            """),
        new Case("class-query-deprecation-record", """
            class C {}
            error_reporting(0); error_clear_last();
            echo is_a('C','C') === false, ':';
            $error=error_get_last(); echo $error['type'], ':', $error['message'], ':', $error['line'];
            echo ':', $error['file'] === __FILE__;
            error_clear_last(); echo ':', error_get_last() === null;
            """),
        new Case("class-query-deprecation-output", """
            class C {}
            echo is_a('C','C') === false;
            """),
        new Case("numeric-union-coercion", """
            function numeric(int|float $value) { echo $value === 1.5 ? 'float' : 'other'; }
            numeric('1.5');
            try { numeric('not-numeric'); } catch(TypeError $error) {echo ':type';}
            """),
        new Case("abstract-trait-alias-implemented", """
            trait T {abstract function value():int;}
            class C {use T{value as other;} function value():int{return 1;} function other():int{return 2;}}
            echo (new C)->value(), ':', (new C)->other();
            """),
        new Case("class-metadata", """
            interface A {} interface B extends A {} trait RootTrait {} trait T {use RootTrait; function value(){}}
            class Base implements A {} class C extends Base implements B {use T {value as alias;}}
            foreach(class_parents('C') as $key=>$value) echo $key,'=',$value,';'; echo ':';
            foreach(class_implements(new C) as $key=>$value) echo $key,'=',$value,';'; echo ':';
            foreach(class_uses('C') as $key=>$value) echo $key,'=',$value,';';
            echo ':',method_exists('C','alias'), ':', method_exists('C','value');
            """),
        new Case("interface-autoload-kind-check", """
            spl_autoload_register(function($name){echo $name,':'; if($name==='I')eval('interface I{}'); else eval('class Wrong{}');});
            echo interface_exists('I'), ':', interface_exists('Wrong') === false, ':', class_exists('Wrong',false);
            """),
        new Case("dependency-autoload-order", """
            spl_autoload_register(function($name){echo $name,':'; if($name==='I')eval('interface I{}'); else if($name==='T')eval('trait T{}'); else eval('class Base{}');});
            class C extends Base implements I {use T;}
            echo (new C) instanceof I;
            """),
        new Case("conditional-interface-and-trait", """
            if(false){interface Absent{} trait Unused{}}
            function declareTypes(){interface I{} trait T{function value(){return 5;}}}
            echo interface_exists('I',false) === false, ':', trait_exists('T',false) === false, ':';
            declareTypes(); class C implements I {use T;} echo (new C)->value();
            """),
        new Case("namespace-trait-and-interface-aliases", """
            namespace Lib; interface Contract{function value():int;} trait Body{function value():int{return 7;}}
            namespace App; use Lib\\Contract as I; use Lib\\Body as T;
            class C implements I {use T;}
            echo (new C)->value(), ':', (new C) instanceof I;
            """),
        new Case("async-method-interface-contract", """
            interface I {function value(int $n):int;}
            class C implements I {function value(int $n):int{Async\\delay(1); return $n+4;}}
            echo (new C)->value(3);
            """, true),
        new Case("async-trait-alias-and-closure", """
            trait T {private $n=9; function value():int{Async\\delay(1); return $this->n;} function closure(){return function(){Async\\delay(1); return $this->value();};}}
            class C {use T{value as alias;}}
            $c=new C; $callback=$c->closure(); echo $c->alias(), ':', $callback();
            """, true),
        new Case("async-dependency-files", """
            spl_autoload_register(function($name){Async\\delay(1); require __DIR__.'/'.$name.'.php';});
            echo (new C)->value(), ':', (new C) instanceof I;
            """, Map.of("C.php","class C implements I {use T;}", "I.php","interface I {function value():int;}",
                    "T.php","trait T {function value():int {Async\\delay(1); return 42;}}"), true),
        new Case("async-constant-autoload", """
            spl_autoload_register(function($name){Async\\delay(1); eval('class C {const VALUE=8;}');});
            echo C::VALUE;
            """, true),
        new Case("async-interface-query", """
            spl_autoload_register(function($name){Async\\delay(1); eval('interface I {}');});
            echo interface_exists('I'), ':', class_exists('I') === false;
            """, true),
        new Case("async-cancellation-during-composition", """
            $entered=new Async\\Channel(1); $release=new Async\\Channel(1); $calls=0;
            spl_autoload_register(function($name)use($entered,$release,&$calls){
                if($name==='C') {eval('class C implements I {use T;}'); return;}
                if($name==='T') {
                    if(++$calls===1) {try {$entered->send(true); $release->recv();}finally{echo 'finally:';}}
                    eval('trait T {function value():int{return 7;}}'); return;
                }
                eval('interface I {function value():int;}');
            });
            $task=Async\\spawn(function(){return class_exists('C');});
            $entered->recv(); echo class_exists('C',false) === false, ':';
            $task->cancel(); try {Async\\await($task);}catch(Throwable $error){echo 'cancel:';}
            echo (new C)->value(), ':', $calls;
            """, true),
        new Case("fatal-declaration-bypasses-catch-finally", """
            try {eval('interface I{function f(int $n);} class C implements I{function f(string $n){}}');}
            catch(Throwable $error){echo 'UNEXPECTED_CATCH';}
            finally {echo 'UNEXPECTED_FINALLY';}
            echo 'UNEXPECTED_AFTER';
            """, "compatible"),
        new Case("async-fatal-declaration-stops-request", """
            $task=Async\\spawn(function(){
                try {eval('interface I{function f(int $n);} class C implements I{function f(string $n){}}');}
                catch(Throwable $error){echo 'UNEXPECTED_CATCH';}
                finally {echo 'UNEXPECTED_FINALLY';}
            });
            try {Async\\await($task);}catch(Throwable $error){echo 'UNEXPECTED_AWAIT_CATCH';}
            echo 'UNEXPECTED_AFTER';
            """, Map.of(), true, "compatible"),
        new Case("abstract-trait-alias-missing", "trait T{abstract function f();} class C{use T{f as g;} function f(){}}", "abstract"),
        new Case("variadic-trailing-type-rejected", "interface I{function f(int ...$x);} class C implements I{function f(int $a=0,string ...$x){}}", "compatible"),
        new Case("final-second-interface-constant-rejected", "interface A{const X=1;} interface B{final const X=1;} class C implements A,B{const X=2;}", "constant"),
        new Case("interface-sibling-signatures-rejected", "interface A{function f(int $x);} interface B{function f(string $x);} interface C extends A,B{}", "compatible"),
        new Case("interface-constructor-signature-rejected", "interface I{function __construct(int $n);} class C implements I{function __construct(string $n){}}", "compatible"),
        new Case("late-static-return-widening-rejected", "class Base{function f():static{return $this;}} class C extends Base{function f():self{return $this;}}", "compatible"),
        new Case("missing-interface-method", "interface I{function missing():int;} class C implements I{}", "abstract"),
        new Case("missing-abstract-parent-method", "abstract class Base{abstract function missing():int;} class C extends Base{}", "abstract"),
        new Case("missing-trait-requirement", "trait T{abstract function missing():int;} class C{use T;}", "abstract"),
        new Case("final-class-rejected", "final class Base{} class Child extends Base{}", "extend"),
        new Case("final-method-rejected", "class Base{final function value(){}} class Child extends Base{function value(){}}", "final"),
        new Case("final-trait-alias-rejected", "trait T{function value(){}} class Base{use T{value as final;}} class Child extends Base{function value(){}}", "final"),
        new Case("visibility-narrowing-rejected", "class Base{public function value(){}} class Child extends Base{protected function value(){}}", "method"),
        new Case("static-mismatch-rejected", "interface I{public static function value();} class C implements I{public function value(){}}", "compatible"),
        new Case("parameter-narrowing-rejected", "class Animal{} class Cat extends Animal{} interface I{function value(Animal $x);} class C implements I{function value(Cat $x){}}", "compatible"),
        new Case("return-widening-rejected", "class Animal{} class Cat extends Animal{} interface I{function value():Cat;} class C implements I{function value():Animal{return new Animal;}}", "compatible"),
        new Case("extra-required-parameter-rejected", "interface I{function value($x);} class C implements I{function value($x,$y){}}", "compatible"),
        new Case("reference-mismatch-rejected", "interface I{function value(&$x);} class C implements I{function value($x){}}", "compatible"),
        new Case("variadic-removal-rejected", "interface I{function value(...$x);} class C implements I{function value($x){}}", "compatible"),
        new Case("trait-method-collision-rejected", "trait A{function value(){}} trait B{function value(){}} class C{use A,B;}", "collision"),
        new Case("trait-property-conflict-rejected", "trait A{public $n=1;} trait B{public $n=2;} class C{use A,B;}", "incompatible"),
        new Case("trait-property-type-conflict-rejected", "trait A{public int $n=1;} class C{use A; public string $n='1';}", "incompatible"),
        new Case("trait-constant-conflict-rejected", "trait T{const VALUE=1;} class C{use T;const VALUE=2;}", "incompatible"),
        new Case("final-constant-rejected", "class Base{final const VALUE=1;} class Child extends Base{const VALUE=2;}", "constant"),
        new Case("interface-constant-ambiguity-rejected", "interface A{const VALUE=1;} interface B{const VALUE=2;} class C implements A,B{}", "constant"),
        new Case("extends-interface-rejected", "interface I{} class C extends I{}", "extend"),
        new Case("implements-class-rejected", "class Base{} class C implements Base{}", "interface"),
        new Case("use-class-rejected", "class Base{} class C{use Base;}", "trait"),
        new Case("invalid-trait-precedence-rejected", "trait A{function value(){}} class C{use A{A::value insteadof Missing;}}", "trait"),
        new Case("inherited-property-type-rejected", "class Base{public int $n=1;} class Child extends Base{public string $n='a';}", "property")
    );

    public static void main(String[] arguments) throws Exception { run(arguments, CASES, "class-contracts"); }

    static void run(String[] arguments, List<Case> cases, String suite) throws Exception {
        Path oracle = Path.of(arguments.length > 0 ? arguments[0] : "tools/php-8.6.0RC2/php.exe").toAbsolutePath();
        Path asyncOracle = Path.of(arguments.length > 1 ? arguments[1] : "tools/trueasync-0.10.0/php.exe").toAbsolutePath();
        Path executable = arguments.length > 2 && !arguments[2].isBlank() ? Path.of(arguments[2]).toAbsolutePath() : null;
        boolean interpreter = arguments.length > 3 && arguments[3].equals("--interpreter");
        if (!Files.isRegularFile(oracle) || !Files.isRegularFile(asyncOracle)) throw new IllegalArgumentException("Both PHP 8.6 reference executables are required");
        Path directory = Files.createTempDirectory(Path.of("build"), suite + "-").toAbsolutePath();
        var report = new StringBuilder("Target: " + (executable == null ? "JVM" : executable.getFileName()) + (interpreter ? " interpreter" : "") + "\n");
        for (Path reference : List.of(oracle, asyncOracle)) {
            var process = new ProcessBuilder(reference.toString(), "-n", "-v").redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Reference version timeout"); }
            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            report.append(version.replace("\r\n", "\n"));
            if (process.exitValue() != 0 || !version.contains("PHP 8.6")) throw new AssertionError("Unexpected PHP reference version");
        }
        int failures = 0;
        int rejections = 0;
        for (var test : cases) {
            Path root = directory.resolve(test.name);
            Files.createDirectories(root);
            Path script = root.resolve("main.php");
            Files.writeString(script, "<?php\n" + test.source);
            for (var file : test.files.entrySet()) Files.writeString(root.resolve(file.getKey()), "<?php\n" + file.getValue());
            int expectedExit = process(List.of((test.async ? asyncOracle : oracle).toString(), "-n", script.toString()), root, "oracle");
            int actualExit;
            if (executable != null) {
                actualExit = process(interpreter ? List.of(executable.toString(), "--interpreter", script.toString())
                        : List.of(executable.toString(), script.toString()), root, "actual");
            } else {
                var output = new ByteArrayOutputStream();
                var error = new ByteArrayOutputStream();
                actualExit = 0;
                var builder = Context.newBuilder("php").allowAllAccess(true).out(output).err(error)
                        .environment("GRAALPHP_ROOT", root.toString()).environment("GRAALPHP_WATCH", "0");
                if (interpreter) builder.option("engine.Compilation", "false");
                try (var context = builder.build()) {
                    context.eval(Source.newBuilder("php", script.toFile()).build());
                } catch (Exception failure) {
                    actualExit = 1;
                    error.writeBytes(failure.toString().getBytes(StandardCharsets.UTF_8));
                }
                Files.write(root.resolve("actual.out"), output.toByteArray());
                Files.write(root.resolve("actual.err"), error.toByteArray());
            }
            String expected = text(root, "oracle.out");
            String actual = text(root, "actual.out");
            String expectedError = text(root, "oracle.err");
            String actualError = text(root, "actual.err");
            boolean pass;
            if (test.rejection == null) {
                pass = expectedExit == 0 && actualExit == 0 && expected.equals(actual) && expectedError.isEmpty() && actualError.isEmpty();
            } else {
                rejections++;
                String diagnostic = (actual + actualError).toLowerCase(Locale.ROOT);
                pass = expectedExit > 0 && actualExit > 0 && diagnostic.contains(test.rejection)
                        && !expected.contains("UNEXPECTED_") && !actual.contains("UNEXPECTED_")
                        && !diagnostic.contains("expected '") && !diagnostic.contains("unsupported")
                        && !diagnostic.contains("stack overflow") && !diagnostic.contains("exception in thread");
            }
            String line = (pass ? "PASS " : "FAIL ") + test.name + " oracle=" + expectedExit + " target=" + actualExit
                    + " mode=" + (test.rejection == null ? "output" : "rejection:" + test.rejection) + "\n";
            report.append(line);
            System.out.print(line);
            if (!pass) {
                failures++;
                System.out.println("oracle=" + expected + expectedError + "\nactual=" + actual + actualError);
            }
        }
        report.append("Cases: ").append(cases.size()).append("; output comparisons: ").append(cases.size() - rejections)
                .append("; semantic rejections: ").append(rejections).append("; failures: ").append(failures).append('\n');
        Files.writeString(directory.resolve("results.txt"), report);
        System.out.println("Evidence: " + directory);
        if (failures != 0) throw new AssertionError(failures + " " + suite + " cases failed");
        System.out.println("PASS: " + cases.size() + " " + suite + " programs; " + rejections + " semantic rejections (diagnostic text not asserted identical)");
    }

    private static String text(Path root, String name) throws Exception {
        return Files.readString(root.resolve(name)).replace("\r\n", "\n");
    }

    private static int process(List<String> command, Path root, String label) throws Exception {
        var builder = new ProcessBuilder(command).redirectOutput(root.resolve(label + ".out").toFile())
                .redirectError(root.resolve(label + ".err").toFile());
        builder.environment().put("GRAALPHP_ROOT", root.toString());
        builder.environment().put("GRAALPHP_WATCH", "0");
        var process = builder.start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            return -1;
        }
        return process.exitValue();
    }
}
