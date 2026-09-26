<?php
function argument() { echo 'arg:'; return 8; }
spl_autoload_register(function($name) {
    echo 'load:';
    eval('class ' . $name . ' { public $n; public function __construct($n) { $this->n = $n; } }');
});
$name = 'Loaded'; $object = new $name(argument());
echo $object->n, ':', get_class(new ('Loaded')(9));
