<?php
class Base {const VALUE=2;}
enum E:int {case A=Base::VALUE;case B=self::NEXT;const NEXT=3;}
echo E::A->value,':',E::B->value,':',E::from(3)===E::B;
