<?php
enum E {case A;case B;}
$values=[E::A];$copy=$values;$ref=&$values[0];$ref=E::B;
echo $values[0]===E::B,':',$copy[0]===E::A,':',[E::A] === [E::A],':',[E::A] !== [E::B];
