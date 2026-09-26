<?php
interface A {} interface B {} class Both implements A, B {}
interface I { function f(A&B $value): A; }
class C implements I { function f(A $value): A&B { return new Both; } }
echo get_class((new C)->f(new Both));
