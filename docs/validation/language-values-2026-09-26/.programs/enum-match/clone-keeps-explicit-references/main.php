<?php
class C{public $value=1;}
$a=new C;$ref=&$a->value;$b=clone $a;$b->value=9;
echo $a->value,':',$b->value,':',$ref;
