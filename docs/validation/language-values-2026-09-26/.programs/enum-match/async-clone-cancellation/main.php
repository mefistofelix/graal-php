<?php
$entered=new Async\Channel(1);$release=new Async\Channel(1);
class C{function __clone(){global $entered,$release;try{$entered->send(true);$release->recv();}finally{echo 'clone-finally:';}}}
$original=new C;$task=Async\spawn(function()use($original){return clone $original;});
$entered->recv();$task->cancel();try{Async\await($task);}catch(Throwable $error){echo 'cancel:';}
echo $original instanceof C;
