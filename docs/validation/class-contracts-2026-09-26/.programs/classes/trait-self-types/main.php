<?php
trait T {function same(self $value): self {return $value;} function create(): self {return new self;}}
class C {use T;} class D {use T;}
echo get_class((new C)->same(new C)), ':', get_class((new D)->create());
try {(new C)->same(new D);}catch(TypeError $error){echo ':type';}
