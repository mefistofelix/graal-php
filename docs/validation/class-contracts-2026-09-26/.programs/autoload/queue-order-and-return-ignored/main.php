<?php
function first($name) { echo 'A'; return true; }
function second($name) { echo 'B'; eval('class ' . $name . ' {}'); return false; }
function third($name) { echo 'C'; }
spl_autoload_register('second'); spl_autoload_register('third');
spl_autoload_register('first', prepend: true);
echo class_exists('Loaded'), ':', count(spl_autoload_functions());
