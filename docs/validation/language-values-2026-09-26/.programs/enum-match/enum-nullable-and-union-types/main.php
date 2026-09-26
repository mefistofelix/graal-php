<?php
enum E{case A;}enum F{case A;}
function selected(E|F $value):?E{return $value instanceof E?$value:null;}
echo selected(E::A)===E::A,':',selected(F::A)===null;
try{selected(1);}catch(TypeError $error){echo ':type';}
