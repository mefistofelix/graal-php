<?php
spl_autoload_register(function($name){echo $name,':'; if($name==='I')eval('interface I{}'); else eval('class Wrong{}');});
echo interface_exists('I'), ':', interface_exists('Wrong') === false, ':', class_exists('Wrong',false);
