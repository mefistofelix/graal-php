<?php
trait T {public static $n=parent::VALUE;}
class Base {const VALUE=8;} class C extends Base {use T;}
echo C::$n;
