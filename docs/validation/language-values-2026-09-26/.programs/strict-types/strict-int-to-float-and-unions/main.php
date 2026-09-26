<?php
declare(strict_types=1);
function f(float $n):float{return $n;}function u(float|string $n){return $n;}
echo f(1)===1.0,':',u(2)===2.0,':',u('2')==='2';
