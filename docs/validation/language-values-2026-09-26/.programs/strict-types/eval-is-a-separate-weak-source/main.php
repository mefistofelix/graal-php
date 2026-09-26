<?php
declare(strict_types=1);function f(int $n){return $n;}
echo eval('return f("3");');
try{eval('declare(strict_types=1);return f("4");');}catch(TypeError$e){echo ':strict';}
