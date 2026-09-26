<?php
$entered=new Async\Channel(1);$release=new Async\Channel(1);
$task=Async\spawn(function()use($entered,$release){
    $wait=function()use($entered,$release){$entered->send(true);return $release->recv();};
    $subject=[1];try{return match($subject){$wait()=>'unused',default=>'none'};}
    finally{echo 'finally:';}
});
$entered->recv();$task->cancel();try{Async\await($task);}catch(Throwable $error){echo 'cancel';}
