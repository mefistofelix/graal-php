<?php
enum E:string {case A='a';}
$name='E';echo $name::from('a')===E::A,':',count($name::cases()),':',$name::A->name;
$case=E::A;echo ':',$case::tryFrom('a')===E::A;
