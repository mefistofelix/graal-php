<?php
$value=7;echo $value??throw new Exception('wrong');
$value=null;try{$result=$value??throw new Exception('missing');}catch(Exception $error){echo ':',$error->getMessage();}
