<?php
interface I { function value(): int; }
abstract class Base implements I {}
class Child extends Base { function value(): int { return 4; } }
echo (new Child)->value();
