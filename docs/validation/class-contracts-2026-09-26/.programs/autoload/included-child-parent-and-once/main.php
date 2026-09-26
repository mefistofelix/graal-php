<?php
spl_autoload_register(function($name) { echo $name, ':'; require_once __DIR__ . '/' . $name . '.php'; });
echo (new Child)->value(), ':', class_exists('ParentClass', false);
