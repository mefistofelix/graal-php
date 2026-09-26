<?php
enum E:int {case A=1;}
echo E::from('1.5')->name;
