<?php
class Base {protected function value(int $n): int {return $n+3;}}
trait T {abstract protected function value(int $n): int; function run(){return $this->value(4);}}
class C extends Base {use T;}
echo (new C)->run();
