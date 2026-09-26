<?php
spl_autoload_register(function($name) { Async\delay(1); require __DIR__ . '/' . $name . '.php'; });
echo (new Child)->value();
