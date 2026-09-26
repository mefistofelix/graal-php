<?php
class Base { function value(){return 'parent';} }
trait T { function value(){return 'trait';} function parentValue(){return parent::value();} }
class C extends Base { use T; }
class D extends Base { use T; function value(){return 'class';} }
echo (new C)->value(), ':', (new D)->value(), ':', (new C)->parentValue();
