<?php
interface I {}
enum E:string implements I {case A='a';}
foreach(class_implements('E') as $name)echo $name,':';
echo count(class_parents(E::A)),':',is_a('E','UnitEnum',true),':',is_subclass_of(E::A,'BackedEnum');
