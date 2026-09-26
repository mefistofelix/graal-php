<?php
require __DIR__.'/strict.php';
try{strictReturn();}catch(TypeError$e){echo 'return:';}
echo widenedReturn()===3.0;
