<?php
$entered=new Async\Channel(1); $release=new Async\Channel(1); $calls=0;
spl_autoload_register(function($name)use($entered,$release,&$calls){
    if($name==='C') {eval('class C implements I {use T;}'); return;}
    if($name==='T') {
        if(++$calls===1) {try {$entered->send(true); $release->recv();}finally{echo 'finally:';}}
        eval('trait T {function value():int{return 7;}}'); return;
    }
    eval('interface I {function value():int;}');
});
$task=Async\spawn(function(){return class_exists('C');});
$entered->recv(); echo class_exists('C',false) === false, ':';
$task->cancel(); try {Async\await($task);}catch(Throwable $error){echo 'cancel:';}
echo (new C)->value(), ':', $calls;
