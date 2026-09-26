<?php
spl_autoload_register(function($name){Async\delay(1); eval('interface I {}');});
echo interface_exists('I'), ':', class_exists('I') === false;
