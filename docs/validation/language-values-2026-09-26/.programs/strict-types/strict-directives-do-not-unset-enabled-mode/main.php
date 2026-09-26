<?php
declare(strict_types=1);declare(strict_types=0);
function f(int $n){return $n;}try{f('2');}catch(TypeError$e){echo 'strict';}
