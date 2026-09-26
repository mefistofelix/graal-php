<?php
trait RootTrait { function value(){return 5;} }
trait A {use RootTrait;} trait B {use RootTrait;}
trait Combined {use A,B;}
class C {use Combined;}
echo (new C)->value();
