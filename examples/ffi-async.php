<?php
$ffi = FFI::cdef('
    typedef int64_t (*transform)(int64_t value);
    int64_t gp_callback(transform function, int64_t value);
    int64_t gp_sleep_echo(unsigned int milliseconds, int64_t value);
', 'build/graalphp-native.dll');

$mutex = new Async\Mutex;
$results = [];
$tasks = [];
for ($i = 1; $i <= 4; $i++) {
    $tasks[] = Async\spawn(function() use ($ffi, $mutex, &$results, $i) {
        $value = $ffi->gp_sleep_echo(10, $i, async: true, pool: 'blocking');
        $mutex->synchronized(function() use (&$results, $value) {
            $results[] = $value;
        });
    });
}
Async\await_all_or_fail($tasks);
echo 'completed=', count($results), "\n";
echo 'callback=', $ffi->gp_callback(fn($n) => $n * 2, 21, async: true, pool: 'callbacks'), "\n";
