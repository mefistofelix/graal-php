<?php
;;;declare(strict_types=1);namespace Demo;
function f(int $n){return $n;}try{f('2');}catch(\TypeError$e){echo 'strict';}
