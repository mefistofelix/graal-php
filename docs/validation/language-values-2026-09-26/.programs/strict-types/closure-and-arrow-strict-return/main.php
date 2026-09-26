<?php
declare(strict_types=1);$closure=function():int{return '2';};$arrow=fn():int=>'2';
foreach([$closure,$arrow]as$f){try{$f();}catch(TypeError$e){echo 'return:';}}
