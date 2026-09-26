<?php
interface I {function value(int $n):int;}
class C implements I {function value(int $n):int{Async\delay(1); return $n+4;}}
echo (new C)->value(3);
