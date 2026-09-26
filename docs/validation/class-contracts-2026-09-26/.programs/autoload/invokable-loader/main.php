<?php
class Loader { public function __invoke($name) { echo $name; } }
$loader = new Loader; spl_autoload_register($loader);
$list = spl_autoload_functions(); echo count($list), ':';
class_exists('Missing'); echo ':', spl_autoload_unregister($loader);
