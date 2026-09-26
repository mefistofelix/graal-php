<?php
trait T {public static $n=1; public static function bump(){static::$n++;}}
class A {use T;} class B extends A {use T;} class C extends A {}
A::bump(); B::bump(); B::bump(); echo A::$n, ':', B::$n, ':', C::$n;
