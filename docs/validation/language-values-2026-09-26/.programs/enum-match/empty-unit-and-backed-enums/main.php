<?php
enum EmptyUnit {} enum EmptyBacked:int {}
echo count(EmptyUnit::cases()),':',count(EmptyBacked::cases()),':',EmptyBacked::tryFrom(0)===null;
try{EmptyBacked::from(0);}catch(ValueError $error){echo ':missing';}
