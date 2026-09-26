<?php
function first($name) { echo 'A'; }
function second($name) { echo 'B'; spl_autoload_unregister('first'); }
function third($name) { echo 'C'; }
spl_autoload_register('first'); spl_autoload_register('second'); spl_autoload_register('third');
class_exists('Missing');
