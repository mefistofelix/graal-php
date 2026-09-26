<?php
interface I { function add(int &$n, int ...$values): int; }
class C implements I { function add(int &$n, int ...$values): int { foreach($values as $value) $n += $value; return $n; } }
$n=2; echo (new C)->add($n,3,4), ':', $n;
