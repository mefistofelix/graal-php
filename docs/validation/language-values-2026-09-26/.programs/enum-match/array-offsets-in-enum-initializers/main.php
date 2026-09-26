<?php
class C{public const VALUES=['a'=>4];}
enum E:int{case A=C::VALUES['a'];}
echo E::A->value,':',E::from(4)->name;
