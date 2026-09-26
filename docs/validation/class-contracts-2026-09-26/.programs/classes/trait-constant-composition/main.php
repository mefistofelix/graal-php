<?php
trait A {const VALUE=2+3;} trait B {const VALUE=5;}
class C {use A,B; const VALUE=5;}
echo C::VALUE;
