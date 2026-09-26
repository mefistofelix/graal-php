<?php
interface I { function value(): int; }
abstract class Base implements I { abstract protected function prefix(): int; public function value(): int { return $this->prefix()+1; } }
class Child extends Base { protected function prefix(): int { return 8; } }
echo (new Child)->value(), ':', class_exists('Base');
try { new Base; } catch (Error $error) { echo ':abstract'; }
