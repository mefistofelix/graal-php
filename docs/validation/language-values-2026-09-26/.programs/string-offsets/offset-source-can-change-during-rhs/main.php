<?php
$s='abc';function index(){echo 'key:';return 0;}
function value(){global $s;$s=[1];echo 'value:';return 'Z';}
$s[index()]=value();echo $s[0];
