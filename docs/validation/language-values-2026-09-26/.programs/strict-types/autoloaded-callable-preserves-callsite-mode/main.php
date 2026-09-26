<?php
declare(strict_types=1);spl_autoload_register(function($name){Async\delay(1);eval('class C{public static function f(int $n){return $n;}}');});
$f='C::f';try{$f('2');}catch(TypeError$e){echo 'strict';}
