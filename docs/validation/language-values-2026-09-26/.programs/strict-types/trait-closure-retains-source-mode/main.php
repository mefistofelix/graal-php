<?php
require __DIR__.'/strict-trait.php';class C{use T;}$f=(new C)->make();
try{$f();}catch(TypeError$e){echo 'strict';}
