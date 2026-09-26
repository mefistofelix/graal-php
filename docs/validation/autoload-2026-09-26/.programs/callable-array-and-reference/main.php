<?php
spl_autoload_register(function($name) {
    eval('class ' . $name . ' { public static function change(&$n, $a = 2) { $n += $a; return $n; } }');
});
$callback = [1 => 'change', 0 => 'Loaded']; $n = 5;
echo $callback(a: 3, n: $n), ':', $n;
