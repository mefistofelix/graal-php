<?php
$s='abc';foreach([0,3,'x',null,1.8]as$i)echo ($s[$i]??'missing'),':';
try{$value=$s[[]]??'missing';}catch(TypeError $e){echo 'type';}
