<?php
try {eval('interface I{function f(int $n);} class C implements I{function f(string $n){}}');}
catch(Throwable $error){echo 'UNEXPECTED_CATCH';}
finally {echo 'UNEXPECTED_FINALLY';}
echo 'UNEXPECTED_AFTER';
