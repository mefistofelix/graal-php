<?php
function calculate(&$value) {
    try {
        sleep_ms(5);
        $value += 1;
        return $value;
    } finally {
        echo "finally\n";
    }
}
function child($name) {
    echo context_get('request'), ':', $name, "\n";
    sleep_ms(2);
    return 7;
}
function worker($counter) {
    $i = 0;
    while ($i < 1000) {
        shared_add($counter, 1);
        $i += 1;
    }
    return shared_get($counter);
}
$a = [1, 2, 3];
$b = $a;
foreach ($a as &$value) { $value += 10; }
unset($value);
echo $a[0], ':', $b[0], "\n";
$x = 40;
echo calculate($x), ':', $x, "\n";
context_set('request', 'demo');
$task = spawn('child', 'task');
echo 'await=', await($task), "\n";
$counter = shared_counter(0);
$first = parallel('worker', $counter);
$second = parallel('worker', $counter);
await($first);
await($second);
echo 'shared=', shared_get($counter), "\n";
echo 'eval=', eval('return 6 * 7;'), "\n";
