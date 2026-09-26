<?php
require __DIR__.'/strict.php';echo definedStrict('2');
try{strictCaller();}catch(TypeError$e){echo ':nested';}
function globalInteger(int $n){return $n;}
