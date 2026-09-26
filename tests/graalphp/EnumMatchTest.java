package graalphp;

import java.util.List;
import java.util.Map;
import graalphp.ClassContractsTest.Case;

/** Enum identity, native method contracts, readonly fields, strict match and resumable clone. */
public final class EnumMatchTest {
    private static final List<Case> CASES = List.of(
        new Case("enum-declaration-timing-and-kind", """
            echo enum_exists('E',false) === false, ':';
            enum E {case A;case B;}
            echo enum_exists('e'), ':', class_exists('E'), ':', interface_exists('E') === false, ':', trait_exists('E') === false;
            echo ':', interface_exists('UnitEnum'), ':', interface_exists('BackedEnum'), ':', class_exists('UnitEnum') === false;
            """),
        new Case("unit-cases-and-singleton", """
            enum Direction {case North;case South;}
            echo Direction::North->name, ':', Direction::North === Direction::North, ':', Direction::North !== Direction::South;
            foreach(Direction::cases() as $key=>$value) echo ';',$key,':',$value->name;
            echo ':', Direction::North === Direction::cases()[0];
            """),
        new Case("case-names-are-case-sensitive", """
            enum E {case A;case a;}
            echo e::A->name, ':', E::a->name, ':', E::A !== E::a;
            try {echo E::Missing;}catch(Error $error){echo ':missing';}
            """),
        new Case("cases-array-is-independent", """
            enum E {case A;case B;}
            $cases=E::cases();$copy=$cases;$cases[0]=E::B;$cases[]=E::A;
            echo count($cases),':',count(E::cases()),':',$copy[0]===E::A,':',E::cases()[0]===E::A;
            """),
        new Case("backed-int-from-and-try", """
            enum Code:int {case Ok=200;case Missing=404;}
            echo Code::Ok->value,':',Code::from(200)===Code::Ok,':',Code::tryFrom(404)===Code::Missing;
            echo ':',Code::tryFrom(500)===null;
            try{Code::from(500);}catch(ValueError $error){echo ':value';}
            """),
        new Case("backed-string-and-named-argument", """
            enum State:string {case Ready='ready';case Done='done';}
            echo State::from(value:'done')->name,':',State::tryFrom(value:'ready')===State::Ready;
            echo ':',State::tryFrom('absent')===null;
            """),
        new Case("numeric-string-backing-values-stay-distinct", """
            enum E:string {case One='1';case Padded='01';case Zero='0';case Empty='';}
            foreach(['1','01','0',''] as $value)echo E::from($value)->name,':';
            echo E::from(1)===E::One;
            """),
        new Case("case-constant-alias-and-defaults", """
            enum E {case A;case B;const Alias=self::A;function same(self $value=self::A):self{return $value;}}
            function selected(E $value=E::A):E{return $value;}
            class C {public E $value=E::A;}
            echo E::Alias===E::A,':',count(E::cases()),':',selected()===E::A,':',(new C)->value===E::A,':',E::B->same()===E::A;
            """),
        new Case("case-initializers-use-class-constants", """
            class Base {const VALUE=2;}
            enum E:int {case A=Base::VALUE;case B=self::NEXT;const NEXT=3;}
            echo E::A->value,':',E::B->value,':',E::from(3)===E::B;
            """),
        new Case("enum-properties-in-constant-expressions", """
            enum E:int{case A=2;}
            class C{public const N=E::A->name;public const V=E::A->value;}
            enum F:int{case B=C::V+1;}
            echo C::N,':',C::V,':',F::B->value;
            """),
        new Case("array-offsets-in-enum-initializers", """
            class C{public const VALUES=['a'=>4];}
            enum E:int{case A=C::VALUES['a'];}
            echo E::A->value,':',E::from(4)->name;
            """),
        new Case("constant-expression-short-circuiting", """
            class C{public const A=false&&Missing::VALUE;public const B=true||Missing::VALUE;public const EMPTY=![];}
            enum E:int{case A=(null??3)+(true?4:Missing::VALUE);}
            echo C::A===false,':',C::B,':',C::EMPTY,':',E::A->value;
            """),
        new Case("enum-methods-and-interface", """
            interface Label {function label():string;}
            enum Status:string implements Label {
                case Ready='r';case Done='d';
                public function label():string{return match($this){self::Ready=>'ready',self::Done=>'done'};}
            }
            function label(Label $value):string{return $value->label();}
            echo label(Status::Done),':',Status::Ready instanceof Label,':',Status::Ready instanceof UnitEnum,':',Status::Ready instanceof BackedEnum;
            """),
        new Case("custom-unit-enum-interface", """
            interface Kind extends UnitEnum {function label():string;}
            enum E implements Kind {case A;function label():string{return $this->name;}}
            echo E::A->label(),':',E::A instanceof Kind;
            foreach(class_implements('E') as $name)echo ':',$name;
            """),
        new Case("custom-backed-enum-interface", """
            interface Kind extends BackedEnum {function label():string;}
            enum E:int implements Kind {case A=7;function label():string{return $this->name;}}
            echo E::from(7)->label(),':',E::A instanceof Kind;
            """),
        new Case("enum-interface-order", """
            interface I {}
            enum E:string implements I {case A='a';}
            foreach(class_implements('E') as $name)echo $name,':';
            echo count(class_parents(E::A)),':',is_a('E','UnitEnum',true),':',is_subclass_of(E::A,'BackedEnum');
            """),
        new Case("trait-enum-and-magic-scope", """
            trait T {function label(){return __CLASS__.':'.__TRAIT__.':'.$this->name;}}
            enum E {use T{label as alias;}case A;}
            echo E::A->alias();
            """),
        new Case("enum-builtins-win-over-trait", """
            trait T {function cases(){return 9;} function from($value){return 8;}}
            enum E:int {use T;case A=1;}
            echo count(E::cases()),':',E::from(1)===E::A;
            """),
        new Case("unit-enum-may-define-from", """
            enum E {case A;public static function from(string $value):self{return self::A;}}
            echo E::from('a')===E::A;
            """),
        new Case("enum-invokable", """
            enum E {case A;function __invoke(int $n):string{return $this->name.$n;}}
            $value=E::A;echo $value(7);
            """),
        new Case("enum-generated-methods-as-callables", """
            enum E:int {case A=1;}
            $cases=['E','cases'];$from='E::from';$try=['E','tryFrom'];
            echo $cases()[0]===E::A,':',$from(1)===E::A,':',$try(2)===null;
            """),
        new Case("enum-dynamic-static-receiver", """
            enum E:string {case A='a';}
            $name='E';echo $name::from('a')===E::A,':',count($name::cases()),':',$name::A->name;
            $case=E::A;echo ':',$case::tryFrom('a')===E::A;
            """),
        new Case("enum-builtin-arity-and-named-errors", """
            enum E:int {case A=1;}
            try{E::from();}catch(ArgumentCountError $error){echo 'missing:';}
            try{E::from(1,2);}catch(ArgumentCountError $error){echo 'extra:';}
            try{E::cases(1);}catch(ArgumentCountError $error){echo 'cases:';}
            try{E::tryFrom(unknown:1);}catch(Error $error){echo 'named:';}
            echo E::from(value:1)===E::A;
            """),
        new Case("backed-weak-scalar-conversions", """
            enum E:int {case A=1;}
            foreach([1,'1',1.0,true,false] as $value){$result=E::tryFrom($value);echo $result===null?'none':$result->name;echo ';';}
            foreach([[],new stdClass] as $value){try{E::from($value);}catch(TypeError $error){echo 'type;';}}
            """),
        new Case("backed-float-deprecation", """
            enum E:string {case A='1';case B='1.5';}
            echo E::from(1.5)->name;
            """),
        new Case("backed-float-string-deprecation", """
            enum E:int {case A=1;}
            echo E::from('1.5')->name;
            """),
        new Case("backed-null-deprecation", """
            enum E:int {case Zero=0;}
            echo E::tryFrom(null)->name;
            """),
        new Case("backed-nonnumeric-type-error", """
            enum E:int {case A=1;}
            try{E::tryFrom('abc');}catch(TypeError $error){echo 'type';}
            """),
        new Case("empty-unit-and-backed-enums", """
            enum EmptyUnit {} enum EmptyBacked:int {}
            echo count(EmptyUnit::cases()),':',count(EmptyBacked::cases()),':',EmptyBacked::tryFrom(0)===null;
            try{EmptyBacked::from(0);}catch(ValueError $error){echo ':missing';}
            """),
        new Case("duplicate-backing-check-is-lazy", """
            enum E:string {case A='x';case B='x';const OTHER=3;}
            echo 'declared:',enum_exists('E'),':';
            foreach(E::cases() as $case)echo $case->name,':',$case->value,';';
            try{echo E::A->name;}catch(Error $error){echo 'duplicate:';}
            try{echo E::OTHER;}catch(Error $error){echo 'constant:';}
            try{E::tryFrom('x');}catch(Error $error){echo 'lookup';}
            """),
        new Case("invalid-backing-type-cases-vs-lookup", """
            enum E:string {case A=1;case B='b';const OTHER=3;}
            echo 'declared:';foreach(E::cases()as$case)echo $case->name,':',$case->value,';';
            try{echo E::A->name;}catch(TypeError $error){echo 'case:';}
            try{echo E::OTHER;}catch(TypeError $error){echo 'constant';}
            """),
        new Case("readonly-name-and-value", """
            enum E:string {case A='a';}
            $value=E::A;
            try{$value->name='other';}catch(Error $error){echo 'name:';}
            try{$value->value='other';}catch(Error $error){echo 'value:';}
            echo E::A->name,':',E::A->value;
            """),
        new Case("readonly-property-references", """
            enum E:int {case A=1;}
            $value=E::A;
            try{$ref=&$value->name;}catch(Error $error){echo 'name:';}
            try{$ref=&$value->value;}catch(Error $error){echo 'value:';}
            $x=4;try{$value->value=&$x;}catch(Error $error){echo 'bind';}
            """),
        new Case("readonly-property-unset", """
            enum E:int {case A=1;}
            $value=E::A;
            try{unset($value->name);}catch(Error $error){echo 'name:';}
            try{unset($value->value);}catch(Error $error){echo 'value:';}
            unset($value->missing);echo isset($value->missing)?'wrong':'absent';
            """),
        new Case("enum-no-dynamic-properties", """
            enum E {case A;}
            $value=E::A;
            try{$value->other=1;}catch(Error $error){echo 'dynamic:';}
            try{$value->other[]=1;}catch(Error $error){echo 'array:';}
            echo isset($value->other)===false;
            """),
        new Case("enum-not-constructible-or-cloneable", """
            enum E {case A;}
            try{new E;}catch(Error $error){echo 'new:';}
            $name='E';try{new $name;}catch(Error $error){echo 'dynamic:';}
            try{$copy=clone E::A;}catch(Error $error){echo 'clone:';}
            echo E::A===E::cases()[0];
            """),
        new Case("enum-values-in-arrays-and-references", """
            enum E {case A;case B;}
            $values=[E::A];$copy=$values;$ref=&$values[0];$ref=E::B;
            echo $values[0]===E::B,':',$copy[0]===E::A,':',[E::A] === [E::A],':',[E::A] !== [E::B];
            """),
        new Case("enum-nullable-and-union-types", """
            enum E{case A;}enum F{case A;}
            function selected(E|F $value):?E{return $value instanceof E?$value:null;}
            echo selected(E::A)===E::A,':',selected(F::A)===null;
            try{selected(1);}catch(TypeError $error){echo ':type';}
            """),
        new Case("enum-conditional-declaration", """
            if(false){enum Absent{case A;}}
            function defineEnum(){enum E{case A;}}
            echo enum_exists('Absent',false)===false,':',enum_exists('E',false)===false,':';
            defineEnum();echo E::A->name;
            """),
        new Case("enum-autoload-and-kind", """
            spl_autoload_register(function($name){echo 'load:';eval('enum E:string{case A="a";}');});
            echo enum_exists('E'),':',class_exists('E',false),':',E::from('a')->name;
            """),
        new Case("enum-namespace-and-alias", """
            namespace Library;enum State:string {case Ready='r';}
            namespace App;use Library\\State as Status;
            echo Status::Ready->name,':',Status::from('r')===Status::Ready,':',Status::Ready instanceof Status;
            """),
        new Case("match-strict-scalar-types", """
            foreach([0,'0',false,null,1,1.0]as$value){echo match($value){0=>'int0','0'=>'string0',false=>'false',null=>'null',1=>'int1',1.0=>'float1'},';';}
            """),
        new Case("match-subject-evaluated-once", """
            $calls=0;function subject(){global $calls;$calls++;return 2;}
            echo match(subject()){1=>'one',2=>'two',default=>'other'},':',$calls;
            """),
        new Case("match-default-and-lazy-arms", """
            function condition($value){echo 'c'.$value.':';return $value;}
            function result($value){echo 'r'.$value.':';return $value;}
            echo match(2){default=>result('default'),condition(1)=>result('one'),condition(2),condition(3)=>result('two'),condition(4)=>result('four')};
            """),
        new Case("match-multiple-and-empty-arms", """
            echo match(3){1,2,3,=>'group',default=>'other'},':';
            try{$value=match(1){};}catch(UnhandledMatchError $error){echo 'empty:';}
            try{$value=match(1){2=>'two'};}catch(Error $error){echo 'unhandled';}
            """),
        new Case("match-ordered-nested-arrays", """
            $subject=['a'=>[1,2],'b'=>3];
            echo match($subject){['b'=>3,'a'=>[1,2]]=>'order-wrong',['a'=>[1,'2'],'b'=>3]=>'type-wrong',['a'=>[1,2],'b'=>3]=>'exact',default=>'wrong'};
            """),
        new Case("match-object-and-enum-identity", """
            class C{public $n=1;}$a=new C;$b=new C;
            enum E{case A;case B;}
            echo match($a){$b=>'wrong',$a=>'same'},':',match(E::B){E::A=>'wrong',E::B=>'B'};
            """),
        new Case("match-array-object-members", """
            class C{}$a=new C;$b=new C;
            echo match([$a]){[$b]=>'wrong',[$a]=>'same'};
            """),
        new Case("match-subject-retained-while-mutated", """
            $subject=[1];
            function condition(){global $subject;$subject[0]=2;return [0];}
            $result=match($subject){condition()=>'wrong',[1]=>[3],default=>'bad'};
            echo $result[0],':',$subject[0];
            """),
        new Case("match-variable-reads-after-condition", """
            $value=1;function condition(){global $value;$value=2;return 1;}
            echo match($value){condition()=>'one',2=>'two',default=>'none'},':',$value;
            """),
        new Case("match-property-is-a-snapshot", """
            class C{public $value=1;}$object=new C;
            function condition(){global $object;$object->value=2;return 1;}
            echo match($object->value){condition()=>'one',2=>'two',default=>'none'},':',$object->value;
            """),
        new Case("match-assignment-is-a-snapshot", """
            function condition(){global $value;$value=2;return 1;}
            echo match($value=1){condition()=>'one',2=>'two',default=>'none'},':',$value;
            """),
        new Case("match-undefined-variable-assigned-by-condition", """
            function condition(){global $value;$value=2;return 1;}
            echo match($value){condition()=>'one',2=>'two',default=>'none'};
            """),
        new Case("throw-expression-in-match", """
            function choose($value){return match($value){1=>'one',default=>throw new Exception('missing')};}
            echo choose(1),':';try{choose(2);}catch(Exception $error){echo $error->getMessage();}finally{echo ':finally';}
            """),
        new Case("throw-expression-in-coalesce", """
            $value=7;echo $value??throw new Exception('wrong');
            $value=null;try{$result=$value??throw new Exception('missing');}catch(Exception $error){echo ':',$error->getMessage();}
            """),
        new Case("match-return-in-finally", """
            function choose($n){try{return match($n){1=>[4],default=>throw new Exception('missing')};}finally{echo 'finally:';}}
            echo choose(1)[0],':';try{choose(2);}catch(Exception $error){echo 'caught';}
            """),
        new Case("clone-object-and-array-copy-on-write", """
            class C{public $values=[1];public $n=2;}
            $a=new C;$b=clone $a;$b->values[0]=9;$b->n=7;
            echo $a!==$b,':',$a->values[0],':',$b->values[0],':',$a->n,':',$b->n;
            """),
        new Case("clone-keeps-explicit-references", """
            class C{public $value=1;}
            $a=new C;$ref=&$a->value;$b=clone $a;$b->value=9;
            echo $a->value,':',$b->value,':',$ref;
            """),
        new Case("clone-calls-hook-on-new-object", """
            class C{public $n=1;public function __clone(){$this->n++;}}
            $a=new C;$b=clone $a;echo $a->n,':',$b->n;
            """),
        new Case("clone-visibility-error", """
            class C{private function __clone(){}}
            try{$copy=clone new C;}catch(Error $error){echo 'private:';}
            try{$copy=clone 1;}catch(Error $error){echo 'scalar';}
            """),
        new Case("clone-closure-captures", """
            $n=3;$closure=function()use($n){return $n;};$copy=clone $closure;
            echo $copy(),':',$closure!==$copy;
            """),
        new Case("async-enum-method-match", """
            enum E:int {case A=1;case B=2;function label():string{Async\\delay(1);return match($this){self::A=>'A',self::B=>'B'};}}
            echo E::from(2)->label();
            """,true),
        new Case("async-enum-autoload", """
            spl_autoload_register(function($name){Async\\delay(1);require __DIR__.'/E.php';});
            echo E::from('a')->name,':',enum_exists('E');
            """,Map.of("E.php","enum E:string{case A='a';}"),true),
        new Case("async-enum-generated-callable", """
            enum E:int{case A=1;}
            $task=Async\\spawn(function(){Async\\delay(1);return E::from(1);});
            echo Async\\await($task)===E::A;
            """,true),
        new Case("async-match-subject-and-condition", """
            function subject(){Async\\delay(1);return [1,2];}
            function condition($n){Async\\delay(1);return [1,$n];}
            function result(){Async\\delay(1);return [9];}
            echo (match(subject()){condition(0)=>'wrong',condition(2)=>result(),default=>'bad'})[0];
            """,true),
        new Case("async-match-cancellation-finally", """
            $entered=new Async\\Channel(1);$release=new Async\\Channel(1);
            $task=Async\\spawn(function()use($entered,$release){
                $wait=function()use($entered,$release){$entered->send(true);return $release->recv();};
                $subject=[1];try{return match($subject){$wait()=>'unused',default=>'none'};}
                finally{echo 'finally:';}
            });
            $entered->recv();$task->cancel();try{Async\\await($task);}catch(Throwable $error){echo 'cancel';}
            """,true),
        new Case("async-clone-hook", """
            class C{public $n=1;function __clone(){Async\\delay(1);$this->n=9;}}
            $a=new C;$b=clone $a;echo $a->n,':',$b->n;
            """,true),
        new Case("async-clone-cancellation", """
            $entered=new Async\\Channel(1);$release=new Async\\Channel(1);
            class C{function __clone(){global $entered,$release;try{$entered->send(true);$release->recv();}finally{echo 'clone-finally:';}}}
            $original=new C;$task=Async\\spawn(function()use($original){return clone $original;});
            $entered->recv();$task->cancel();try{Async\\await($task);}catch(Throwable $error){echo 'cancel:';}
            echo $original instanceof C;
            """,true),
        new Case("enum-case-missing-backing-rejected","enum E:int{case A;}","backing"),
        new Case("unit-case-value-rejected","enum E{case A=1;}","backing"),
        new Case("invalid-enum-backing-type-rejected","enum E:float{case A=1.0;}","backing"),
        new Case("duplicate-case-name-rejected","enum E{case A;case A;}","constant"),
        new Case("case-and-constant-conflict-rejected","enum E{case A;const A=1;}","constant"),
        new Case("enum-instance-property-rejected","enum E{case A;public $n=1;}","properties"),
        new Case("enum-static-property-rejected","enum E{case A;public static $n=1;}","properties"),
        new Case("enum-trait-property-rejected","trait T{public $n=1;}enum E{use T;case A;}","properties"),
        new Case("enum-constructor-rejected","enum E{case A;function __construct(){}}","magic"),
        new Case("enum-destructor-rejected","enum E{case A;function __destruct(){}}","magic"),
        new Case("enum-clone-hook-rejected","enum E{case A;function __clone(){}}","magic"),
        new Case("enum-tostring-rejected","enum E{case A;function __toString(){return 'a';}}","magic"),
        new Case("enum-missing-interface-method-rejected","interface I{function value():int;}enum E implements I{case A;}","abstract"),
        new Case("enum-generated-cases-redeclaration-rejected","enum E{case A;static function cases():array{return [];}}","redeclare"),
        new Case("enum-generated-from-redeclaration-rejected","enum E:int{case A=1;static function from($n):self{return self::A;}}","redeclare"),
        new Case("enum-generated-tryfrom-redeclaration-rejected","enum E:int{case A=1;static function tryFrom($n):?self{return self::A;}}","redeclare"),
        new Case("non-enum-unit-interface-rejected","class C implements UnitEnum{static function cases():array{return [];}}","non-enum"),
        new Case("non-enum-indirect-unit-interface-rejected","interface I extends UnitEnum{}class C implements I{static function cases():array{return [];}}","non-enum"),
        new Case("unit-enum-backed-interface-rejected","enum E implements BackedEnum{case A;}","non-backed"),
        new Case("enum-explicit-unit-interface-rejected","enum E implements UnitEnum{case A;}","interface"),
        new Case("enum-explicit-backed-interface-rejected","enum E:int implements BackedEnum{case A=1;}","interface"),
        new Case("match-duplicate-default-rejected","$n=match(1){default=>1,default=>2};","default")
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "enum-match");
    }
}
