<?php
enum E:int{case A=1;static function tryFrom($n):?self{return self::A;}}