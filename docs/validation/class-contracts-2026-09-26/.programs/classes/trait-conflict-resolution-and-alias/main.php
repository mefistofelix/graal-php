<?php
trait A {function run(){return 'A';}} trait B {function run(){return 'B';}}
class C {use A,B { A::run insteadof B; B::run as other; }}
echo (new C)->run(), ':', (new C)->other();
