<?php
enum E:int {case A=1;}
foreach([1,'1',1.0,true,false] as $value){$result=E::tryFrom($value);echo $result===null?'none':$result->name;echo ';';}
foreach([[],new stdClass] as $value){try{E::from($value);}catch(TypeError $error){echo 'type;';}}
