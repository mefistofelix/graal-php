<?php
interface A { function a(); } interface B { function b(); }
interface C extends A, B { function c(); }
class D implements C { function a(){return 1;} function b(){return 2;} function c(){return 3;} }
$d = new D; echo $d->a(), $d->b(), $d->c(), ':', $d instanceof A, ':', $d instanceof B, ':', $d instanceof C;
