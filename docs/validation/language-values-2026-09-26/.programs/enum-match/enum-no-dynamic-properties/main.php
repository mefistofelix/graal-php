<?php
enum E {case A;}
$value=E::A;
try{$value->other=1;}catch(Error $error){echo 'dynamic:';}
try{$value->other[]=1;}catch(Error $error){echo 'array:';}
echo isset($value->other)===false;
