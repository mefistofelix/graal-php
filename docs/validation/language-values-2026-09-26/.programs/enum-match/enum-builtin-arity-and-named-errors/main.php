<?php
enum E:int {case A=1;}
try{E::from();}catch(ArgumentCountError $error){echo 'missing:';}
try{E::from(1,2);}catch(ArgumentCountError $error){echo 'extra:';}
try{E::cases(1);}catch(ArgumentCountError $error){echo 'cases:';}
try{E::tryFrom(unknown:1);}catch(Error $error){echo 'named:';}
echo E::from(value:1)===E::A;
