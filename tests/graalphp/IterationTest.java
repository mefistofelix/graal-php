package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

/** Identical programs cover protocol order, live properties, tentative types, ownership and suspension. */
public final class IterationTest {
    private static final String ITERATOR = """
        class Items implements Iterator {
            public $position = 0;
            public function rewind(): void { echo 'R'; $this->position = 0; }
            public function valid(): bool { echo 'V'; return $this->position < 2; }
            public function current(): mixed { echo 'C'; return $this->position + 5; }
            public function key(): mixed { echo 'K'; return $this->position; }
            public function next(): void { echo 'N'; $this->position++; }
        }
        """;
    private static Case iterator(String name, String body) { return new Case(name, ITERATOR + body); }
    private static final List<Case> CASES = List.of(
        iterator("foreach-method-order", "foreach(new Items as$key=>$value)echo '['.$key.':'.$value.']';"),
        iterator("unused-key-not-called", "foreach(new Items as$value)echo '['.$value.']';"),
        iterator("repeat-loop-rewinds", "$i=new Items;foreach($i as$v){echo $v;break;}echo '|';foreach($i as$v)echo $v;"),
        iterator("break-does-not-advance", "foreach(new Items as$v){echo $v;break;}echo ':done';"),
        iterator("continue-advances", "foreach(new Items as$v){if($v===5)continue;echo $v;}"),
        iterator("same-iterator-nested-loop", "$i=new Items;foreach($i as$v){echo 'outer';foreach($i as$inner){echo $inner;}}"),
        iterator("iterator-count-skips-current-and-key", "echo ':',iterator_count(new Items);"),
        iterator("iterator-array-uses-current-then-key", "$a=iterator_to_array(new Items);echo ':',$a[0],':',$a[1];"),
        iterator("iterator-array-reindex-skips-key", "$a=iterator_to_array(new Items,false);echo ':',$a[0],':',$a[1];"),
        iterator("iterator-apply-method-order", "echo ':',iterator_apply(new Items,function(){echo 'F';return true;});"),
        iterator("iterator-apply-counts-false-call", "echo ':',iterator_apply(new Items,function(){echo 'F';return false;});"),
        iterator("iterator-apply-callback-arguments", "function visit($a,$b){echo $a,':',$b;return true;}echo ':',iterator_apply(new Items,'visit',['x',3]);"),
        iterator("iterator-apply-named-arguments", "function visit($a,$b){echo $a,':',$b;return true;}echo ':',iterator_apply(iterator:new Items,callback:'visit',args:['b'=>3,'a'=>'x']);"),
        iterator("iterator-apply-explicit-reference-argument", "function visit(&$n){$n++;return true;}$n=1;echo iterator_apply(new Items,'visit',[&$n]),':',$n;"),
        iterator("iterator-apply-private-callback-scope", "class Visitor{private static function visit(){echo 'F';return true;}public function run(){return iterator_apply(new Items,'self::visit');}}echo ':',(new Visitor)->run();"),
        iterator("iterator-apply-autoloads-callback-class", "spl_autoload_register(function($name){echo 'A';eval('class Visitor{public static function visit(){echo \\\'F\\\';return true;}}');});echo ':',iterator_apply(new Items,'Visitor::visit');"),
        iterator("iterator-apply-invalid-callback-before-rewind", "try{iterator_apply(new Items,'missing_callback');}catch(TypeError$e){echo 'type';}"),
        iterator("aggregate-chain", "class A implements IteratorAggregate{public function getIterator():Traversable{echo 'A';return new B;}}class B implements IteratorAggregate{public function getIterator():Iterator{echo 'B';return new Items;}}foreach(new A as$v)echo $v;"),
        iterator("aggregate-fresh-iterator-per-loop", "class A implements IteratorAggregate{public function getIterator():Traversable{echo 'A';return new Items;}}$a=new A;foreach($a as$v){echo $v;break;}foreach($a as$v)echo $v;"),
        iterator("iterable-parameter-and-return", "function identity(iterable $v):iterable{return $v;}$i=identity(new Items);echo is_iterable($i),':',$i instanceof Traversable;foreach($i as$v)echo $v;"),
        iterator("iterator-reference-forbidden-before-rewind", "$i=new Items;try{foreach($i as&$v){}}catch(Error$e){echo $e->getMessage();}"),
        iterator("temporary-iterator-reference-forbidden", "try{foreach(new Items as&$v){}}catch(Error$e){echo $e->getMessage();}"),
        iterator("aggregate-reference-resolves-before-error", "class A implements IteratorAggregate{public function getIterator():Traversable{echo 'A';return new Items;}}try{foreach(new A as&$v){}}catch(Error$e){echo $e->getMessage();}"),
        new Case("key-called-before-binding-value", """
            class I implements Iterator {
                public $i=0; public function rewind():void{$this->i=0;} public function valid():bool{return $this->i<2;}
                public function current():mixed{return $this->i+1;}
                public function key():mixed{global $v;echo '['.$v.']';return $this->i;}
                public function next():void{$this->i++;}
            }
            $v='old';foreach(new I as$k=>$v)echo $v,';';
            """),
        new Case("iterator-key-can-be-an-array-or-object", """
            class I implements Iterator {
                public $i=0;public function rewind():void{$this->i=0;}public function valid():bool{return $this->i<2;}
                public function current():mixed{return 5;}public function key():mixed{return $this->i===0?[]:new stdClass;}
                public function next():void{$this->i++;}
            }
            foreach(new I as$k=>$v)echo is_array($k)?'array:':'object:',$v,';';
            """),
        new Case("array-conversion-rejects-non-array-key", """
            class I implements Iterator {
                public function rewind():void{}public function valid():bool{return true;}
                public function current():mixed{return 5;}public function key():mixed{return [];}
                public function next():void{}
            }
            try{iterator_to_array(new I);}catch(TypeError$e){echo 'key';}
            """),
        new Case("countable-and-is-countable", """
            class C implements Countable{public function count():int{echo 'C';return 7;}}
            $c=new C;echo is_countable($c),':',is_iterable($c)===false,':',count($c),':',sizeof($c,COUNT_RECURSIVE);
            echo ':',is_countable([]),':',is_countable(new stdClass)===false;
            """),
        new Case("countable-tentative-missing-return", """
            echo 'before:';
            class C implements Countable{public function count(){return '7';}}
            echo 'after:',count(new C);
            """),
        new Case("countable-tentative-wrong-return", """
            class C implements Countable{public function count():string{return '7';}}
            echo count(new C);
            """),
        new Case("return-type-will-change-suppresses-only-return", """
            class C implements Countable{#[ReturnTypeWillChange]public function count(){return '7';}}
            echo count(new C);
            """),
        new Case("tentative-notice-reported-once", """
            error_reporting(0);class C implements Countable{public function count(){return 2;}}
            $last=error_get_last();echo $last['type'],':',$last['file']===__FILE__,':',$last['line'];
            error_clear_last();class D extends C{}echo ':',error_get_last()===null;
            """),
        new Case("return-type-attribute-namespaces-and-aliases", """
            namespace N;use ReturnTypeWillChange as Change;
            class A implements \\Countable{#[Change]public function count(){return 1;}}
            class B implements \\Countable{#[\\ReturnTypeWillChange]public function count(){return 2;}}
            echo count(new A),':',count(new B);
            """),
        new Case("same-short-attribute-name-is-not-builtin", """
            namespace N;class ReturnTypeWillChange{}
            class C implements \\Countable{#[ReturnTypeWillChange]public function count(){return 2;}}
            echo count(new C);
            """),
        new Case("trait-attribute-survives-composition", """
            trait T{#[ReturnTypeWillChange]public function number(){return 3;}}
            class C implements Countable{use T{number as count;}}
            echo count(new C);
            """),
        new Case("unknown-attributes-remain-lazy", """
            spl_autoload_register(function($name){echo 'UNEXPECTED_AUTOLOAD';});
            #[Unknown(Missing::VALUE, name:'x')]
            class C {
                #[Other] public $n=2;
                #[Other] public const N=3;
                #[Other] public function value(#[Other]$n){return $n;}
            }
            #[Other]function f(#[Other]$n){return $n;}
            enum E{#[Other]case A;}
            $fn=#[Other]fn(#[Other]$n)=>$n;
            echo (new C)->value(C::N),':',f(4),':',$fn(5),':',E::A->name;
            """),
        new Case("countable-return-conversion-is-internal-weak", """
            declare(strict_types=1);
            class C implements Countable{public $n;public function __construct($n){$this->n=$n;}#[ReturnTypeWillChange]public function count(){return $this->n;}}
            foreach([null,false,true,[],[1],'7x',-3,1.8]as$v)echo count(new C($v)),':';
            """),
        new Case("countable-object-result-warning-location", """
            class C implements Countable{#[ReturnTypeWillChange]public function count(){return new stdClass;}}
            echo count(new C);
            """),
        new Case("count-recursive-and-repeated-noncyclic-array", """
            $v=[1,[2]];$a=[$v,$v];echo count($a),':',count($a,COUNT_RECURSIVE),':',sizeof($a,mode:1);
            """),
        new Case("count-recursion-warning-and-result", """
            $a=[1];$a[]=&$a;echo count($a,COUNT_RECURSIVE);
            """),
        new Case("count-errors", """
            foreach([null,1,'x',new stdClass]as$v){try{count($v);}catch(TypeError$e){echo 'type:';}}
            try{count([],2);}catch(ValueError$e){echo 'mode:';}
            try{count();}catch(ArgumentCountError$e){echo 'arity';}
            """),
        new Case("nullable-scalar-builtin-deprecations", """
            echo count([1],null),':';$a=iterator_to_array(['x'=>2],null);echo $a[0];
            """),
        new Case("strict-iteration-builtin-scalars", """
            declare(strict_types=1);try{count([],true);}catch(TypeError$e){echo 'mode:';}
            try{iterator_to_array([],1);}catch(TypeError$e){echo 'keys:';}
            try{iterator_to_array([],null);}catch(TypeError$e){echo 'null';}
            """),
        new Case("array-inputs-and-result-copy-on-write", """
            $a=['x'=>[1],2=>[2]];$b=iterator_to_array($a);$b['x'][0]=9;
            $c=iterator_to_array($a,preserve_keys:false);echo iterator_count($a),':',$a['x'][0],':',$b['x'][0],':',$c[1][0];
            try{iterator_apply($a,fn()=>true);}catch(TypeError$e){echo ':apply';}
            """),
        new Case("array-conversion-preserves-explicit-references", """
            $n=1;$a=['x'=>&$n];$b=iterator_to_array($a);$b['x']=2;echo $n,':';
            $c=iterator_to_array($a,false);$c[0]=3;echo $n,':',$a['x'];
            """),
        new Case("object-public-protected-private-visibility", """
            class B{private $p=1;protected $q=2;public $r=3;public function walk(){foreach($this as$k=>$v)echo $k,':',$v,';';}}
            class C extends B{private $s=4;public function walkC(){foreach($this as$k=>$v)echo $k,':',$v,';';}}
            $c=new C;foreach($c as$k=>$v)echo $k,':',$v,';';echo '|';$c->walk();echo '|';$c->walkC();
            """),
        new Case("object-live-mutation", """
            $o=new stdClass;$o->a=1;$o->b=2;$o->c=3;
            foreach($o as$k=>$v){echo $k,':',$v,';';if($k==='a'){unset($o->b);$o->d=4;$o->c=9;}}
            """),
        new Case("object-dynamic-reinsert-appends", """
            $o=new stdClass;$o->a=1;$o->b=2;
            foreach($o as$k=>$v){echo $k,':',$v,';';if($v===1){unset($o->a);$o->a=3;}}
            """),
        new Case("declared-reinsert-keeps-position", """
            class C{public $a=1;public $b=2;}$o=new C;
            foreach($o as$k=>$v){echo $k,':',$v,';';if($v===1){unset($o->a);$o->a=3;}}
            """),
        new Case("uninitialized-property-skipped-before-it-is-set", """
            class C{public int $n;public $a=null;public $b=2;}$o=new C;
            foreach($o as$k=>$v){echo $k,':';if($k==='a')$o->n=9;}
            """),
        new Case("undefined-probe-does-not-reserve-property-order", """
            $o=new stdClass;$o->a=1;isset($o->future);$o->b=2;
            foreach($o as$k=>$v){echo $k,':',$v,';';if($k==='b')$o->future=3;}
            """),
        new Case("object-by-reference-and-lingering-alias", """
            $o=new stdClass;$o->a=1;$o->b=2;
            foreach($o as$k=>&$v){$v++;echo $k,':',$v,';';}$v=7;echo $o->b;
            """),
        new Case("temporary-array-reference-loop", """
            foreach([1,2]as&$v){$v++;echo $v,':';}$v=8;echo $v;
            """),
        new Case("temporary-object-reference-loop", """
            function object(){ $o=new stdClass;$o->a=1;return $o; }
            foreach(object()as&$v){$v++;echo $v,':';}$v=8;echo $v;
            """),
        new Case("get-object-vars-snapshot-and-mangled-keys", """
            class C{private $p=1;protected $q=2;public $r=3;public int $missing;
                public function values(){return get_object_vars($this);}}
            $o=new C;$public=get_object_vars($o);$all=$o->values();$public['r']=9;
            echo count($public),':',$o->r,':',count($all),':',$all['p'],':';
            foreach(get_mangled_object_vars($o)as$k=>$v)echo bin2hex($k),'=',$v,';';
            """),
        new Case("get-object-vars-preserves-explicit-references", """
            $n=1;$o=new stdClass;$o->a=&$n;$a=get_object_vars($o);$a['a']=2;echo $n,':';
            $b=get_mangled_object_vars($o);$b['a']=3;echo $n,':',$o->a;
            """),
        new Case("closure-internals-are-not-object-properties", """
            $a=[1];$f=function()use($a){return $a;};foreach($f as$k=>$v)echo 'UNEXPECTED_FIELD';
            echo count(get_object_vars($f)),':',count(get_mangled_object_vars($f));
            """),
        new Case("enum-properties-are-visible-but-not-referenceable", """
            enum E:int{case A=2;}foreach(E::A as$k=>$v)echo $k,':',$v,';';
            try{foreach(E::A as&$v){}}catch(Error$e){echo 'readonly';}
            """),
        new Case("foreach-invalid-input-warning-keeps-binding", """
            $v='old';foreach(null as$v)echo 'UNEXPECTED_BODY';echo $v;
            foreach(3 as$v)echo 'UNEXPECTED_BODY';foreach('x'as&$v)echo 'UNEXPECTED_BODY';
            """),
        new Case("aggregate-invalid-result-is-exception", """
            class A implements IteratorAggregate{#[ReturnTypeWillChange]public function getIterator(){return [];}}
            try{foreach(new A as$v){}}catch(Exception$e){echo $e->getMessage();}
            """),
        new Case("legacy-iterator-tentative-warning-order", """
            class I implements Iterator {
                public function rewind(){} public function valid(){return false;}
                public function current(){return 1;} public function key(){return 0;} public function next(){}
            }
            foreach(new I as$v)echo 'UNEXPECTED_BODY';echo 'done';
            """),
        new Case("truthy-valid-with-tentative-suppression", """
            class I implements Iterator{public $i=0;
                #[ReturnTypeWillChange]public function rewind(){$this->i=0;}
                #[ReturnTypeWillChange]public function valid(){return $this->i===0?[1]:[];}
                #[ReturnTypeWillChange]public function current(){return 7;}
                #[ReturnTypeWillChange]public function key(){return null;}
                #[ReturnTypeWillChange]public function next(){$this->i++;}
            }foreach(new I as$k=>$v)echo $k===null,':',$v;
            """),
        new Case("coerced-property-assignment-expression", """
            class C{public int $n=1;public static int $s=1;}$c=new C;
            echo ($c->n='2')===2,':',(C::$s='3')===3;
            """),
        new Case("async-every-iterator-method", """
            class I implements Iterator{public $i=0;
                public function rewind():void{Async\\delay(1);echo 'R';$this->i=0;}
                public function valid():bool{Async\\delay(1);echo 'V';return $this->i<2;}
                public function current():mixed{Async\\delay(1);echo 'C';return [$this->i];}
                public function key():mixed{Async\\delay(1);echo 'K';return $this->i;}
                public function next():void{Async\\delay(1);echo 'N';$this->i++;}
            }
            foreach(new I as$k=>$v){Async\\delay(1);echo $k,':',$v[0],';';}
            """,true),
        new Case("async-aggregate-and-countable", """
            class I implements Iterator,Countable{public $i=0;
                public function rewind():void{$this->i=0;}public function valid():bool{return $this->i<1;}
                public function current():mixed{Async\\delay(1);return 9;}public function key():mixed{return 0;}
                public function next():void{$this->i++;}public function count():int{Async\\delay(1);return 3;}}
            class A implements IteratorAggregate{public function getIterator():Traversable{Async\\delay(1);return new I;}}
            $a=iterator_to_array(new A);echo $a[0],':',count(new I);
            """,true),
        new Case("async-callback-apply-and-shared-reference", ITERATOR + """
            $n=1;echo iterator_apply(new Items,function(&$n){Async\\delay(1);$n++;return true;},[&$n]),':',$n;
            """,true),
        new Case("async-cancellation-inside-current", """
            $entered=new Async\\Channel(1);
            class I implements Iterator{public $entered;public function __construct($entered){$this->entered=$entered;}
                public function rewind():void{}public function valid():bool{return true;}
                public function current():mixed{try{$this->entered->send(true);Async\\delay(10000);}finally{echo 'inner:';}return 1;}
                public function key():mixed{return 0;}public function next():void{echo 'UNEXPECTED_NEXT';}}
            $task=Async\\spawn(function()use($entered){try{foreach(new I($entered)as$v){echo 'UNEXPECTED_BODY';}}finally{echo 'outer:';}});
            $entered->recv();$task->cancel();try{Async\\await($task);}catch(Throwable$e){echo 'cancelled';}
            """,true),
        new Case("iterator-exception-keeps-old-loop-binding", """
            class I implements Iterator{public function rewind():void{}public function valid():bool{return true;}
                public function current():mixed{return 5;}public function key():mixed{throw new Exception('key');}public function next():void{}}
            $v='old';$k='key';try{foreach(new I as$k=>$v){}}catch(Exception$e){echo $v,':',$k;}
            """),
        new Case("direct-traversable-rejected", "class C implements Traversable{}", "traversable"),
        new Case("abstract-traversable-is-allowed", "abstract class A implements Traversable{}echo class_exists('A');"),
        new Case("concrete-traversable-child-rejected", "abstract class A implements Traversable{}class C extends A{}", "traversable"),
        new Case("both-iterator-protocols-rejected", "class C implements Iterator,IteratorAggregate{}", "both iterator and iteratoraggregate"),
        new Case("required-parameter-still-incompatible", "class C implements Countable{#[ReturnTypeWillChange]public function count($required){return 1;}}", "compatible"),
        new Case("nonpublic-method-still-incompatible", "class C implements Countable{#[ReturnTypeWillChange]protected function count(){return 1;}}", "compatible"),
        new Case("static-method-still-incompatible", "class C implements Countable{#[ReturnTypeWillChange]public static function count(){return 1;}}", "compatible")
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "iteration");
    }
}
