<?php
function first($name) { echo 'A'; }
function second($name) { echo 'B'; }
spl_autoload_register('first');
$list = spl_autoload_functions(); $list[0] = 'second'; $list[] = 'second';
echo count(spl_autoload_functions()), ':'; class_exists('Missing');
