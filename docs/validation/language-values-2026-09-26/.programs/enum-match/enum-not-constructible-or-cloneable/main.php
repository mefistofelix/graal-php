<?php
enum E {case A;}
try{new E;}catch(Error $error){echo 'new:';}
$name='E';try{new $name;}catch(Error $error){echo 'dynamic:';}
try{$copy=clone E::A;}catch(Error $error){echo 'clone:';}
echo E::A===E::cases()[0];
