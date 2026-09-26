<?php
$n=3;$closure=function()use($n){return $n;};$copy=clone $closure;
echo $copy(),':',$closure!==$copy;
