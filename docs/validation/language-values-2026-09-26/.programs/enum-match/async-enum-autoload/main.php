<?php
spl_autoload_register(function($name){Async\delay(1);require __DIR__.'/E.php';});
echo E::from('a')->name,':',enum_exists('E');
