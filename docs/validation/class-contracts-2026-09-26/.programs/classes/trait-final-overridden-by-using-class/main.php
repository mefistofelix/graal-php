<?php
trait T {final public function value(){return 1;}}
class C {use T; public function value(){return 2;}}
echo (new C)->value();
