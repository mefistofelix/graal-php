<?php
$calls = 0; spl_autoload_register(function($name) use (&$calls) { $calls++; });
foreach (['', 'A B', 'A/B'] as $name) echo class_exists($name) === false;
echo ':', $calls, ':'; class_exists('404'); echo $calls;
