<?php
function bump(&$value) { $value += 2; return $value; }
function sum($array) {
    $total = 0;
    foreach ($array as $key => $value) { $total += $value; }
    return $total;
}
$a = [1, 2, 3];
$b = $a;
$a[1] = 20;
echo sum($a), ':', sum($b), "\n";
foreach ($a as &$value) { $value += 1; }
unset($value);
echo $a[0], ':', $a[1], ':', $a[2], "\n";
$x = 5;
$y =& $x;
echo bump($y), ':', $x, "\n";
try { throw new Exception('failure'); }
catch (Throwable $error) { echo "caught\n"; }
finally { echo "cleanup\n"; }
$n = 0;
while ($n < 10) {
    $n += 1;
    if ($n == 2) { continue; }
    if ($n == 4) { break; }
    echo $n;
}
echo "\n";
echo eval('return 6 * 7;'), "\n";
