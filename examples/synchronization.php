<?php
$mutex = new Async\Mutex;
$channel = new Async\ThreadChannel(1);
$total = 0;

$producer = Async\spawn(function() use ($channel) {
    for ($i = 1; $i <= 4; $i++) $channel->send($i);
    $channel->close();
});
$consumer = Async\spawn(function() use ($mutex, $channel, &$total) {
    try {
        while (true) {
            $value = $channel->recv();
            $mutex->lock();
            try { $total += $value; } finally { $mutex->unlock(); }
        }
    } catch (Async\ThreadChannelException $closed) {}
});
Async\await_all_or_fail([$producer, $consumer]);
echo 'total=', $total, "\n";
