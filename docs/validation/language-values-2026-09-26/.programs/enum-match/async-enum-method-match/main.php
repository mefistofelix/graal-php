<?php
enum E:int {case A=1;case B=2;function label():string{Async\delay(1);return match($this){self::A=>'A',self::B=>'B'};}}
echo E::from(2)->label();
