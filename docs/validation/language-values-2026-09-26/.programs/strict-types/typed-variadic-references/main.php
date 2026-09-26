<?php
function f(int &...$ns){echo $ns[0]===2,':';$ns[1]=4;}
$a='2';$b='3';f($a,$b);echo $a===2,':',$b;
