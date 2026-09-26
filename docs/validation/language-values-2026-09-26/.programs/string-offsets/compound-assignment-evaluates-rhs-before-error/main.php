<?php
$s='abc';function value(){echo 'rhs:';return 'X';}
try{$s[0].=value();}catch(Error $e){echo 'compound:';}
try{$s[[]]+=value();}catch(Error $e){echo 'invalid:';}
try{$s[0]++;}catch(Error $e){echo 'increment:';}echo $s;
