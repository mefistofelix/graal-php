<?php
require __DIR__.'/strict-trait.php';class C{use T{value as alias;}}
foreach(['value','alias']as$name){$f=['C',$name];try{$f();}catch(TypeError$e){echo 'strict:';}}
