<?php
spl_autoload_register(function($name) {
    Async\delay(1);
    eval('class Loaded { public static function change(&$n, $a) { Async\delay(1); $n += $a[0]; return $n; } }');
});
$callback = 'Loaded::change'; $n = 2;
echo $callback(a: [7], n: $n), ':', $n;
