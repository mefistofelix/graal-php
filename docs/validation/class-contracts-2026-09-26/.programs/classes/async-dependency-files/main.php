<?php
spl_autoload_register(function($name){Async\delay(1); require __DIR__.'/'.$name.'.php';});
echo (new C)->value(), ':', (new C) instanceof I;
