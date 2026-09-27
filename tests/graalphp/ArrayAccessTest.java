package graalphp;

import java.util.List;
import graalphp.ClassContractsTest.Case;

/** Identical PHP programs cover ArrayAccess dispatch, ordinary l-values and suspending offsets. */
public final class ArrayAccessTest {
    private static final List<Case> CASES = List.of(
        new Case("interface-identity", """
            echo interface_exists('ArrayAccess'), ':';
            class A implements ArrayAccess {
                public function offsetExists(mixed $o): bool{return false;}
                public function offsetGet(mixed $o): mixed{return null;}
                public function offsetSet(mixed $o,mixed $v): void{}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A; echo $a instanceof ArrayAccess;
            """),
        new Case("read-write-append-unset", """
            class A implements ArrayAccess {
                public array $d=['x'=>2];
                public function offsetExists(mixed $o): bool{return $o==='x';}
                public function offsetGet(mixed $o): mixed{echo'G',$o,'|';return $this->d[$o]??null;}
                public function offsetSet(mixed $o,mixed $v): void{echo'S',($o===null?'N':$o),':',$v,'|';if($o===null)$this->d[]=$v;else$this->d[$o]=$v;}
                public function offsetUnset(mixed $o): void{echo'U',$o,'|';unset($this->d[$o]);}
            }
            $a=new A;echo$a['x'],':';echo($a['x']=5),':';$a[]=7;echo$a->d['x'],':',$a->d[0],':';unset($a['x']);echo isset($a->d['x'])?1:0;
            """),
        new Case("isset-empty-coalesce", """
            class A implements ArrayAccess {
                public array $d=['x'=>5,'n'=>null];
                public function offsetExists(mixed $o): bool{echo'E',$o,'|';return$o==='x'||$o==='n';}
                public function offsetGet(mixed $o): mixed{echo'G',$o,'|';return$this->d[$o]??null;}
                public function offsetSet(mixed $o,mixed $v): void{}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;echo isset($a['x'])?1:0,':',isset($a['z'])?1:0,':',empty($a['x'])?1:0,':',empty($a['z'])?1:0,':',($a['x']??9),':',($a['n']??9),':',($a['z']??9);
            """),
        new Case("coalesce-assign", """
            class A implements ArrayAccess {
                public array $d=['x'=>null];
                public function offsetExists(mixed $o): bool{echo'E',$o,'|';return$o==='x';}
                public function offsetGet(mixed $o): mixed{echo'G',$o,'|';return$this->d[$o]??null;}
                public function offsetSet(mixed $o,mixed $v): void{echo'S',$o,':',$v,'|';$this->d[$o]=$v;}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;echo($a['x']??=7),':',($a['y']??=8),':',$a->d['x'],':',$a->d['y'];
            """),
        new Case("compound-assignment", """
            class A implements ArrayAccess {
                public array $d=['x'=>3];
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{echo'G|';return$this->d[$o];}
                public function offsetSet(mixed $o,mixed $v): void{echo'S',$v,'|';$this->d[$o]=$v;}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;echo($a['x']+=4),':',$a->d['x'];
            """),
        new Case("increment-is-indirect", """
            error_reporting(0);
            class A implements ArrayAccess {
                public array $d=['x'=>2];
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{echo'G|';return$this->d[$o];}
                public function offsetSet(mixed $o,mixed $v): void{echo'UNEXPECTED_SET';$this->d[$o]=$v;}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;$pre=++$a['x'];$post=$a['x']++;$last=error_get_last();
            echo$pre,':',$post,':',$a->d['x'],':',$last['type'],':',$last['message'];
            """),
        new Case("temporary-receiver-and-key-once", """
            class A implements ArrayAccess {
                public array $d=['x'=>2];
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{echo'G|';return$this->d[$o];}
                public function offsetSet(mixed $o,mixed $v): void{echo'S',$v,'|';$this->d[$o]=$v;}
                public function offsetUnset(mixed $o): void{}
            }
            function make(){echo'M|';return new A;}function keyName(){echo'K|';return'x';}
            echo make()[keyName()],':';make()[keyName()]=5;echo'done';
            """),
        new Case("property-receiver", """
            class A implements ArrayAccess {
                public array $d=['x'=>2];
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{return$this->d[$o];}
                public function offsetSet(mixed $o,mixed $v): void{$this->d[$o]=$v;}
                public function offsetUnset(mixed $o): void{}
            }
            class H{public A $a;function __construct(){$this->a=new A;}}
            $h=new H;echo$h->a['x'],':';$h->a['x']=8;echo$h->a['x'];
            """),
        new Case("nested-read", """
            class A implements ArrayAccess {
                public array $d=['nested'=>['y'=>3]];
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{echo'G|';return$this->d[$o];}
                public function offsetSet(mixed $o,mixed $v): void{}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;echo$a['nested']['y'];
            """),
        new Case("native-array-location-regression", """
            function bump(&$x){$x++;}$a=['x'=>1,'n'=>null];bump($a['x']);$a['x']+=3;
            echo$a['x'],':',isset($a['x'])?1:0,':',isset($a['n'])?1:0,':',($a['n']??7),':';
            unset($a['x']);echo isset($a['x'])?1:0;
            """),
        new Case("string-offset-regression", """
            $s='abc';echo$s[1],':';$s[1]='Z';echo$s,':',isset($s[2])?1:0,':',empty($s[9])?1:0;
            """),
        new Case("by-value-argument", """
            class A implements ArrayAccess {
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{echo'G|';return 6;}
                public function offsetSet(mixed $o,mixed $v): void{}
                public function offsetUnset(mixed $o): void{}
            }
            function value($x){echo$x+1;}value((new A)['x']);
            """),
        new Case("append-passes-null", """
            class A implements ArrayAccess {
                public function offsetExists(mixed $o): bool{return false;}
                public function offsetGet(mixed $o): mixed{return null;}
                public function offsetSet(mixed $o,mixed $v): void{echo$o===null?'NULL':'OTHER',':',$v;}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;$a[]=9;
            """),
        new Case("async-offset-get", """
            class A implements ArrayAccess {
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{echo'A';Async\\delay(1);echo'B';return 4;}
                public function offsetSet(mixed $o,mixed $v): void{}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;echo':',$a['x'];
            """, true),
        new Case("async-offset-set", """
            class A implements ArrayAccess {
                public int $v=0;
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{return$this->v;}
                public function offsetSet(mixed $o,mixed $v): void{echo'A';Async\\delay(1);$this->v=$v;echo'B';}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;echo($a['x']=7),':',$a->v;
            """, true),
        new Case("async-offset-exists", """
            class A implements ArrayAccess {
                public function offsetExists(mixed $o): bool{echo'A';Async\\delay(1);echo'B';return$o==='x';}
                public function offsetGet(mixed $o): mixed{echo'G';return 2;}
                public function offsetSet(mixed $o,mixed $v): void{}
                public function offsetUnset(mixed $o): void{}
            }
            $a=new A;echo isset($a['x'])?1:0,':',empty($a['z'])?1:0,':',($a['x']??9);
            """, true),
        new Case("async-offset-unset", """
            class A implements ArrayAccess {
                public int $v=1;
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{return$this->v;}
                public function offsetSet(mixed $o,mixed $v): void{}
                public function offsetUnset(mixed $o): void{echo'A';Async\\delay(1);$this->v=0;echo'B';}
            }
            $a=new A;unset($a['x']);echo':',$a->v;
            """, true),
        new Case("return-type-will-change", """
            class A implements ArrayAccess {
                #[ReturnTypeWillChange] public function offsetExists($o){return true;}
                #[ReturnTypeWillChange] public function offsetGet($o){return 3;}
                #[ReturnTypeWillChange] public function offsetSet($o,$v){}
                #[ReturnTypeWillChange] public function offsetUnset($o){}
            }
            echo(new A)['x'];
            """),
        new Case("missing-required-method-rejected", """
            class A implements ArrayAccess {
                public function offsetExists(mixed $o): bool{return true;}
                public function offsetGet(mixed $o): mixed{return null;}
                public function offsetSet(mixed $o,mixed $v): void{}
            }
            echo'UNEXPECTED_AFTER';
            """, "abstract"),
        new Case("non-arrayaccess-object-rejected", "class A{} $a=new A; echo $a['x']; echo 'UNEXPECTED_AFTER';", "array")
    );

    public static void main(String[] arguments) throws Exception {
        ClassContractsTest.run(arguments, CASES, "arrayaccess");
    }
}
