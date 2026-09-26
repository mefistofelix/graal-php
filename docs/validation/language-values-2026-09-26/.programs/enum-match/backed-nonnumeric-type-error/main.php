<?php
enum E:int {case A=1;}
try{E::tryFrom('abc');}catch(TypeError $error){echo 'type';}
