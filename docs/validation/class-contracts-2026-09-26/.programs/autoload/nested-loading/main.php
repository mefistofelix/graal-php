<?php
spl_autoload_register(function($name) {
    echo $name, ':';
    if ($name === 'OuterClass') new InnerClass;
    eval('class ' . $name . ' {}');
});
new OuterClass; echo class_exists('InnerClass', false);
