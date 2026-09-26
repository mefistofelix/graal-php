<?php
class Base { public static function make(): static { return new static; } }
class Child extends Base {}
echo get_class(Child::make()), ':', get_class(Base::make());
