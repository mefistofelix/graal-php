<?php
require __DIR__.'/strict-class.php';$c=new C;$c->n='3';echo $c->n===3;
try{$c->set();}catch(TypeError$e){echo ':strict';}
