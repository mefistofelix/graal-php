<?php

use Async\Future;
use Async\AsyncCancellation;
use function Async\spawn;
use function Async\await;
use function Async\await_all;
use function Async\await_any_or_fail;
use function Async\protect;
use function Async\delay;

$slow = spawn(function() { delay(20); return ['name' => 'slow']; });
$fast = spawn(fn() => ['name' => 'fast']);
echo 'Winner: ', await_any_or_fail([$slow, $fast])['name'], "\n";

$batch = await_all([
    'slow' => $slow,
    'fast' => $fast,
    'failure' => Future::failed(new Exception('Unavailable'))
], null, true, true);
foreach ($batch[0] as $key => $value) {
    echo $key, ': ', $value['name'] ?? 'no result', "\n";
}
echo 'Error: ', $batch[1]['failure']->getMessage(), "\n";

$task = spawn(function() {
    try {
        protect(function() {
            echo "Protected operation started\n";
            delay(20);
            echo "Protected operation completed\n";
        });
    } catch (AsyncCancellation $error) {
        echo 'Cancellation: ', $error->getMessage(), "\n";
    } finally {
        echo "Resources released\n";
    }
});
delay(2);
$task->cancel(new AsyncCancellation('Requested by caller'));
await($task);
