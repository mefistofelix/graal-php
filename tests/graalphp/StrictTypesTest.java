package graalphp;

import java.util.List;
import java.util.Map;
import graalphp.ClassContractsTest.Case;

/** Caller-side parameter strictness, declaration-side returns, and source-isolated include/eval. */
public final class StrictTypesTest {
    private static final List<Case> CASES = List.of(
        new Case("strict-scalars", """
            declare(strict_types=1);
            function integer(int $n){return $n;}function text(string $s){return $s;}
            function flag(bool $b){return $b;}
            foreach(['1',1.0,true,null]as$n){try{integer($n);}catch(TypeError$e){echo 'int:';}}
            try{text(1);}catch(TypeError$e){echo 'string:';}
            try{flag(1);}catch(TypeError$e){echo 'bool:';}
            echo integer(1),':',text('ok'),':',flag(true);
            """),
        new Case("strict-int-to-float-and-unions", """
            declare(strict_types=1);
            function f(float $n):float{return $n;}function u(float|string $n){return $n;}
            echo f(1)===1.0,':',u(2)===2.0,':',u('2')==='2';
            """),
        new Case("strict-nullable-and-exact-literals", """
            declare(strict_types=1);
            function n(?int $n){return $n;}function yes(true $n){return $n;}
            echo n(null)===null,':',n(2),':',yes(true);
            try{n('2');}catch(TypeError$e){echo ':nullable';}
            try{yes(1);}catch(TypeError$e){echo ':literal';}
            """),
        new Case("weak-default-mode", """
            function f(int $n,string $s,bool $b,float $v){echo $n===2,':',$s==='3',':',$b===true,':',$v===4.0;}
            f('2',3,1,4);
            """),
        new Case("explicit-weak-mode", """
            declare(strict_types=0);function f(int $n){return $n;}echo f('2');
            """),
        new Case("strict-directives-do-not-unset-enabled-mode", """
            declare(strict_types=1);declare(strict_types=0);
            function f(int $n){return $n;}try{f('2');}catch(TypeError$e){echo 'strict';}
            """),
        new Case("empty-statements-before-directive", """
            ;;;declare(strict_types=1);namespace Demo;
            function f(int $n){return $n;}try{f('2');}catch(\\TypeError$e){echo 'strict';}
            """),
        new Case("weak-caller-strict-declaration", """
            require __DIR__.'/strict.php';echo definedStrict('2');
            try{strictCaller();}catch(TypeError$e){echo ':nested';}
            function globalInteger(int $n){return $n;}
            """, Map.of("strict.php", "declare(strict_types=1);function definedStrict(int $n){return $n;}function strictCaller(){return globalInteger('3');}"), false),
        new Case("strict-caller-weak-declaration", """
            declare(strict_types=1);require __DIR__.'/weak.php';
            try{definedWeak('2');}catch(TypeError$e){echo 'strict';}
            echo ':',weakReturn();
            """, Map.of("weak.php", "function definedWeak(int $n){return $n;}function weakReturn():int{return '3';}"), false),
        new Case("strict-return-belongs-to-declaration", """
            require __DIR__.'/strict.php';
            try{strictReturn();}catch(TypeError$e){echo 'return:';}
            echo widenedReturn()===3.0;
            """, Map.of("strict.php", "declare(strict_types=1);function strictReturn():int{return '3';}function widenedReturn():float{return 3;}"), false),
        new Case("closure-and-arrow-strict-return", """
            declare(strict_types=1);$closure=function():int{return '2';};$arrow=fn():int=>'2';
            foreach([$closure,$arrow]as$f){try{$f();}catch(TypeError$e){echo 'return:';}}
            """),
        new Case("closure-keeps-source-mode-across-include", """
            $f=require __DIR__.'/strict.php';try{$f();}catch(TypeError$e){echo 'strict';}
            """, Map.of("strict.php", "declare(strict_types=1);return function():int{return '4';};"), false),
        new Case("trait-method-retains-source-mode", """
            require __DIR__.'/strict-trait.php';class C{use T{value as alias;}}
            foreach(['value','alias']as$name){$f=['C',$name];try{$f();}catch(TypeError$e){echo 'strict:';}}
            """, Map.of("strict-trait.php", "declare(strict_types=1);trait T{public static function value():int{return '3';}}"), false),
        new Case("trait-closure-retains-source-mode", """
            require __DIR__.'/strict-trait.php';class C{use T;}$f=(new C)->make();
            try{$f();}catch(TypeError$e){echo 'strict';}
            """, Map.of("strict-trait.php", "declare(strict_types=1);trait T{public function make(){return function():int{return '3';};}}"), false),
        new Case("eval-is-a-separate-weak-source", """
            declare(strict_types=1);function f(int $n){return $n;}
            echo eval('return f("3");');
            try{eval('declare(strict_types=1);return f("4");');}catch(TypeError$e){echo ':strict';}
            """),
        new Case("strict-method-constructor-and-named-arguments", """
            declare(strict_types=1);class C{public function __construct(int $n){}public static function value(int $n){return $n;}}
            try{new C(n:'2');}catch(TypeError$e){echo 'constructor:';}
            try{C::value(n:'2');}catch(TypeError$e){echo 'method:';}
            $f=['C','value'];try{$f(n:'2');}catch(TypeError$e){echo 'callable';}
            """),
        new Case("strict-variadic", """
            declare(strict_types=1);function f(int ...$ns){return count($ns);}
            echo f(1,2);try{f(1,'2');}catch(TypeError$e){echo ':type';}
            """),
        new Case("weak-reference-coercion-on-entry-only", """
            function f(int &$n){echo $n===2,':';$n='changed';}
            $n='2';f($n);echo $n;
            """),
        new Case("strict-reference-does-not-coerce", """
            declare(strict_types=1);function f(int &$n){$n=3;}
            $n='2';try{f($n);}catch(TypeError$e){echo 'type:';}echo $n==='2';
            """),
        new Case("typed-variadic-references", """
            function f(int &...$ns){echo $ns[0]===2,':';$ns[1]=4;}
            $a='2';$b='3';f($a,$b);echo $a===2,':',$b;
            """),
        new Case("strict-typed-property-writes", """
            declare(strict_types=1);class C{public int $n=1;public static int $s=2;public float $f=0.0;}
            $c=new C;try{$c->n='3';}catch(TypeError$e){echo 'instance:';}
            try{C::$s='3';}catch(TypeError$e){echo 'static:';}
            $c->f=4;echo $c->n,':',C::$s,':',$c->f===4.0;
            """),
        new Case("property-mode-belongs-to-write-site", """
            require __DIR__.'/strict-class.php';$c=new C;$c->n='3';echo $c->n===3;
            try{$c->set();}catch(TypeError$e){echo ':strict';}
            """,Map.of("strict-class.php","declare(strict_types=1);class C{public int $n=1;public function set(){$this->n='4';}}"),false),
        new Case("weak-argument-precision-deprecation", """
            function f(int $n){return $n;}echo f(1.5),':',f('2.5');
            """),
        new Case("weak-return-precision-deprecation", """
            function f():int{return 1.5;}echo f();
            """),
        new Case("strict-backed-enum-lookups", """
            declare(strict_types=1);enum I:int{case A=1;}enum S:string{case A='1';}
            foreach(['1',1.0,true,null]as$v){try{I::from($v);}catch(TypeError$e){echo 'int:';}}
            foreach([1,1.0,true,null]as$v){try{S::tryFrom($v);}catch(TypeError$e){echo 'string:';}}
            echo I::from(1)->name,':',S::from('1')->name;
            """),
        new Case("strict-generated-enum-callables", """
            declare(strict_types=1);enum E:int{case A=1;}$f='E::from';
            try{$f('1');}catch(TypeError$e){echo 'string:';}
            $f=['E','tryFrom'];try{$f(1.0);}catch(TypeError$e){echo 'float:';}
            echo $f(1)->name;
            """),
        new Case("strict-class-query-builtin-types", """
            declare(strict_types=1);
            try{class_exists(1);}catch(TypeError$e){echo 'name:';}
            try{class_exists('Missing',1);}catch(TypeError$e){echo 'flag:';}
            try{error_reporting('0');}catch(TypeError$e){echo 'reporting';}
            """),
        new Case("strict-string-offset-still-casts-index", """
            declare(strict_types=1);$s='abc';echo $s[1.8];
            """),
        new Case("async-strict-return-and-finally", """
            declare(strict_types=1);function f():int{try{Async\\delay(1);return '2';}finally{echo 'finally:';}}
            try{f();}catch(TypeError$e){echo 'type';}
            """,true),
        new Case("async-internal-callback-is-weak", """
            declare(strict_types=1);$task=Async\\spawn(function(int $n){return $n;},'2');
            echo Async\\await($task);
            """,true),
        new Case("autoloaded-callable-preserves-callsite-mode", """
            declare(strict_types=1);spl_autoload_register(function($name){Async\\delay(1);eval('class C{public static function f(int $n){return $n;}}');});
            $f='C::f';try{$f('2');}catch(TypeError$e){echo 'strict';}
            """,true),
        new Case("late-directive-rejected", "echo 'UNEXPECTED_BODY';declare(strict_types=1);", "first statement"),
        new Case("namespace-before-directive-rejected", "namespace N;declare(strict_types=1);", "first statement"),
        new Case("function-local-directive-rejected", "function f(){declare(strict_types=1);}", "first statement"),
        new Case("nonbinary-directive-rejected", "declare(strict_types=2);", "0 or 1"),
        new Case("float-directive-rejected", "declare(strict_types=1.0);", "0 or 1"),
        new Case("string-directive-rejected", "declare(strict_types='1');", "0 or 1"),
        new Case("block-directive-rejected", "declare(strict_types=1){echo 'UNEXPECTED_BODY';}", "block mode")
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "strict-types");
    }
}
