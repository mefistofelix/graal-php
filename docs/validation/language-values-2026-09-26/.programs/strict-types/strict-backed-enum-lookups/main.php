<?php
declare(strict_types=1);enum I:int{case A=1;}enum S:string{case A='1';}
foreach(['1',1.0,true,null]as$v){try{I::from($v);}catch(TypeError$e){echo 'int:';}}
foreach([1,1.0,true,null]as$v){try{S::tryFrom($v);}catch(TypeError$e){echo 'string:';}}
echo I::from(1)->name,':',S::from('1')->name;
