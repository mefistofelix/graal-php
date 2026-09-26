<?php
declare(strict_types=1);class C{public function __construct(int $n){}public static function value(int $n){return $n;}}
try{new C(n:'2');}catch(TypeError$e){echo 'constructor:';}
try{C::value(n:'2');}catch(TypeError$e){echo 'method:';}
$f=['C','value'];try{$f(n:'2');}catch(TypeError$e){echo 'callable';}
