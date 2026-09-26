<?php
trait T {function value(){return __CLASS__.':'.__TRAIT__.':'.__METHOD__.':'.__FUNCTION__;}}
class C {use T {value as alias;}} class D {use T;}
echo (new C)->value(), ';', (new C)->alias(), ';', (new D)->value();
