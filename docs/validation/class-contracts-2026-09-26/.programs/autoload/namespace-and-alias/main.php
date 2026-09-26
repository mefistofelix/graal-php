<?php
namespace App;
use Vendor\Package as Alias;
spl_autoload_register(function($name) {
    echo $name, ':';
    eval('namespace Vendor; class Package {}');
});
echo class_exists('Alias', false) === false, ':';
$object = new Alias; echo get_class($object), ':', class_exists('\Vendor\Package');
