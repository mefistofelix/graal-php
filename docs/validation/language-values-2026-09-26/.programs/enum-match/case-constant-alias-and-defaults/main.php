<?php
enum E {case A;case B;const Alias=self::A;function same(self $value=self::A):self{return $value;}}
function selected(E $value=E::A):E{return $value;}
class C {public E $value=E::A;}
echo E::Alias===E::A,':',count(E::cases()),':',selected()===E::A,':',(new C)->value===E::A,':',E::B->same()===E::A;
