<?php
spl_autoload_register(function($name) { eval('class ' . $name . ' { public static function value() { return 9; } }'); });
$callback = '\Loaded::value'; echo $callback();
