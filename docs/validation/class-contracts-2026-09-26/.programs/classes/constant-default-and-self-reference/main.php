<?php
class C {const FIRST=3; const SECOND=self::FIRST+4; public $n=self::SECOND; function value($n=self::SECOND){return $n;}}
echo C::SECOND, ':', (new C)->n, ':', (new C)->value();
