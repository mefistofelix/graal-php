<?php
interface I { static function value(int $n = 2): int; }
abstract class Base { abstract public static function value(int $n = 2): int; }
class C extends Base implements I { public static function value(int $n = 2): int { return $n*3; } }
echo C::value(), ':', C::value(3);
