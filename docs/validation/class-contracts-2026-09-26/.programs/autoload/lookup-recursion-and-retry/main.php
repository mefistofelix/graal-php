<?php
spl_autoload_register(function($name) { echo $name, ':', class_exists('missing') === false, ';'; });
class_exists('Missing'); class_exists('Missing');
