<?php
// Identical direct cURL workload on GraalPHP and TrueAsync; no PHP server or FFI.
function signalPeer($path) {
    $h = curl_init(getenv('BENCH_UPSTREAM') . $path);
    curl_setopt_array($h, [CURLOPT_RETURNTRANSFER => true, CURLOPT_NOPROXY => '*', CURLOPT_TIMEOUT_MS => 10000]);
    if (curl_exec($h) !== 'ok') throw new Exception('Benchmark control failed: ' . curl_error($h));
}
function curlBatch($clients, $iterations, $sampleEvery, $timeline) {
    $tasks = [];
    $url = getenv('BENCH_UPSTREAM') . '/payload';
    $expected = getenv('BENCH_BODY');
    for ($i = 0; $i < $clients; $i++) {
        $tasks[] = Async\spawn(function() use ($url, $iterations, $expected, $sampleEvery, $timeline, $i) {
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
                        $latencies[] = $timeline ? [$start, $elapsed] : $elapsed;
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
$timeline = getenv('BENCH_TIMELINE') === '1';
signalPeer('/begin');
$sampleEvery = intval(getenv('BENCH_LATENCY_EVERY'));
$state = new Async\FutureState();
$gate = new Async\Future($state);
$parked = [];
$count = intval(getenv('BENCH_PARKED'));
$ready = new Async\Channel($count);
for ($i = 0; $i < $count; $i++) $parked[] = Async\spawn(function() use ($gate, $ready) { $ready->send(1); return Async\await($gate); });
for ($i = 0; $i < $count; $i++) $ready->recv();
$clockBefore = $timeline ? hrtime(true) : 0;
signalPeer('/suspended');
$clockAfter = $timeline ? hrtime(true) : 0;
$latencies = curlBatch(intval(getenv('BENCH_CLIENTS')), intval(getenv('BENCH_ITERATIONS')), $sampleEvery, $timeline);
$state->complete(1);
foreach ($parked as $task) Async\await($task);
signalPeer('/end');
// Emit only after the measured window: no logging or percentile sorting in traffic.
if ($timeline) echo 'CLOCK ' . $clockBefore . ' ' . $clockAfter . "\n";
foreach ($latencies as $client => $samples) {
    foreach ($samples as $index => $sample) {
        if ($timeline) {
            $iteration = $client % $sampleEvery + $index * $sampleEvery;
            echo 'LATENCY ' . $sample[1] . "\n";
            echo 'TIMELINE ' . $client . ' ' . $iteration . ' ' . $sample[0] . ' ' . $sample[1] . "\n";
        } else {
            echo 'LATENCY ' . $sample . "\n";
        }
    }
}
echo "PASS curl benchmark\n";
