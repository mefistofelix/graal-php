<?php
function condition($value){echo 'c'.$value.':';return $value;}
function result($value){echo 'r'.$value.':';return $value;}
echo match(2){default=>result('default'),condition(1)=>result('one'),condition(2),condition(3)=>result('two'),condition(4)=>result('four')};
