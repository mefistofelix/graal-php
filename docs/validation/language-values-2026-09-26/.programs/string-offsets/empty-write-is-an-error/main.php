<?php
$s='abc';try{$s[0]='';}catch(Error $e){echo 'empty:';}echo $s;
