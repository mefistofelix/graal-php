<?php
class Loader { public $n = 3; public function load($name) { echo $this->n, ':'; } }
function register() {
    $object = new Loader; $value = [7];
    spl_autoload_register([$object, 'load']);
    spl_autoload_register(function($name) use ($value) { echo $value[0], ':', $name; });
}
register(); class_exists('Missing');
