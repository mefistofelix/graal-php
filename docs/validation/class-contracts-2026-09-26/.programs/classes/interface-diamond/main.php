<?php
interface RootContract { const VALUE = 7; function run(): int; }
interface A extends RootContract {} interface B extends RootContract {}
interface Combined extends A, B {}
class C implements Combined { function run(): int {return self::VALUE;} }
echo (new C)->run(), ':', C::VALUE;
