<?php
trait T {abstract function value():int;}
class C {use T{value as other;} function value():int{return 1;} function other():int{return 2;}}
echo (new C)->value(), ':', (new C)->other();
