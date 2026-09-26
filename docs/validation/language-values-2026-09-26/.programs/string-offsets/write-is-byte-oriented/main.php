<?php
$s=hex2bin('c3a9ff');$s[0]='A';$s[-1]=hex2bin('80');
echo bin2hex($s),':',bin2hex($s[1]);
