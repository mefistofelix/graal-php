<?php
declare(strict_types=1);class C{public int $n=1;public static int $s=2;public float $f=0.0;}
$c=new C;try{$c->n='3';}catch(TypeError$e){echo 'instance:';}
try{C::$s='3';}catch(TypeError$e){echo 'static:';}
$c->f=4;echo $c->n,':',C::$s,':',$c->f===4.0;
