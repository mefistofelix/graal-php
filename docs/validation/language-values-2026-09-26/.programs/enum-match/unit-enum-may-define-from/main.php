<?php
enum E {case A;public static function from(string $value):self{return self::A;}}
echo E::from('a')===E::A;
