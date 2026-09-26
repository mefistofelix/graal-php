<?php
trait T {public static $n=self::VALUE;}
class C {use T; const VALUE=7;} class D {use T; const VALUE=9;}
echo C::$n, ':', D::$n;
