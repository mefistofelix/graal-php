<?php
$next = function($name) { echo 'B'; };
spl_autoload_register(function($name) use ($next) { echo 'A'; spl_autoload_register($next); });
class_exists('Missing'); echo ':'; class_exists('Other');
