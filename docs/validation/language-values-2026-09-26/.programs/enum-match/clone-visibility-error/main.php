<?php
class C{private function __clone(){}}
try{$copy=clone new C;}catch(Error $error){echo 'private:';}
try{$copy=clone 1;}catch(Error $error){echo 'scalar';}
