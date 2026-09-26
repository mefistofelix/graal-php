<?php
class Loader {
    public function register() { spl_autoload_register([$this, 'load']); }
    private function load($name) { echo $name; eval('class ' . $name . ' {}'); }
}
$loader = new Loader; $loader->register(); unset($loader);
echo ':', class_exists('Loaded');
