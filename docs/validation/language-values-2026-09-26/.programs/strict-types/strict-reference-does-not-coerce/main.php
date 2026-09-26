<?php
declare(strict_types=1);function f(int &$n){$n=3;}
$n='2';try{f($n);}catch(TypeError$e){echo 'type:';}echo $n==='2';
