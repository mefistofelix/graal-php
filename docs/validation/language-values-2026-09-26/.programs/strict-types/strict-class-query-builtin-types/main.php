<?php
declare(strict_types=1);
try{class_exists(1);}catch(TypeError$e){echo 'name:';}
try{class_exists('Missing',1);}catch(TypeError$e){echo 'flag:';}
try{error_reporting('0');}catch(TypeError$e){echo 'reporting';}
