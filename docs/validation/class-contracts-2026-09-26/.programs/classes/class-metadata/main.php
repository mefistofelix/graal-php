<?php
interface A {} interface B extends A {} trait RootTrait {} trait T {use RootTrait; function value(){}}
class Base implements A {} class C extends Base implements B {use T {value as alias;}}
foreach(class_parents('C') as $key=>$value) echo $key,'=',$value,';'; echo ':';
foreach(class_implements(new C) as $key=>$value) echo $key,'=',$value,';'; echo ':';
foreach(class_uses('C') as $key=>$value) echo $key,'=',$value,';';
echo ':',method_exists('C','alias'), ':', method_exists('C','value');
