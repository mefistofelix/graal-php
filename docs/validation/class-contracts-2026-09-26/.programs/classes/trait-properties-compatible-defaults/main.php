<?php
trait A {public $value=1+1; public $items=[1,2];}
trait B {public $value=2; public $items=[1,2];}
class C {use A,B; public $value=2;}
echo (new C)->value, ':', (new C)->items[1];
