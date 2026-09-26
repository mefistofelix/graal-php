<?php
enum E {case A;case B;}
$cases=E::cases();$copy=$cases;$cases[0]=E::B;$cases[]=E::A;
echo count($cases),':',count(E::cases()),':',$copy[0]===E::A,':',E::cases()[0]===E::A;
