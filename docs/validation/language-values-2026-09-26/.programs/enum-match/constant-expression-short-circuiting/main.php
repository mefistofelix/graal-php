<?php
class C{public const A=false&&Missing::VALUE;public const B=true||Missing::VALUE;public const EMPTY=![];}
enum E:int{case A=(null??3)+(true?4:Missing::VALUE);}
echo C::A===false,':',C::B,':',C::EMPTY,':',E::A->value;
