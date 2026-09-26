<?php
interface I {const int VALUE=7;}
class C implements I {const array ITEMS=[1,2]; public $value=self::VALUE;}
$items=C::ITEMS; $items[0]=9;
echo C::VALUE, ':', (new C)->value, ':', C::ITEMS[0], ':', $items[0];
