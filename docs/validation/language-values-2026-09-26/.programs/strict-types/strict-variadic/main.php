<?php
declare(strict_types=1);function f(int ...$ns){return count($ns);}
echo f(1,2);try{f(1,'2');}catch(TypeError$e){echo ':type';}
