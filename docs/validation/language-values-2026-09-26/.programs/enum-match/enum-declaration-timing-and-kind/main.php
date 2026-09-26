<?php
echo enum_exists('E',false) === false, ':';
enum E {case A;case B;}
echo enum_exists('e'), ':', class_exists('E'), ':', interface_exists('E') === false, ':', trait_exists('E') === false;
echo ':', interface_exists('UnitEnum'), ':', interface_exists('BackedEnum'), ':', class_exists('UnitEnum') === false;
