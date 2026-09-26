<?php
class Loader { public static function Load($name) { echo $name; } }
spl_autoload_register('Loader::LoAd'); spl_autoload_register(['loader', 'load'], prepend: true);
$list = spl_autoload_functions(); echo count($list), ':', $list[0][0], ':', $list[0][1], ':';
spl_autoload_call('Missing'); echo ':', spl_autoload_unregister(['Loader', 'LOAD']);
