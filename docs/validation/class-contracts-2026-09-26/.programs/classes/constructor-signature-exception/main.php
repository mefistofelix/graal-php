<?php
class Base { function __construct(int $value) {} }
class Child extends Base { public $value; function __construct(string $value, int $other) { $this->value = $value.$other; } }
echo (new Child('x',2))->value;
