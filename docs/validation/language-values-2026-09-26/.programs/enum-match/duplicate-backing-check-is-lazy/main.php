<?php
enum E:string {case A='x';case B='x';const OTHER=3;}
echo 'declared:',enum_exists('E'),':';
foreach(E::cases() as $case)echo $case->name,':',$case->value,';';
try{echo E::A->name;}catch(Error $error){echo 'duplicate:';}
try{echo E::OTHER;}catch(Error $error){echo 'constant:';}
try{E::tryFrom('x');}catch(Error $error){echo 'lookup';}
