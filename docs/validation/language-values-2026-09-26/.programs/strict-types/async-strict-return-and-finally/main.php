<?php
declare(strict_types=1);function f():int{try{Async\delay(1);return '2';}finally{echo 'finally:';}}
try{f();}catch(TypeError$e){echo 'type';}
