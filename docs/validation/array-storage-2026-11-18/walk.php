<?php
$n = intval(getenv('ARRAY_ITEMS'));
$a = [];
for ($i = 0; $i < $n; $i++) $a[$i] = $i;
$it = new ArrayIterator($a);
$hole = getenv('ARRAY_LAYOUT') === 'hole';
if ($hole) unset($it[1]);
$sum = 0;
foreach ($it as $key => $value) $sum += $key + $value;
if ($sum !== $n * ($n - 1) - ($hole ? 2 : 0)) throw new Exception('Invalid traversal');
echo $sum, "\n";
