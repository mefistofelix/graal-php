<?php
class Base {public const VALUE=4; private const SECRET=7; public function secret(){return self::SECRET;}}
class Child extends Base {public const VALUE=9; public function values(){return parent::VALUE+self::VALUE;}}
echo Child::VALUE, ':', (new Child)->values(), ':', (new Child)->secret();
try {echo Base::SECRET;}catch(Error $error){echo ':private';}
