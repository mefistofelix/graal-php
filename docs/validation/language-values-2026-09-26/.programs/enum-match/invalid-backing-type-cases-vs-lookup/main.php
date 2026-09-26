<?php
enum E:string {case A=1;case B='b';const OTHER=3;}
echo 'declared:';foreach(E::cases()as$case)echo $case->name,':',$case->value,';';
try{echo E::A->name;}catch(TypeError $error){echo 'case:';}
try{echo E::OTHER;}catch(TypeError $error){echo 'constant';}
