<?php
$s=hex2bin('c3a900ff');
for($i=0;$i<4;$i++)echo bin2hex($s[$i]),':';
echo bin2hex(hex2bin('ff00')[-2]);
