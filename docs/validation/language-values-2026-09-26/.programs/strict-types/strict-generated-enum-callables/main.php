<?php
declare(strict_types=1);enum E:int{case A=1;}$f='E::from';
try{$f('1');}catch(TypeError$e){echo 'string:';}
$f=['E','tryFrom'];try{$f(1.0);}catch(TypeError$e){echo 'float:';}
echo $f(1)->name;
