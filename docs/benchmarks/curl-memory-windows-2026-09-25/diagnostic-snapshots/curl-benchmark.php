<?php
// Identical direct cURL workload on GraalPHP and TrueAsync; no PHP server or FFI.
function signalPeer($path) {
    $h = curl_init(getenv('BENCH_UPSTREAM') . $path);
    curl_setopt_array($h, [CURLOPT_RETURNTRANSFER => true, CURLOPT_NOPROXY => '*', CURLOPT_TIMEOUT_MS => 10000]);
    if (curl_exec($h) !== 'ok') throw new Exception('Benchmark control failed: ' . curl_error($h));
}
function curlBatch($clients, $iterations, $sampleEvery) {
    $tasks = [];
    $url = getenv('BENCH_UPSTREAM') . '/payload';
    $expected = getenv('BENCH_BODY');
    for ($i = 0; $i < $clients; $i++) {
        $tasks[] = Async\spawn(function() use ($url, $iterations, $expected, $sampleEvery, $i) {
            $h = curl_init($url);
            curl_setopt_array($h, [CURLOPT_RETURNTRANSFER => true, CURLOPT_NOPROXY => '*', CURLOPT_TIMEOUT_MS => 10000, CURLOPT_HTTP_VERSION => CURL_HTTP_VERSION_1_1]);
            $latencies = [];
            if ($sampleEvery === 0) {
                for ($j = 0; $j < $iterations; $j++) {
                    if (curl_exec($h) !== $expected) throw new Exception('Response mismatch: ' . curl_error($h));
                }
            } else {
                $nextSample = $i % $sampleEvery;
                for ($j = 0; $j < $iterations; $j++) {
                    if ($j === $nextSample) {
                        $start = hrtime(true);
                        $body = curl_exec($h);
                        $elapsed = hrtime(true) - $start;
                        if ($body !== $expected) throw new Exception('Response mismatch: ' . curl_error($h));
                        $latencies[] = $elapsed;
                        $nextSample += $sampleEvery;
                    } else {
                        if (curl_exec($h) !== $expected) throw new Exception('Response mismatch: ' . curl_error($h));
                    }
                }
            }
            return $latencies;
        });
    }
    $results = [];
    foreach ($tasks as $task) $results[] = Async\await($task);
    return $results;
}
$version = curl_version();
echo 'VERSION ' . $version['version'] . "\n";
signalPeer('/startup');
$warmup = getenv('BENCH_WARMUP');
$sampleEvery = intval(getenv('BENCH_LATENCY_EVERY'));
curlBatch(64, $warmup ? intval($warmup) : 256, $sampleEvery);
signalPeer('/baseline');
$state = new Async\FutureState();
$gate = new Async\Future($state);
$parked = [];
$count = intval(getenv('BENCH_PARKED'));
$ready = new Async\Channel($count);
for ($i = 0; $i < $count; $i++) $parked[] = Async\spawn(function() use ($gate, $ready) { $ready->send(1); return Async\await($gate); });
for ($i = 0; $i < $count; $i++) $ready->recv();
signalPeer('/suspended');
signalPeer('/begin');
$latencies = curlBatch(intval(getenv('BENCH_CLIENTS')), intval(getenv('BENCH_ITERATIONS')), $sampleEvery);
signalPeer('/end');
// Emit only after the measured window: no logging or percentile sorting in traffic.
foreach ($latencies as $samples) foreach ($samples as $elapsed) echo 'LATENCY ' . $elapsed . "\n";
$state->complete(1);
foreach ($parked as $task) Async\await($task);
echo "PASS curl benchmark\n";
