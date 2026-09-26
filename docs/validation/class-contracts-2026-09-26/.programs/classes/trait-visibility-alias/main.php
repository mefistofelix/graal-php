<?php
trait T { function value(){return 7;} }
class C {use T {value as private secret; value as protected;} function run(){return $this->secret()+$this->value();}}
echo (new C)->run();
try {(new C)->value();} catch(Error $error){echo ':protected';}
try {(new C)->secret();} catch(Error $error){echo ':private';}
