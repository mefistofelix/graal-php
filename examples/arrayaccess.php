<?php
class A implements ArrayAccess {
    public array $d=['x'=>2,'n'=>null];
    public function offsetExists(mixed $o): bool { echo "E",$o,"|"; return $o === 'x' || $o === 'n'; }
    public function offsetGet(mixed $o): mixed { echo "G",$o,"|"; return $this->d[$o] ?? null; }
    public function offsetSet(mixed $o,mixed $v): void { echo "S",$o===null?'N':$o,":",$v,"|"; if($o===null)$this->d[]=$v;else$this->d[$o]=$v; }
    public function offsetUnset(mixed $o): void { echo "U",$o,"|"; unset($this->d[$o]); }
}
$a=new A;
echo $a['x'],":";
$a['x']=5; $a[]=7;
echo isset($a['x'])?1:0,":",empty($a['n'])?1:0,":",($a['x']??9),":",($a['n']??9),":";
$a['x']+=2; echo $a->d['x'],":";
unset($a['x']); echo isset($a['x'])?1:0;