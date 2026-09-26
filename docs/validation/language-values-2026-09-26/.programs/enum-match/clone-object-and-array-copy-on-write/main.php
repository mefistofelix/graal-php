<?php
class C{public $values=[1];public $n=2;}
$a=new C;$b=clone $a;$b->values[0]=9;$b->n=7;
echo $a!==$b,':',$a->values[0],':',$b->values[0],':',$a->n,':',$b->n;
