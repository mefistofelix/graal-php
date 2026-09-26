<?php
$secret = 'global';
spl_autoload_register(function($name) { $secret = 'local'; require __DIR__ . '/loaded.php'; });
$object = new Loaded; echo ':', $secret, ':', $object->n;
