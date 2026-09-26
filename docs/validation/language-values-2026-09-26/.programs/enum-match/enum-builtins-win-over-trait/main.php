<?php
trait T {function cases(){return 9;} function from($value){return 8;}}
enum E:int {use T;case A=1;}
echo count(E::cases()),':',E::from(1)===E::A;
