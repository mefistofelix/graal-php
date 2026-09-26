<?php
class Base { private function value(){ return 'base'; } public function call(){return $this->value();} }
class Child extends Base { public function value(){return 'child';} }
echo (new Child)->call(), ':', (new Child)->value();
