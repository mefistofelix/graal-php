<?php
spl_autoload_register(function($name) {
    Async\delay(1);
    eval('class Loaded { public $n; public function __construct($n) { Async\delay(1); $this->n = $n; } }');
});
echo (new Loaded(42))->n;
