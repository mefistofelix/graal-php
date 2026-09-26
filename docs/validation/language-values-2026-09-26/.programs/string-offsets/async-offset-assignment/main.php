<?php
$s='abc';function value(){Async\delay(1);return 'Z';}
$s[1]=value();echo $s;
