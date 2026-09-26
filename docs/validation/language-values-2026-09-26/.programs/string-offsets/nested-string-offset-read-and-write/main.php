<?php
$s='abc';echo $s[0][0],':';
try{$s[0][0]='Z';}catch(Error $e){echo 'nested:';}echo $s;
