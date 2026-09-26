<?php
declare(strict_types=1);
function integer(int $n){return $n;}function text(string $s){return $s;}
function flag(bool $b){return $b;}
foreach(['1',1.0,true,null]as$n){try{integer($n);}catch(TypeError$e){echo 'int:';}}
try{text(1);}catch(TypeError$e){echo 'string:';}
try{flag(1);}catch(TypeError$e){echo 'bool:';}
echo integer(1),':',text('ok'),':',flag(true);
