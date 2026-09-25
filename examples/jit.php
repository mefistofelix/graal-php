<?php
function kernel($value) { return $value * 3 + 1; }
$i = 0;
$sum = 0;
while ($i < 20000) {
    $sum += kernel($i);
    $i += 1;
}
echo $sum, "\n";
