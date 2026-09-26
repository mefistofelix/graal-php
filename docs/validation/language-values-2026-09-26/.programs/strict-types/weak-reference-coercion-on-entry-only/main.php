<?php
function f(int &$n){echo $n===2,':';$n='changed';}
$n='2';f($n);echo $n;
