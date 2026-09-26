<?php
trait T {function names(){return self::class.':'.parent::class.':'.static::class;}}
class Base {} class C extends Base {use T;} class D extends C {}
echo (new D)->names();
