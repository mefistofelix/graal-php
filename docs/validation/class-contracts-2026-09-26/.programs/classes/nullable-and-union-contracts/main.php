<?php
interface I { function convert(int $value): int|string; }
class C implements I { function convert(int|string|null $value): string { return 'value:'.$value; } }
echo (new C)->convert(null), ':', (new C)->convert('x');
function identity(int|string $value): int|string { return $value; }
echo ':', identity('007'), ':', identity(7);
