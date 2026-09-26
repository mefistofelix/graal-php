<?php
enum E:string {case One='1';case Padded='01';case Zero='0';case Empty='';}
foreach(['1','01','0',''] as $value)echo E::from($value)->name,':';
echo E::from(1)===E::One;
