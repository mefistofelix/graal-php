<?php
spl_autoload_register(function($name) {
    echo 'base:';
    if ($name === 'Loader') eval('class Loader { public static function load($name) { echo $name; } }');
});
echo spl_autoload_register(['Loader', 'load']), ':';
class_exists('Missing');
