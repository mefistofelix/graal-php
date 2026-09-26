<?php
trait T {abstract private function value(): int; function run(){return $this->value();}}
class C {use T; private function value(): int{return 8;}}
echo (new C)->run();
