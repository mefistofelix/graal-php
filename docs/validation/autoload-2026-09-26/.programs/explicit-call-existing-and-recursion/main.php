<?php
class Existing {}
$depth = 0;
spl_autoload_register(function($name) use (&$depth) {
    echo $name, ++$depth, ':';
    if ($depth < 3) spl_autoload_call($name);
});
spl_autoload_call('Existing');
