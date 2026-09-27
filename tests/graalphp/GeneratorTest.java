package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

/** Identical PHP programs cover lazy generators, delegation, references, cleanup and TrueAsync suspension. */
public final class GeneratorTest {
    private static final List<Case> CASES = List.of(
        new Case("lazy-start-and-basic-state", """
            function g(){echo 'A';yield 'x';echo 'B';yield 'y';echo 'C';return 9;}
            $g=g();echo 'made:',$g->current(),':',$g->key(),':',$g->valid(),':';
            $g->next();echo $g->current(),':',$g->key(),':';$g->next();echo $g->valid()?'T':'F',':',$g->getReturn();
            """),
        new Case("next-before-start", "function g(){echo 'A';yield 1;echo 'B';yield 2;}$g=g();$g->next();echo ':',$g->current();"),
        new Case("send-before-start", "function g(){echo 'A';$x=yield 1;echo 'X',$x;yield 2;}$g=g();echo ':',$g->send(7),':',$g->current();"),
        new Case("send-expression-result", "function g(){$x=yield 'a'=>1;echo 'X',$x;yield 2;}$g=g();echo $g->current(),':',$g->send(7),':',$g->current();"),
        new Case("automatic-and-explicit-keys", "function g(){yield 'a';yield 10=>'b';yield 'c';yield -2=>'d';yield 'e';}foreach(g()as$k=>$v)echo $k,'=',$v,';';"),
        new Case("empty-yield", "function g(){yield;yield null=>2;}$g=g();echo $g->current()===null,':',$g->key(),':';$g->next();echo $g->current(),':',$g->key()===null;"),
        new Case("rewind-first-position", "function g(){yield 1;yield 2;}$g=g();$g->rewind();echo $g->current(),':';$g->rewind();echo $g->current(),':';$g->next();try{$g->rewind();}catch(Throwable$e){echo get_class($e),':',$e->getMessage();}"),
        new Case("get-return-early", "function g(){yield 1;return 9;}$g=g();try{$g->getReturn();}catch(Throwable$e){echo get_class($e),':',$e->getMessage();}"),
        new Case("throw-into-generator", "function g(){try{$x=yield 1;echo 'x',$x;}catch(Exception$e){echo 'caught';yield 2;}return 9;}$g=g();echo $g->current(),':',$g->throw(new Exception('x')),':',$g->current(),':';$g->next();echo $g->getReturn();"),
        new Case("terminal-exception-state", "function g(){yield 1;throw new Exception('boom');}$g=g();echo $g->current(),':';try{$g->next();}catch(Throwable$e){echo get_class($e),':',$e->getMessage(),':';}echo $g->valid()?'T':'F',':';try{$g->getReturn();}catch(Throwable$e){echo get_class($e),':',$e->getMessage();}"),
        new Case("completed-send-and-throw", "function g(){yield 1;return 9;}$g=g();$g->next();echo $g->send(3)===null,':';try{$g->throw(new Exception('x'));}catch(Throwable$e){echo get_class($e),':',$e->getMessage();}echo ':',$g->getReturn();"),
        new Case("generator-identity-and-interfaces", "function g(){yield 1;}$g=g();echo get_class($g),':',($g instanceof Generator),':',($g instanceof Iterator),':',($g instanceof Traversable),':',is_iterable($g);"),
        new Case("accepted-generator-return-types", "function a():Generator{yield 1;}function b():Traversable{yield 1;}function c():Iterator{yield 1;}function d():iterable{yield 1;}function e():object{yield 1;}function f():Generator|false{yield 1;}echo get_class(a()),get_class(b()),get_class(c()),get_class(d()),get_class(e()),get_class(f());"),
        new Case("bad-generator-return-type-is-declaration-fatal", "function g():int{yield 1;}echo 'UNEXPECTED_AFTER';", "generator return type"),
        new Case("parameters-checked-before-body", "function g(int $n){echo 'UNEXPECTED_BODY';yield $n;}try{g('x');}catch(TypeError$e){echo get_class($e);}"),
        new Case("nested-generator-does-not-convert-outer", "function outer(){$g=function(){yield 1;};echo get_class($g());return 9;}echo ':',outer();"),
        new Case("generator-closure", "$g=function($n){yield $n;return $n+1;};$v=$g(4);echo $v->current(),':';$v->next();echo $v->getReturn();"),
        new Case("generator-method-and-trait", "trait T{public function values(){yield 2;}}class C{use T;public static function more(){yield 3;}}echo(new C)->values()->current(),':',C::more()->current();"),
        new Case("yield-from-array", "function g(){yield 'a'=>1;yield from[5=>'x','z'=>'y'];yield 9;}foreach(g()as$k=>$v)echo $k,'=',$v,';';"),
        new Case("yield-from-generator-return", "function a(){yield 1;return 8;}function b(){$x=yield from a();echo 'R',$x;yield 2;}foreach(b()as$v)echo $v;"),
        new Case("yield-from-forwards-send", "function a(){$x=yield 1;echo 'A',$x;$y=yield 2;echo 'B',$y;return 7;}function b(){$r=yield from a();echo 'R',$r;yield 3;}$g=b();echo $g->current(),':',$g->send(5),':',$g->send(6),':',$g->current();"),
        new Case("yield-from-forwards-throw", "function a(){try{yield 1;}catch(Exception$e){echo 'AC';yield 2;}return 7;}function b(){$r=yield from a();echo 'R',$r;yield 3;}$g=b();echo $g->current(),':',$g->throw(new Exception('x')),':',$g->current();"),
        new Case("yield-from-iterator", "class I implements Iterator{public $i=0;public function rewind():void{echo'R';$this->i=0;}public function valid():bool{return$this->i<2;}public function current():mixed{return$this->i+4;}public function key():mixed{return$this->i+7;}public function next():void{$this->i++;}}function g(){$x=yield from new I;echo $x===null?'NULL':'OTHER';yield 9;}foreach(g()as$k=>$v)echo'[',$k,'=',$v,']';"),
        new Case("yield-from-invalid", "function g(){yield from 3;}try{g()->current();}catch(Throwable$e){echo get_class($e);}"),
        new Case("by-reference-generator", "function &g(&$x){yield $x;yield $x;}$x=1;foreach(g($x)as&$v){$v++;}echo$x;"),
        new Case("non-reference-generator-rejects-reference-foreach", "function g(){yield 1;}try{foreach(g()as&$v){}}catch(Throwable$e){echo get_class($e),':',$e->getMessage();}"),
        new Case("by-reference-yield-notice", "error_reporting(0);function &g(){yield 1;}$g=g();$g->current();$last=error_get_last();echo $last['type'],':',$last['message'];"),
        new Case("yield-from-forbidden-in-reference-generator", "function &g(){yield from[1];}echo'UNEXPECTED_AFTER';", "yield from"),
        new Case("destruction-runs-finally-before-next-statement", "function g(){try{yield 1;yield 2;}finally{echo'F';}}$g=g();echo$g->current(),':';unset($g);echo':done';"),
        new Case("unstarted-destruction-does-not-run-body", "function g(){try{echo'A';yield 1;}finally{echo'F';}}$g=g();unset($g);echo'done';"),
        new Case("destruction-unwind-is-not-catchable", "function g(){try{yield 1;}catch(Throwable$e){echo'UNEXPECTED_CAUGHT';}finally{echo'F';}echo'UNEXPECTED_AFTER';}$g=g();echo$g->current(),':';unset($g);echo':done';"),
        new Case("temporary-generator-receiver", "function g(){Async\\delay(1);yield 4;}echo g()->current();", true),
        new Case("async-generator-methods", "function g(){echo'A';Async\\delay(1);$x=yield 1;echo'B',$x;Async\\delay(1);yield 2;return 7;}$g=g();echo $g->current(),':',$g->send(5),':';$g->next();echo $g->getReturn();", true),
        new Case("iterator-builtins-on-generator", "function g(){yield'a'=>2;yield'b'=>3;}$g=g();$a=iterator_to_array($g);echo$a['a'],':',$a['b'],':';function h(){yield 1;yield 2;}echo iterator_count(h()),':',iterator_apply(h(),fn()=>true);"),
        new Case("reflection-generator-metadata", "function &g(){yield 1;}$r=new ReflectionFunction('g');echo$r->isGenerator(),':',$r->returnsReference();"),
        new Case("generator-cannot-be-created-or-cloned", "try{new Generator;}catch(Throwable$e){echo'new:',get_class($e),':';}function g(){yield 1;}$g=g();try{$x=clone $g;}catch(Throwable$e){echo'clone:',get_class($e);}"),
        new Case("async-cancellation-unwinds-generator", """
            $entered=new Async\\Channel(1);
            function g($entered){try{$entered->send(true);Async\\delay(10000);yield 1;}finally{echo'inner:';}}
            $task=Async\\spawn(function()use($entered){try{foreach(g($entered)as$v){echo'UNEXPECTED_BODY';}}finally{echo'outer:';}});
            $entered->recv();$task->cancel();try{Async\\await($task);}catch(Throwable$e){echo'cancelled';}
            """, true)
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "generator");
    }
}
