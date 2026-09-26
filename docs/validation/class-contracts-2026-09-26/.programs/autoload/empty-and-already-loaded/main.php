<?php
class Existing {}
echo count(spl_autoload_functions()), ':', class_exists('Existing', false), ':', class_exists('eXiStInG'), ':';
echo class_exists('Closure'), ':', class_exists('Exception'), ':', class_exists('Missing', false) === false;
