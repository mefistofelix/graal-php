<?php
spl_autoload_register(function($name) {
    eval('class ' . $name . ' { public static $n = 4; public static function get() { return static::$n; } }');
});
Loaded::$n += 3; echo Loaded::get();
