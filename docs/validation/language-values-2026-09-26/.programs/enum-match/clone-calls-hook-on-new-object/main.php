<?php
class C{public $n=1;public function __clone(){$this->n++;}}
$a=new C;$b=clone $a;echo $a->n,':',$b->n;
