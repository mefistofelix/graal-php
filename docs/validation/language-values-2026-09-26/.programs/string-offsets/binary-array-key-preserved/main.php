<?php
$key=hex2bin('ff00');$a=[$key=>3];$b=$a;$b[$key]=9;
echo $a[$key],':',$b[$key],':',count($a);
foreach($a as $k=>$v)echo ':',bin2hex($k),':',$v;
