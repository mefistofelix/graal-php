<?php
$s='abc';try{$s[]='X';}catch(Error $e){echo 'append:';}
try{$ref=&$s[0];}catch(Error $e){echo 'reference:';}
$x='X';try{$s[0]=&$x;}catch(Error $e){echo 'bind:';}
try{unset($s[0]);}catch(Error $e){echo 'unset:';}echo $s;
