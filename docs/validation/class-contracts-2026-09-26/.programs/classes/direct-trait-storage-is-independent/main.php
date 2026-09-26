<?php
error_reporting(0);
trait T {public static $n=1;} class C {use T;}
T::$n=7; echo T::$n, ':', C::$n;
C::$n=9; echo ':',T::$n, ':',C::$n;
