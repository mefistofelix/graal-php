<?php
declare(strict_types=1);require __DIR__.'/weak.php';
try{definedWeak('2');}catch(TypeError$e){echo 'strict';}
echo ':',weakReturn();
