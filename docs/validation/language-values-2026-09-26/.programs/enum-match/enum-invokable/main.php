<?php
enum E {case A;function __invoke(int $n):string{return $this->name.$n;}}
$value=E::A;echo $value(7);
