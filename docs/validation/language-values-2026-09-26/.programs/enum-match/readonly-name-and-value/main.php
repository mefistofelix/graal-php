<?php
enum E:string {case A='a';}
$value=E::A;
try{$value->name='other';}catch(Error $error){echo 'name:';}
try{$value->value='other';}catch(Error $error){echo 'value:';}
echo E::A->name,':',E::A->value;
