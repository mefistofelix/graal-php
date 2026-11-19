<?php
$ffi = FFI::cdef('
    typedef int64_t (*transform)(int64_t, const char*);
    int64_t gp_test_walk(transform callback, int64_t seed, int depth, const char *text);
    int gp_test_active(void); int gp_test_finished(void); int64_t gp_test_thread(void);
    double gp_test_mixed(double (*callback)(int8_t,uint8_t,int16_t,uint16_t,int32_t,uint32_t,int64_t,uint64_t,float,double,const char*,double), double value);
    float gp_test_float(float (*callback)(float), float value);
    int8_t gp_test_narrow(int8_t (*callback)(uint8_t), uint8_t value);
    void gp_test_void(void (*callback)(void));
    double gp_test_pair(int (*first)(int), double (*second)(double));
    int64_t gp_test_foreign(int64_t (*callback)(int64_t));', getenv('FFI_TEST_LIBRARY'));
$owner = $ffi->gp_test_thread();
function checkpoint() {
    if (getenv('FFI_TEST_GC') === '1') host_call('checkpoint');
}
function native_walk($ffi, $seed, $depth, $nested, $offload, $owner) {
    $task = Async\current_coroutine();
    $marker = [$seed, 'kept across suspension'];
    return $ffi->gp_test_walk(function($n, $text) use ($ffi, $seed, $nested, $owner, $task, $marker) {
        graal_assert($ffi->gp_test_thread() === $owner && Async\current_coroutine() === $task, 'FFI scenario at line 19');
        graal_assert($text === 'owned-string-' . $seed && $marker[0] === $seed, 'FFI scenario at line 20');
        checkpoint(); Async\delay(1);
        return $nested ? native_walk($ffi, $n, 8, false, false, $owner) : $n * 2;
    }, $seed, $depth, 'owned-string-' . $seed, async: $offload, pool: 'serial-callbacks');
}
FFI::definePool('serial-callbacks', min: 0, max: 1);
foreach ([false, true] as $offload) {
    $jobs = [];
    for ($i = 1; $i <= 8; $i++) $jobs[] = Async\spawn(function() use ($ffi, $i, $owner, $offload) {
        return native_walk($ffi, $i, 32, false, $offload, $owner);
    });
    $sum = 0; foreach ($jobs as $job) $sum += Async\await($job);
    graal_assert($sum === 520, 'FFI scenario at line 32');
    graal_assert(native_walk($ffi, 10, 32, true, $offload, $owner) === 272, 'FFI scenario at line 33');
    graal_assert($ffi->gp_test_active() === 0, 'FFI scenario at line 34');
}
graal_assert(FFI::poolSize('serial-callbacks') === 1, 'FFI scenario at line 36');
$mixed = $ffi->gp_test_mixed(function($a,$b,$c,$d,$e,$f,$g,$h,$i,$j,$s,$k) {
    graal_assert($a === -120 && $b === 250 && $c === -32000 && $d === 65000, 'FFI scenario at line 38');
    graal_assert($e === -2000000000 && $f === 4000000000 && $g === -7000000000000 && $h === 14000000000000, 'FFI scenario at line 39');
    graal_assert($i === 1.25 && $j === 2.5 && $s === 'C-local', 'FFI scenario at line 40');
    checkpoint(); Async\delay(1); return $i + $j + $k;
}, 4.75);
graal_assert($mixed === 8.5, 'FFI scenario at line 43');
graal_assert($ffi->gp_test_float(function($v) { Async\delay(1); return $v * 2; }, 1.25) === 3.0, 'FFI scenario at line 44');
graal_assert($ffi->gp_test_narrow(function($v) { Async\delay(1); return $v - 255; }, 128) === -127, 'FFI scenario at line 45');
$void = false;
$ffi->gp_test_void(function() use (&$void) { Async\delay(1); $void = true; });
graal_assert($void, 'FFI scenario at line 48');
graal_assert($ffi->gp_test_pair(function($n) { Async\delay(1); return $n * 2; },
    function($n) { Async\delay(1); return $n * 2; }, async: true) === 44.5, 'multiple callbacks');
try {
    $ffi->gp_test_walk(fn($n, $s) => [], 1, 32, 'invalid');
    graal_assert(false, 'invalid callback return accepted');
} catch (TypeError $e) { graal_assert($ffi->gp_test_active() === 0, 'invalid return drains C'); }
foreach ([false, true] as $offload) {
    $before = $ffi->gp_test_finished(); $calls = 0;
    try {
        $ffi->gp_test_walk(function($n, $s) use (&$calls) {
            $calls++; Async\delay(1); throw new Exception('callback-original');
        }, 1, 32, 'throw', async: $offload, pool: 'serial-callbacks');
        graal_assert(false, 'FFI scenario at line 55');
    } catch (Exception $e) { graal_assert($e->getMessage() === 'callback-original', 'FFI scenario at line 56'); }
    graal_assert($calls === 1 && $ffi->gp_test_active() === 0 && $ffi->gp_test_finished() === $before + 1, 'FFI scenario at line 57');
    $started = new Async\Channel(1); $cleaned = false; $calls = 0;
    $job = Async\spawn(function() use ($ffi, $started, &$cleaned, &$calls, $offload) {
        try {
            $ffi->gp_test_walk(function($n, $s) use ($started, &$calls) {
                $calls++; $started->send(1); Async\delay(10000); return 99;
            }, 1, 64, 'cancel', async: $offload, pool: 'serial-callbacks');
        } finally { $cleaned = true; }
    });
    $started->recv(); $job->cancel();
    try { Async\await($job); } catch (Async\AsyncCancellation $e) {}
    graal_assert($cleaned && $calls === 1 && $ffi->gp_test_active() === 0, 'FFI scenario at line 68');
    graal_assert(native_walk($ffi, 1, 0, false, $offload, $owner) === 12, 'FFI scenario at line 69');
}
try { $ffi->gp_test_foreign(fn($n) => $n); graal_assert(false, 'FFI scenario at line 71'); }
catch (FFI\Exception $e) { graal_assert($e->getMessage() === 'Call-scoped callbacks must run on the native invocation thread', 'FFI scenario at line 72'); }
$started = new Async\Channel(1); $release = new Async\Channel(1);
$held = Async\spawn(function() use ($ffi, $started, $release) {
    $first = true;
    return $ffi->gp_test_walk(function($n, $s) use ($started, $release, &$first) {
        if ($first) { $first = false; $started->send(1); $release->recv(); }
        return $n * 2;
    }, 1, 0, 'held', async: true, pool: 'serial-callbacks');
});
$started->recv(); $before = $ffi->gp_test_finished(); $entered = false;
$queued = Async\spawn(function() use ($ffi, &$entered) {
    $ffi->gp_test_walk(function($n, $s) use (&$entered) { $entered = true; return 0; },
        1, 0, 'queued', async: true, pool: 'serial-callbacks');
});
Async\delay(1); $queued->cancel(); $release->send(1);
graal_assert(Async\await($held) === 12, 'held invocation result');
try { Async\await($queued); } catch (Async\AsyncCancellation $e) {}
graal_assert(!$entered && $ffi->gp_test_active() === 0 && $ffi->gp_test_finished() === $before + 1, 'queued cancellation never enters C');
$scope = Async\Scope::inherit(); $started = new Async\Channel(1); $release = new Async\Channel(1); $completed = false;
$protected = $scope->spawn(function() use ($ffi, $started, $release, &$completed) {
    Async\protect(function() use ($ffi, $started, $release, &$completed) {
        $first = true;
        $value = $ffi->gp_test_walk(function($n, $s) use ($started, $release, &$first) {
            if ($first) { $first = false; $started->send(1); $release->recv(); }
            return $n * 2;
        }, 1, 0, 'protected', async: true, pool: 'serial-callbacks');
        $completed = $value === 12;
    });
});
$started->recv(); $scope->cancel(); $release->send(1);
try { Async\await($protected); } catch (Async\AsyncCancellation $e) {}
$scope->awaitAfterCancellation();
graal_assert($completed && $ffi->gp_test_active() === 0, 'scope cancellation respects protected native call');
$sqlite = FFI::cdef('int gp_sqlite_scalar(const char *sql, int64_t (*callback)(int64_t));', 'builtin:sqlite3');
$scalar = 0;
graal_assert($sqlite->gp_sqlite_scalar('SELECT 42', function($n) use (&$scalar) {
    checkpoint(); Async\delay(1); $scalar = $n; return 0;
}) === 0 && $scalar === 42);
graal_assert($ffi->gp_test_active() === 0, 'FFI scenario at line 78');
$buffers = FFI::cdef('typedef uint8_t *bytes; int gp_test_buffer(bytes buffer, size_t length, void (*checkpoint)(void));
    int gp_test_buffer_first(const char *buffer);',
    getenv('FFI_TEST_LIBRARY'));
$buffer = $buffers->new('char[5]');
graal_assert(get_class($buffer) === 'FFI\\CData' && FFI::sizeof($buffer) === 5, 'owned buffer size and type');
FFI::memset($buffer, 65, 4);
graal_assert(FFI::string($buffer) === 'AAAA', 'C string stops at zero');
$copy = $buffers->new('uint8_t[5]');
FFI::memcpy($copy, $buffer, 5);
graal_assert($buffers->gp_test_buffer_first($copy) === 65
    && $buffers->gp_test_buffer_first($copy, async: true) === 65, 'char pointer accepts a buffer without callback');
try { FFI::memcpy($copy, 'X', 2); graal_assert(false, 'out of bounds C buffer source'); }
catch (ValueError $e) { graal_assert(bin2hex(FFI::string($copy, 5)) === '4141414100'); }
graal_assert($buffers->gp_test_buffer(null, 4, function () {}) === -1, 'nullable C pointer argument');
foreach ([false, true] as $offload) {
    $buffer = $buffers->new('unsigned char [4]');
    FFI::memcpy($buffer, hex2bin('0001feff'), 4);
    $weak = WeakReference::create($buffer);
    $sum = $buffers->gp_test_buffer($buffer, 4, function () use (&$buffer, $weak) {
        $buffer = null;
        Async\suspend();
        checkpoint();
        graal_assert(bin2hex(FFI::string($weak->get(), 4)) === '0901feff', 'C pointer stays valid across callback suspension');
    }, async: $offload, pool: 'serial-callbacks');
    graal_assert($sum === 519 && $weak->get() === null, 'owned buffer released after physical C return');
}
$started = new Async\Channel(1); $weak = null; $finished = $ffi->gp_test_finished();
$taking = Async\spawn(function () use ($buffers, $started, &$weak) {
    $buffer = $buffers->new('char[4]');
    $weak = WeakReference::create($buffer);
    $buffers->gp_test_buffer($buffer, 4, function () use ($started, &$buffer) {
        $buffer = null;
        $started->send(true);
        Async\delay(5000);
    }, async: true, pool: 'serial-callbacks');
});
$started->recv(); graal_assert($weak->get() !== null, 'in-flight C owns the buffer');
$taking->cancel();
try { Async\await($taking); } catch (Async\AsyncCancellation $e) {}
graal_assert($ffi->gp_test_finished() === $finished + 1 && $weak->get() === null, 'cancel drains C before dropping buffer');
echo 'PASS transparent FFI: nested C stacks, owner coroutine/thread, scalar ABIs, buffers, GC, pool serialization, error/cancel drain, SQLite callback';
