<?php
$entered = new Async\Channel(1); $release = new Async\Channel(1);
spl_autoload_register(function($name) use ($entered, $release) {
    $entered->send(true); $release->recv(); eval('class Loaded {}');
});
$task = Async\spawn(function() { return class_exists('Loaded'); });
$entered->recv(); echo class_exists('Loaded') === false, ':';
$release->send(true); echo Async\await($task), ':', class_exists('Loaded', false);
