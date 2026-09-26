<?php
interface A{function f(int $x);} interface B{function f(string $x);} interface C extends A,B{}