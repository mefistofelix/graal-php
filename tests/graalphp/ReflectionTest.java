package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

/** Identical PHP programs cover reflection metadata and lazy attribute validation/instantiation. */
public final class ReflectionTest {
    private static final List<Case> CASES = List.of(
        new Case("class-basics", """
            namespace N; class Base{} interface I{} trait T{} class C extends Base implements I{use T;}
            $r=new \\ReflectionClass(C::class);
            echo $r->getName(),':',$r->getShortName(),':',$r->getNamespaceName(),':',$r->inNamespace(),':';
            echo $r->isInterface()?'I':'-',':',$r->isTrait()?'T':'-',':',$r->isEnum()?'E':'-',':';
            echo $r->getParentClass()->getName(),':';foreach($r->getInterfaceNames() as $name)echo $name,',';
            """),
        new Case("reflection-object", """
            class C{} $o=new C; $r=new ReflectionObject($o);
            echo get_class($r),':',$r->getName(),':',$r instanceof ReflectionClass,':',$r instanceof Reflector;
            """),
        new Case("class-autoload", """
            spl_autoload_register(function($name){echo 'A';eval('class '.$name.' {}');});
            $r=new ReflectionClass('Loaded'); echo ':',$r->getName();
            """),
        new Case("function-and-closure", """
            #[Attribute(Attribute::TARGET_FUNCTION)] class A{}
            #[A] function f(#[A] $x, $y=2, ...$rest):int{return 1;}
            $r=new ReflectionFunction('f'); echo $r->getName(),':',count($r->getAttributes()),':',$r->getNumberOfParameters(),':',$r->getNumberOfRequiredParameters(),':',$r->isVariadic(),':',$r->hasReturnType(),':';
            foreach($r->getParameters() as$p)echo $p->getName(),'/',$p->getPosition(),'/',$p->isOptional(),'/',$p->isVariadic(),'/',$p->isPassedByReference(),'/'.count($p->getAttributes()).';';
            $c=#[A] function(#[A] $v){return $v;};$q=new ReflectionFunction($c);echo ':',$q->isClosure(),':',$q->getName(),':',count($q->getAttributes());
            """),
        new Case("method-metadata", """
            class C{
                #[A] public static function run(#[A] &$v,$n=3){}
                private function hidden(){}
            }
            #[Attribute(Attribute::TARGET_METHOD|Attribute::TARGET_PARAMETER)] class A{}
            $r=new ReflectionMethod(C::class,'run');
            echo $r->getName(),':',$r->getDeclaringClass()->getName(),':',$r->isStatic(),':',count($r->getAttributes()),':';
            $p=$r->getParameters()[0];echo $p->getName(),':',$p->isPassedByReference(),':',count($p->getAttributes()),':',$p->getDeclaringClass()->getName();
            """),
        new Case("class-method-queries", """
            class B{public function inherited(){}}
            class C extends B{public function own(){}}
            $r=new ReflectionClass(C::class);
            echo $r->hasMethod('own'),':',$r->hasMethod('inherited'),':',$r->hasMethod('missing')===false,':';
            echo $r->getMethod('inherited')->getDeclaringClass()->getName(),':',count($r->getMethods())>=2;
            """),
        new Case("property-metadata", """
            #[Attribute(Attribute::TARGET_PROPERTY)] class A{}
            class B{#[A] protected $base=1;} class C extends B{#[A] public static int $value=2; private $hidden=3;}
            $r=new ReflectionClass(C::class);echo $r->hasProperty('base'),':',$r->hasProperty('value'),':';
            $p=$r->getProperty('value');echo $p->getName(),':',$p->getDeclaringClass()->getName(),':',$p->isStatic(),':',$p->isPublic(),':',count($p->getAttributes()),':';
            echo $r->getProperty('base')->isProtected(),':',count($r->getProperties())>=3;
            """),
        new Case("constant-metadata", """
            #[Attribute(Attribute::TARGET_CLASS_CONSTANT)] class A{}
            class B{#[A] protected const B=2;} class C extends B{#[A] public final const X=11;}
            $r=new ReflectionClass(C::class);$x=$r->getReflectionConstant('X');
            echo $x->getName(),':',$x->getValue(),':',$x->getDeclaringClass()->getName(),':',$x->isPublic(),':',$x->isFinal(),':',count($x->getAttributes()),':';
            echo $r->getReflectionConstant('B')->isProtected(),':',$r->getReflectionConstant('Missing')===false,':',count($r->getReflectionConstants())>=2;
            """),
        new Case("attribute-arguments-and-instance", """
            #[Attribute(Attribute::TARGET_CLASS|Attribute::IS_REPEATABLE)]
            class Tag{public $x;public $y;function __construct($x,$y=2){$this->x=$x;$this->y=$y;echo 'N'.$x.$y;}}
            #[Tag(y:4,x:3),Tag(5)] class C{}
            $r=new ReflectionClass(C::class);$a=$r->getAttributes();
            echo count($a),':';foreach($a as$v){echo $v->getName(),'/',$v->getTarget(),'/',$v->isRepeated(),'/';foreach($v->getArguments()as$k=>$x)echo $k,'=',$x,',';echo ';';}
            $o=$a[0]->newInstance();echo ':',get_class($o),':',$o->x,':',$o->y;
            """),
        new Case("attribute-exact-filter", """
            #[Attribute]class A{} #[Attribute]class B{} #[A,B,A]class C{}
            $r=new ReflectionClass(C::class);echo count($r->getAttributes()),':',count($r->getAttributes(A::class)),':',count($r->getAttributes('a'));
            """),
        new Case("attribute-instanceof-filter", """
            #[Attribute]class Base{} #[Attribute]class Child extends Base{} #[Base,Child]class C{}
            $r=new ReflectionClass(C::class);echo count($r->getAttributes(Base::class)),':',count($r->getAttributes(Base::class,ReflectionAttribute::IS_INSTANCEOF));
            """),
        new Case("attribute-instanceof-autoload", """
            spl_autoload_register(function($name){echo 'A'.$name.':';if($name==='Base')eval('#[Attribute] class Base{}');else if($name==='Child')eval('#[Attribute] class Child extends Base{}');});
            #[Child] class C{} $r=new ReflectionClass(C::class);echo count($r->getAttributes('Base',ReflectionAttribute::IS_INSTANCEOF));
            """),
        new Case("unknown-attribute-remains-lazy", """
            #[Missing(1)]class C{}$a=(new ReflectionClass(C::class))->getAttributes()[0];
            echo $a->getName(),':';foreach($a->getArguments()as$v)echo $v,':';
            try{$a->newInstance();}catch(Error$e){echo 'missing';}
            """),
        new Case("attribute-target-validation-is-lazy", """
            #[Attribute(Attribute::TARGET_METHOD)]class A{} #[A]class C{}
            echo 'declared:';$a=(new ReflectionClass(C::class))->getAttributes()[0];echo $a->getTarget(),':';
            try{$a->newInstance();}catch(Error$e){echo 'target';}
            """),
        new Case("attribute-repeat-validation-is-lazy", """
            #[Attribute]class A{} #[A,A]class C{} $a=(new ReflectionClass(C::class))->getAttributes();
            echo count($a),':',$a[0]->isRepeated(),':';
            try{$a[1]->newInstance();}catch(Error$e){echo 'repeat';}
            """),
        new Case("non-attribute-class-validation-is-lazy", """
            class A{} #[A]class C{}$a=(new ReflectionClass(C::class))->getAttributes()[0];echo $a->getName(),':';
            try{$a->newInstance();}catch(Error$e){echo 'not-attribute';}
            """),
        new Case("attribute-alias-and-namespace", """
            namespace A;#[\\Attribute]class Mark{}
            namespace B;use A\\Mark as M;#[M]class C{}
            $a=(new \\ReflectionClass(C::class))->getAttributes()[0];echo $a->getName(),':',count((new \\ReflectionClass(C::class))->getAttributes(\\A\\Mark::class));
            """),
        new Case("method-property-parameter-targets", """
            #[Attribute(Attribute::TARGET_METHOD|Attribute::TARGET_PROPERTY|Attribute::TARGET_PARAMETER|Attribute::TARGET_CLASS_CONSTANT)]class A{}
            class C{#[A]public $p;#[A]const X=1;#[A]function f(#[A]$x){}}
            $r=new ReflectionClass(C::class);
            echo $r->getMethod('f')->getAttributes()[0]->getTarget(),':',$r->getProperty('p')->getAttributes()[0]->getTarget(),':';
            echo $r->getMethod('f')->getParameters()[0]->getAttributes()[0]->getTarget(),':',$r->getReflectionConstant('X')->getAttributes()[0]->getTarget();
            """),
        new Case("attribute-constant-expression", """
            class Flags{const X=7;} #[Attribute]class A{public $v;function __construct($v){$this->v=$v;}}
            #[A(Flags::X+2)]class C{}$a=(new ReflectionClass(C::class))->getAttributes()[0];
            echo $a->getArguments()[0],':',$a->newInstance()->v;
            """),
        new Case("parameter-default-reflection", """
            class C{const X=9;function f($a=self::X,$b=null,...$rest){}}
            $p=(new ReflectionMethod(C::class,'f'))->getParameters();
            echo $p[0]->isDefaultValueAvailable(),':',$p[0]->getDefaultValue(),':',$p[1]->isOptional(),':',$p[2]->isVariadic();
            """),
        new Case("reflection-type-relations", """
            class C{}$r=new ReflectionClass(C::class);
            function accept(Reflector $r):ReflectionClass{return $r;}
            echo get_class(accept($r)),':',$r instanceof Reflector,':',$r instanceof ReflectionClass;
            """),
        new Case("invalid-attribute-flags", """
            #[Attribute]class A{}#[A]class C{}$r=new ReflectionClass(C::class);
            try{$r->getAttributes(null,1);}catch(ValueError$e){echo 'flags';}
            """),
        new Case("missing-members", """
            class C{}$r=new ReflectionClass(C::class);
            try{$r->getMethod('x');}catch(ReflectionException$e){echo 'm:';}
            try{$r->getProperty('x');}catch(ReflectionException$e){echo 'p:';}
            echo $r->getReflectionConstant('x')===false;
            """),
        new Case("async-attribute-constructor", """
            #[Attribute]class A{public $v;function __construct($v){Async\\delay(1);$this->v=$v;}}
            #[A(7)]class C{}$a=(new ReflectionClass(C::class))->getAttributes()[0];echo $a->newInstance()->v;
            """, true),
        new Case("async-attribute-autoload", """
            spl_autoload_register(function($name){Async\\delay(1);eval('#[Attribute] class Loaded{public $v;function __construct($v){Async\\delay(1);$this->v=$v;}}');});
            #[Loaded(8)]class C{}$a=(new ReflectionClass(C::class))->getAttributes()[0];echo $a->newInstance()->v;
            """, true)
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "reflection");
    }
}
