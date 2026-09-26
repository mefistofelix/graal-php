<?php
trait T {private $n=9; function value():int{Async\delay(1); return $this->n;} function closure(){return function(){Async\delay(1); return $this->value();};}}
class C {use T{value as alias;}}
$c=new C; $callback=$c->closure(); echo $c->alias(), ':', $callback();
