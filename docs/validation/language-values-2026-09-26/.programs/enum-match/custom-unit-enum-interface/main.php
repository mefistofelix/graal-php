<?php
interface Kind extends UnitEnum {function label():string;}
enum E implements Kind {case A;function label():string{return $this->name;}}
echo E::A->label(),':',E::A instanceof Kind;
foreach(class_implements('E') as $name)echo ':',$name;
