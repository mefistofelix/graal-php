<?php
function second($name) { echo 'B'; }
function third($name) { echo 'C'; }
function first($name) {
    echo 'A'; spl_autoload_unregister('second'); spl_autoload_unregister('first');
}
spl_autoload_register('first'); spl_autoload_register('second'); spl_autoload_register('third');
class_exists('Missing'); echo ':'; class_exists('Other');
