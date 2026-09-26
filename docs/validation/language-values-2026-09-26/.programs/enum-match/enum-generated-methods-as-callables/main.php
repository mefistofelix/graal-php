<?php
enum E:int {case A=1;}
$cases=['E','cases'];$from='E::from';$try=['E','tryFrom'];
echo $cases()[0]===E::A,':',$from(1)===E::A,':',$try(2)===null;
