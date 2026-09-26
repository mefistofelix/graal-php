<?php
enum E:int{case A=1;}
$task=Async\spawn(function(){Async\delay(1);return E::from(1);});
echo Async\await($task)===E::A;
