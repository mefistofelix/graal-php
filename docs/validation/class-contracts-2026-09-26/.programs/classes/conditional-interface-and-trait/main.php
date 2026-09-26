<?php
if(false){interface Absent{} trait Unused{}}
function declareTypes(){interface I{} trait T{function value(){return 5;}}}
echo interface_exists('I',false) === false, ':', trait_exists('T',false) === false, ':';
declareTypes(); class C implements I {use T;} echo (new C)->value();
