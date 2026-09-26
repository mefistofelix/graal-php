<?php
class Base {const VALUE=1; public $n=1;}
trait T {const VALUE=2; public $n=2;}
class C extends Base {use T;}
echo C::VALUE, ':', (new C)->n;
