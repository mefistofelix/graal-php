<?php
class C{public $n=1;function __clone(){Async\delay(1);$this->n=9;}}
$a=new C;$b=clone $a;echo $a->n,':',$b->n;
