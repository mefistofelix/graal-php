<?php
enum E:int{case A=1;static function from($n):self{return self::A;}}