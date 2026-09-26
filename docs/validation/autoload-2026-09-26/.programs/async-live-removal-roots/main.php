<?php
$loader = null; $payload = [9];
$loader = function($name) use (&$loader, $payload) {
    spl_autoload_unregister($loader); unset($loader);
    try { Async\delay(1); echo $payload[0], ':'; eval('class Loaded {}'); }
    finally { echo 'finally:'; }
};
spl_autoload_register($loader); unset($payload);
echo class_exists('Loaded'), ':', count(spl_autoload_functions());
