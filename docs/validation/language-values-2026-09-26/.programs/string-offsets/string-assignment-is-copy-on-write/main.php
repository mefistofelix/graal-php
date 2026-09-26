<?php
$a='abc';$b=$a;$b[1]='X';echo $a,':',$b;
$a=['abc'];$b=$a;$b[0][1]='Y';echo ':',$a[0],':',$b[0];
