<?php
echo interface_exists('I', false), trait_exists('T', false), class_exists('C', false) ? 'C' : '-';
interface I {} trait T {} class C implements I { use T; }
echo ':', interface_exists('i'), ':', trait_exists('t'), ':', class_exists('C'), ':';
echo class_exists('I') === false, ':', class_exists('T') === false, ':', interface_exists('C') === false;
