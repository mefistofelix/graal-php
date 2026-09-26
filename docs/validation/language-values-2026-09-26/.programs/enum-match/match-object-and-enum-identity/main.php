<?php
class C{public $n=1;}$a=new C;$b=new C;
enum E{case A;case B;}
echo match($a){$b=>'wrong',$a=>'same'},':',match(E::B){E::A=>'wrong',E::B=>'B'};
