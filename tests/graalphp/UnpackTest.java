package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

/** Identical PHP programs cover call unpacking, array spread, references, Traversable order and suspension. */
public final class UnpackTest {
    private static final String NUMERIC_ITERATOR = """
        class Numbers implements Iterator {
            public $i=0;
            public function rewind():void{$this->i=0;}
            public function valid():bool{return $this->i<2;}
            public function current():mixed{return $this->i+1;}
            public function key():mixed{return $this->i;}
            public function next():void{$this->i++;}
        }
        """;
    private static final String NAMED_ITERATOR = """
        class NamedValues implements Iterator {
            public $i=0;
            public function rewind():void{$this->i=0;}
            public function valid():bool{return $this->i<2;}
            public function current():mixed{return $this->i===0?2:1;}
            public function key():mixed{return $this->i===0?'b':'a';}
            public function next():void{$this->i++;}
        }
        """;
    private static Case numeric(String name, String body) { return new Case(name, NUMERIC_ITERATOR + body); }
    private static Case named(String name, String body) { return new Case(name, NAMED_ITERATOR + body); }

    private static final List<Case> CASES = List.of(
        new Case("call-positional-array", """
            function f($a,$b,$c=0){echo $a,':',$b,':',$c;}
            $values=[1,2];f(...$values,c:3);
            """),
        new Case("call-named-array", """
            function f($a,$b){echo $a,':',$b;}
            f(...['b'=>2,'a'=>1]);
            """),
        new Case("call-mixed-integer-before-name", """
            function f($a,$b){echo $a,':',$b;}
            f(...[0=>1,'b'=>2]);
            """),
        new Case("call-positional-after-named-unpack-errors", """
            function f(...$values){echo 'UNEXPECTED_BODY';}
            try{f(...['a'=>1,0=>2]);}catch(Error$e){echo 'order';}
            """),
        new Case("call-duplicate-name-across-spreads", """
            function f($a){echo 'UNEXPECTED_BODY';}
            try{f(...['a'=>1],...['a'=>2]);}catch(Error$e){echo 'duplicate';}
            """),
        new Case("call-duplicate-name-explicit-after-spread", """
            function f($a){echo 'UNEXPECTED_BODY';}
            try{f(...['a'=>1],a:2);}catch(Error$e){echo 'duplicate';}
            """),
        new Case("call-multiple-positional-spreads", """
            function f($a,$b,$c,$d){echo $a,$b,$c,$d;}
            f(...[1,2],...[3],...[4]);
            """),
        new Case("call-named-after-spread", """
            function f($a,$b,$c){echo $a,':',$b,':',$c;}
            f(...[1],c:3,b:2);
            """),
        new Case("call-unpack-after-named-is-syntax-error", "function f($a,$b){}f(a:1,...['b'=>2]);", "argument unpacking"),
        new Case("call-positional-after-unpack-is-syntax-error", "function f($a,$b){}f(...[1],2);", "argument unpacking"),
        new Case("variadic-keeps-named-keys", """
            function f(...$values){foreach($values as$key=>$value)echo $key,'=',$value,';';}
            f(...['a'=>1,'b'=>2]);
            """),
        new Case("unknown-named-from-spread", """
            function f($a){}
            try{f(...['unknown'=>1]);}catch(Error$e){echo 'unknown';}
            """),
        new Case("reference-original-array", """
            function add(&$value){$value+=4;}
            $values=[1];add(...$values);echo $values[0];
            """),
        new Case("reference-temporary-array", """
            function add(&$value){$value+=4;echo $value;}
            add(...[1]);
            """),
        new Case("reference-nested-location-evaluated-once", """
            function setValue(&$value){$value=9;}
            $i=0;$values=[[1],[2]];setValue(...$values[$i++]);echo $i,':',$values[0][0],':',$values[1][0];
            """),
        new Case("reference-property-spread-is-temporary", """
            function setValue(&$value){$value=9;}
            $object=new stdClass;$object->values=[1];setValue(...$object->values);echo $object->values[0];
            """),
        new Case("reference-static-property-spread-is-temporary", """
            function setValue(&$value){$value=9;}
            class C{public static $values=[1];}setValue(...C::$values);echo C::$values[0];
            """),
        new Case("constructor-unpack", """
            class C{public $value;function __construct($a,$b){$this->value=$a.$b;}}
            echo (new C(...['a'=>'x','b'=>2]))->value;
            """),
        new Case("method-unpack", """
            class C{function value($a,$b){return $a+$b;}}
            echo (new C)->value(...[2,3]);
            """),
        new Case("static-method-unpack", """
            class C{static function value($a,$b){return $a+$b;}}
            echo C::value(...['b'=>3,'a'=>2]);
            """),
        new Case("dynamic-function-unpack", """
            function value($a,$b){return $a+$b;}$callback='value';echo $callback(...[2,3]);
            """),
        new Case("dynamic-static-callable-unpack", """
            class C{static function value($a,$b){return $a+$b;}}
            $callback=['C','value'];echo $callback(...['b'=>3,'a'=>2]);
            """),
        new Case("autoloaded-callable-unpack", """
            spl_autoload_register(function($name){eval('class C{static function value($a,$b){return $a+$b;}}');});
            $callback='C::value';echo $callback(...[2,3]);
            """),
        new Case("strict-types-applies-after-unpack", """
            declare(strict_types=1);function value(int $n){return $n;}
            try{value(...['n'=>'2']);}catch(TypeError$e){echo 'strict';}
            """),
        new Case("defaults-and-named-unpack", """
            function value($a=1,$b=2,$c=3){echo $a,':',$b,':',$c;}
            value(...['c'=>9]);
            """),
        new Case("argument-evaluation-order", """
            function mark($n){echo $n;return $n;}function value($a,$b,$c){echo ':',$a,$b,$c;}
            value(mark(1),...[mark(2),mark(3)]);
            """),
        new Case("invalid-call-spread-after-prior-expression", """
            function mark($n){echo $n;return $n;}function value(){}
            try{value(mark(1),...mark(2));}catch(TypeError$e){echo ':type';}
            """),
        numeric("call-traversable-positional", "function f($a,$b){echo $a,':',$b;}f(...new Numbers);"),
        named("call-traversable-named", "function f($a,$b){echo $a,':',$b;}f(...new NamedValues);"),
        new Case("call-traversable-duplicate-name", """
            class I implements Iterator{public $i=0;function rewind():void{$this->i=0;}function valid():bool{return $this->i<2;}
                function current():mixed{return $this->i+1;}function key():mixed{return 'a';}function next():void{$this->i++;}}
            function f($a){echo 'UNEXPECTED_BODY';}
            try{f(...new I);}catch(Error$e){echo 'duplicate';}
            """),
        new Case("call-traversable-positional-after-name", """
            class I implements Iterator{public $i=0;function rewind():void{$this->i=0;}function valid():bool{return $this->i<2;}
                function current():mixed{return $this->i+1;}function key():mixed{return $this->i===0?'a':0;}function next():void{$this->i++;}}
            function f(...$values){echo 'UNEXPECTED_BODY';}
            try{f(...new I);}catch(Error$e){echo 'order';}
            """),
        new Case("call-traversable-reference-warning", """
            class I implements Iterator{public $i=0;public $value=1;function rewind():void{$this->i=0;}function valid():bool{return $this->i<1;}
                function current():mixed{return $this->value;}function key():mixed{return 0;}function next():void{$this->i++;}}
            function add(&$value){$value++;echo 'inside:',$value,':';}
            $iterator=new I;add(...$iterator);echo 'outside:',$iterator->value;
            """),
        new Case("async-call-traversable", """
            class I implements Iterator{public $i=0;function rewind():void{Async\\delay(1);$this->i=0;}function valid():bool{Async\\delay(1);return $this->i<2;}
                function current():mixed{Async\\delay(1);return $this->i+3;}function key():mixed{Async\\delay(1);return $this->i;}function next():void{Async\\delay(1);$this->i++;}}
            function sum($a,$b){return $a+$b;}echo sum(...new I);
            """, true),
        new Case("array-spread-numeric-reindexes", """
            $values=[2=>10,5=>20];$result=[0,...$values,30];foreach($result as$key=>$value)echo $key,'=',$value,';';
            """),
        new Case("array-spread-string-overwrites", """
            $a=['x'=>1,'y'=>2];$b=['x'=>3];$result=['x'=>9,...$a,...$b,'y'=>8];
            foreach($result as$key=>$value)echo $key,'=',$value,';';
            """),
        new Case("array-spread-mixed-keys", """
            $values=['x'=>1,4=>2,'y'=>3];$result=[...$values];
            foreach($result as$key=>$value)echo $key,'=',$value,';';
            """),
        new Case("array-spread-preserves-references", """
            $n=1;$values=[&$n];$result=[...$values];$result[0]=7;echo $n,':',$values[0],':',$result[0];
            """),
        new Case("array-spread-temporary", """
            $result=[0,...[1,2],3];echo $result[0],$result[1],$result[2],$result[3];
            """),
        numeric("array-spread-traversable-numeric", "$result=[...new Numbers];echo $result[0],':',$result[1];"),
        named("array-spread-traversable-named", "$result=['a'=>9,...new NamedValues];foreach($result as$key=>$value)echo $key,'=',$value,';';"),
        new Case("array-spread-traversable-duplicate-overwrites", """
            class I implements Iterator{public $i=0;function rewind():void{$this->i=0;}function valid():bool{return $this->i<2;}
                function current():mixed{return $this->i+1;}function key():mixed{return 'x';}function next():void{$this->i++;}}
            $result=[...new I];echo count($result),':',$result['x'];
            """),
        new Case("async-array-spread-traversable", """
            class I implements Iterator{public $i=0;function rewind():void{Async\\delay(1);$this->i=0;}function valid():bool{return $this->i<2;}
                function current():mixed{Async\\delay(1);return $this->i+1;}function key():mixed{return $this->i;}function next():void{$this->i++;}}
            $result=[...new I];echo $result[0],$result[1];
            """, true),
        new Case("array-spread-invalid-scalar", """
            $value=1;try{$result=[...$value];echo 'UNEXPECTED_BODY';}catch(Throwable$e){echo get_class($e);}
            """),
        new Case("array-spread-evaluation-order", """
            function mark($n){echo $n;return $n===2?[2,3]:$n;}
            $result=[mark(1),...mark(2),mark(4)];echo ':',$result[0],$result[1],$result[2],$result[3];
            """),
        new Case("array-spread-copy-on-write", """
            $values=[['n'=>1]];$result=[...$values];$result[0]['n']=9;echo $values[0]['n'],':',$result[0]['n'];
            """),
        new Case("class-constant-array-spread", """
            class C{const A=['x'=>1,2];const B=['x'=>9,...self::A,'y'=>3];}
            foreach(C::B as$key=>$value)echo $key,'=',$value,';';
            """)
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "unpack");
    }
}
