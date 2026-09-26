<?php
interface I { function value(int $n): int; }
class Base { public function value(int $n): int { return $n+1; } }
class Child extends Base implements I {}
echo (new Child)->value(4), ':', (new Child) instanceof I;
