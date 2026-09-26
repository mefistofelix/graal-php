<?php
trait T {function label(){return __CLASS__.':'.__TRAIT__.':'.$this->name;}}
enum E {use T{label as alias;}case A;}
echo E::A->alias();
