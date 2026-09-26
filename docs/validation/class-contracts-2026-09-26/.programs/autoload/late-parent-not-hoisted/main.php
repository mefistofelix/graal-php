<?php
echo class_exists('Child', false) === false, ':';
try { new Child; } catch (Error $error) { echo 'missing:'; }
class Child extends Base {} class Base {}
echo class_exists('Child', false), ':', get_class(new Child);
