<?php
enum E:int {case A=1;}
$value=E::A;
try{$ref=&$value->name;}catch(Error $error){echo 'name:';}
try{$ref=&$value->value;}catch(Error $error){echo 'value:';}
$x=4;try{$value->value=&$x;}catch(Error $error){echo 'bind';}
