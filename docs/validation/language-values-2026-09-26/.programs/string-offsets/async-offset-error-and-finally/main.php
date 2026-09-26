<?php
$s='abc';function value(){Async\delay(1);return '';}
try{$s[0]=value();}catch(Error $e){echo 'error:';}finally{echo $s;}
