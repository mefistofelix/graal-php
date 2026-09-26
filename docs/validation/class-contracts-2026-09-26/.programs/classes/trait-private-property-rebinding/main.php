<?php
trait T {private $n=2; function value(){return $this->n;} function change($n){$this->n=$n;}}
class A {use T; function baseValue(){return $this->n;}}
class B extends A {use T;}
$b=new B; $b->change(9); echo $b->value(), ':', $b->baseValue();
