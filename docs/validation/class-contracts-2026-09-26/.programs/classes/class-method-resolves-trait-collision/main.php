<?php
trait A {function run(){return 'A';}} trait B {function run(){return 'B';}}
class C {use A,B; function run(){return 'C';}}
echo (new C)->run();
