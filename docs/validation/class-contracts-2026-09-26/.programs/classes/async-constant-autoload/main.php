<?php
spl_autoload_register(function($name){Async\delay(1); eval('class C {const VALUE=8;}');});
echo C::VALUE;
