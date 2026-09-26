<?php
namespace Lib; interface Contract{function value():int;} trait Body{function value():int{return 7;}}
namespace App; use Lib\Contract as I; use Lib\Body as T;
class C implements I {use T;}
echo (new C)->value(), ':', (new C) instanceof I;
