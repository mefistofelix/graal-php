<?php
spl_autoload_register(function($name){echo 'load:';eval('enum E:string{case A="a";}');});
echo enum_exists('E'),':',class_exists('E',false),':',E::from('a')->name;
