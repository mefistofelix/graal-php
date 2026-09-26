<?php
trait T {function closure(){return function(){return __CLASS__.':'.$this->value;};}}
class C {use T; private $value=8;}
$callback=(new C)->closure(); echo $callback();
