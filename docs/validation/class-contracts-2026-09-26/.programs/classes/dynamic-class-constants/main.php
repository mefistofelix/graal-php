<?php
class C {const VALUE=6;}
$name='C'; $object=new C;
echo $name::VALUE, ':', $object::VALUE, ':', $object::class;
try {echo $name::class;}catch(TypeError $error){echo ':string-type';}
