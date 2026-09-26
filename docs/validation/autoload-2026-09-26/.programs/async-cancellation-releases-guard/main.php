<?php
$entered = new Async\Channel(1); $calls = 0;
spl_autoload_register(function($name) use ($entered, &$calls) {
    if (++$calls === 1) {
        try { $entered->send(true); Async\delay(10000); } finally { echo 'finally:'; }
    }
    eval('class Loaded {}');
});
$task = Async\spawn(function() { return class_exists('Loaded'); });
$entered->recv(); $task->cancel();
try { Async\await($task); } catch (Throwable $error) { echo 'cancel:'; }
echo class_exists('Loaded'), ':', $calls;
