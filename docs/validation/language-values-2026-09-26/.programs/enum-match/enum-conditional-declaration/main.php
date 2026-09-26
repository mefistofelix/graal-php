<?php
if(false){enum Absent{case A;}}
function defineEnum(){enum E{case A;}}
echo enum_exists('Absent',false)===false,':',enum_exists('E',false)===false,':';
defineEnum();echo E::A->name;
