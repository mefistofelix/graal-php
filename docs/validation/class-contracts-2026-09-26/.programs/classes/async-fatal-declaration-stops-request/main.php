<?php
$task=Async\spawn(function(){
    try {eval('interface I{function f(int $n);} class C implements I{function f(string $n){}}');}
    catch(Throwable $error){echo 'UNEXPECTED_CATCH';}
    finally {echo 'UNEXPECTED_FINALLY';}
});
try {Async\await($task);}catch(Throwable $error){echo 'UNEXPECTED_AWAIT_CATCH';}
echo 'UNEXPECTED_AFTER';
