<?php
$s='abc';$ref=&$s;$ref[-1]='Z';echo $s,':',$ref;
