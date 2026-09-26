<?php
interface Kind extends BackedEnum {function label():string;}
enum E:int implements Kind {case A=7;function label():string{return $this->name;}}
echo E::from(7)->label(),':',E::A instanceof Kind;
