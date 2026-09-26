<?php
enum E:int{case A=2;}
class C{public const N=E::A->name;public const V=E::A->value;}
enum F:int{case B=C::V+1;}
echo C::N,':',C::V,':',F::B->value;
