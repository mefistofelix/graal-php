<?php
enum E:int {case A=1;}
$value=E::A;
try{unset($value->name);}catch(Error $error){echo 'name:';}
try{unset($value->value);}catch(Error $error){echo 'value:';}
unset($value->missing);echo isset($value->missing)?'wrong':'absent';
