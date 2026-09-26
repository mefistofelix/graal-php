<?php
enum E:string{case A='abc';}
try{E::A->name[0]='X';}catch(Error $e){echo 'name:';}
try{E::A->value[0]='X';}catch(Error $e){echo 'value:';}
echo E::A->name,':',E::A->value;
