<?php
use Async\Scope;
use Async\Channel;
use Async\Future;
use function Async\await;
use function Async\delay;
use function Async\timeout;
use function Async\current_context;

current_context()->set('request', 'demo');
$scope = Scope::inherit();
$channel = new Channel(1);
$producer = $scope->spawn(function() use ($channel) {
    for ($i = 1; $i <= 3; $i++) {
        delay(1);
        $channel->send($i);
    }
    $channel->close();
    return current_context()->get('request');
});

$total = 0;
for ($i = 0; $i < 3; $i++) $total += $channel->recv(timeout(1000));
$scope->awaitCompletion(timeout(1000));
echo await($producer), ':', $total, "\n";

$future = Future::completed($total)->map(fn($value) => $value * 7);
echo $future->await(), "\n";
