<?php

function payload(int $sequence, int $width): array {
    $result = [];
    for ($index = 0; $index < $width; ++$index) {
        $result[] = $sequence + $index;
    }
    return $result;
}

$iterations = intval(getenv('FUTURE_ITERATIONS'));
$width = intval(getenv('FUTURE_PAYLOAD'));
$mode = getenv('FUTURE_MODE');
if ($iterations < 1 || $width < 1) {
    throw new ValueError('FUTURE_ITERATIONS and FUTURE_PAYLOAD must be positive');
}

$channel = new Async\Channel(1);
$checksum = 0;
for ($sequence = 0; $sequence < $iterations; ++$sequence) {
    if ($mode === 'channel') {
        $channel->send(payload($sequence, $width));
        $future = $channel->recvAsync();
    } elseif ($mode === 'completed') {
        $future = Async\Future::completed(payload($sequence, $width));
    } elseif ($mode === 'spawn') {
        $future = Async\spawn(fn() => payload($sequence, $width));
    } else {
        throw new ValueError('Expected FUTURE_MODE=channel, completed or spawn');
    }
    $result = Async\await($future);
    unset($future);
    $checksum += $result[0] + $result[$width - 1];
    unset($result);
}
echo $checksum, "\n";
