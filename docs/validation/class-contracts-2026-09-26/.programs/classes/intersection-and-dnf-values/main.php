<?php
interface A {} interface B {} class Both implements A, B {} class Other {}
function both(A&B $value): A&B { return $value; }
function either((A&B)|Other $value): (A&B)|Other { return $value; }
echo get_class(both(new Both)), ':', get_class(either(new Other)), ':';
try { both(new Other); } catch (TypeError $error) { echo 'type'; }
