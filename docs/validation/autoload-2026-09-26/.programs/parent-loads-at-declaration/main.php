<?php
spl_autoload_register(function($name) { echo 'load:', $name, ':'; eval('class ' . $name . ' { public $n = 7; }'); });
echo 'before:'; class Child extends MissingParent {} echo 'after:', (new Child)->n;
