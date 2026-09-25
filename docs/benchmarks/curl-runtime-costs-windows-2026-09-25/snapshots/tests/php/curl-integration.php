<?php
$checks = 0;
function check($condition, $label) {
    global $checks;
    if (!$condition) throw new Exception($label);
    $checks++;
}
function client($url, $provider = null) {
    $h = $provider === null ? curl_init($url) : curl_init($url, $provider);
    curl_setopt_array($h, [CURLOPT_RETURNTRANSFER => true, CURLOPT_NOPROXY => '*', CURLOPT_TIMEOUT_MS => 10000, CURLOPT_CAINFO => getenv('CURL_TEST_CA')]);
    return $h;
}
function fetch($url) {
    $h = client($url);
    $body = curl_exec($h);
    if ($body === false) throw new Exception(curl_error($h));
    return $body;
}
function reached($url, $count) {
    for ($i = 0; $i < 1000; $i++) {
        if (intval(fetch($url)) === $count) return;
        Async\delay(1);
    }
    throw new Exception('Peer barrier was not reached');
}
$http = getenv('CURL_TEST_HTTP');
$https = getenv('CURL_TEST_HTTPS');
$version = curl_version();
echo 'VERSION ' . $version['version'] . "\n";
$h = client($https . '/body');
check(curl_exec($h) === 'curl-body', 'verified HTTPS');
check(curl_getinfo($h, CURLINFO_RESPONSE_CODE) === 200, 'HTTPS response code');
check(bin2hex(fetch($https . '/bytes')) === '007fc0afeda080ffe282acf09f9880', 'binary response preserves malformed UTF-8 and NUL');
check(bin2hex(fetch($https . '/unicode')) === 'e282acf09f988000', 'UTF-8 multibyte response preserves NUL');
check(curl_exec(client($https . '/body')) === 'curl-body', 'temporary handle survives suspension');
check(fetch($https . '/empty') === '', 'empty body');
check(strlen(fetch($https . '/large')) === 1048576, 'large response copied across native boundary');
curl_setopt($h, CURLOPT_URL, $http . '/peer');
$peer = curl_exec($h);
check($peer === curl_exec($h), 'same easy handle reuses its TCP connection');
curl_setopt($h, CURLOPT_URL, $https . '/redirect');
curl_setopt($h, CURLOPT_FOLLOWLOCATION, true);
check(curl_exec($h) === 'curl-body', 'HTTPS redirect');
check(curl_getinfo($h, CURLINFO_EFFECTIVE_URL) === $https . '/body', 'effective URL');
curl_setopt($h, CURLOPT_URL, $https . '/gzip');
curl_setopt($h, CURLOPT_ACCEPT_ENCODING, 'gzip');
check(curl_exec($h) === 'curl-body', 'gzip decoding');
curl_setopt($h, CURLOPT_URL, $http . '/headers');
curl_setopt($h, CURLOPT_HTTPHEADER, ['X-Test: selected']);
check(curl_exec($h) === 'selected', 'provider-owned header list');
curl_setopt($h, CURLOPT_HTTPHEADER, []);
check(curl_exec($h) === 'absent', 'header list cleared');
curl_setopt($h, CURLOPT_URL, $http . '/post');
curl_setopt($h, CURLOPT_POSTFIELDS, 'posted-data');
check(curl_exec($h) === 'posted-data', 'POST body');
curl_setopt($h, CURLOPT_HTTPGET, true);
curl_setopt($h, CURLOPT_URL, $http . '/missing');
check(curl_exec($h) === 'curl-body' && curl_getinfo($h, CURLINFO_RESPONSE_CODE) === 404, 'HTTP error body and status');
curl_setopt($h, CURLOPT_URL, $http . '/delay');
curl_setopt($h, CURLOPT_TIMEOUT_MS, 30);
check(curl_exec($h) === false && curl_errno($h) === 28, 'timeout');
curl_setopt($h, CURLOPT_TIMEOUT_MS, 10000);
curl_setopt($h, CURLOPT_URL, $http . '/body');
check(curl_exec($h) === 'curl-body', 'reuse after timeout');
$untrusted = curl_init($https . '/body');
curl_setopt_array($untrusted, [CURLOPT_RETURNTRANSFER => true, CURLOPT_NOPROXY => '*']);
check(curl_exec($untrusted) === false && curl_errno($untrusted) === 60, 'untrusted TLS certificate rejected');
$bad = client('unsupported://localhost/');
check(curl_exec($bad) === false && curl_errno($bad) === 1, 'unsupported protocol');

$held = client($https . '/hold?cancel');
$task = Async\spawn(function() use ($held) { return curl_exec($held); });
reached($http . '/state?cancel', 1);
if (getenv('CURL_TEST_PROVIDERS') === '1') {
    $busy = false;
    try { curl_exec($held); } catch (Error $error) { $busy = true; }
    check($busy, 'in-flight handle cannot be executed by a second coroutine');
    $busy = false;
    try { curl_setopt($held, CURLOPT_URL, $https . '/body'); } catch (Error $error) { $busy = true; }
    check($busy, 'in-flight handle cannot be reconfigured');
}
$task->cancel();
$cancelled = false;
try { Async\await($task); } catch (Async\AsyncCancellation $error) { $cancelled = true; }
check($cancelled, 'in-flight cancellation propagates');
curl_setopt($held, CURLOPT_URL, $https . '/body');
check(curl_exec($held) === 'curl-body', 'cancelled handle reused before peer release');
fetch($http . '/release?cancel');

$preCancelled = client($https . '/hold?pre-cancel');
$finallyRan = new Async\Channel(1);
$earlyTask = Async\spawn(function() use ($preCancelled, $finallyRan) {
    try {
        Async\current_coroutine()->cancel();
        curl_exec($preCancelled);
    } finally { $finallyRan->send(1); }
});
$earlyCancelled = false;
try { Async\await($earlyTask); } catch (Async\AsyncCancellation $error) { $earlyCancelled = true; }
check($earlyCancelled && $finallyRan->recv() === 1, 'cancellation before suspension executes guest finally');
curl_setopt($preCancelled, CURLOPT_URL, $https . '/body');
check(curl_exec($preCancelled) === 'curl-body', 'pre-cancelled handle detached before guest resumes');
fetch($http . '/release?pre-cancel');

$shielded = client($https . '/hold?shield');
$shieldResult = new Async\Channel(1);
$shieldTask = Async\spawn(function() use ($shielded, $shieldResult) {
    Async\protect(function() use ($shielded, $shieldResult) { $shieldResult->send(curl_exec($shielded)); });
});
reached($http . '/state?shield', 1);
$shieldTask->cancel();
check(fetch($http . '/body') === 'curl-body' && !$shieldTask->isCompleted(), 'outer protect keeps cURL pending on cancellation');
fetch($http . '/release?shield');
check($shieldResult->recv() === 'curl-body', 'protected cURL finishes before cancellation delivery');
try { Async\await($shieldTask); } catch (Async\AsyncCancellation $error) {}
curl_setopt($shielded, CURLOPT_URL, $https . '/body');
check(curl_exec($shielded) === 'curl-body', 'protected cancelled handle can be reused');

$tasks = [];
for ($i = 0; $i < 16; $i++) $tasks[] = Async\spawn(function() use ($https) { return fetch($https . '/hold?batch'); });
reached($http . '/state?batch', 16);
check(fetch($http . '/body') === 'curl-body', 'control request progresses with 16 suspended curl calls');
fetch($http . '/release?batch');
foreach ($tasks as $task) check(Async\await($task) === 'curl-body', 'concurrent HTTPS result');

$multi = curl_multi_init();
$handles = [];
check(curl_multi_setopt($multi, CURLMOPT_MAX_TOTAL_CONNECTIONS, 2), 'multi connection limit');
for ($i = 0; $i < 8; $i++) {
    $easy = client($https . '/body');
    check(curl_multi_add_handle($multi, $easy) === CURLM_OK, 'multi add');
    $handles[] = $easy;
}
do {
    check(curl_multi_exec($multi, $running) === CURLM_OK, 'multi exec');
    if ($running > 0) check(curl_multi_select($multi, 1.0) >= 0, 'multi select');
} while ($running > 0);
$completed = 0;
while (($info = curl_multi_info_read($multi)) !== false) {
    check($info['result'] === CURLE_OK && curl_multi_getcontent($info['handle']) === 'curl-body', 'multi completion');
    $completed++;
}
check($completed === 8, 'all multi results');
foreach ($handles as $easy) check(curl_multi_remove_handle($multi, $easy) === CURLM_OK, 'multi remove');
check(curl_exec($handles[0]) === 'curl-body', 'reuse after multi removal');
curl_multi_close($multi);

if (getenv('CURL_TEST_PROVIDERS') === '1') {
    check($version['provider'] === 'standard', 'environment selects standard provider');
    check(curl_version(provider: 'impersonate')['provider'] === 'curl-impersonate/2.2.2', 'impersonate bundle retained');
    $ordinary = curl_init(provider: 'standard');
    check(curl_impersonate($ordinary, 'chrome136') === false && curl_errno($ordinary) === 4, 'standard handle rejects impersonation');
    $browser = client($https . '/body', 'impersonate');
    check(curl_impersonate($browser, 'chrome136'), 'impersonate profile remains available');
    $standardMulti = curl_multi_init(provider: 'standard');
    check(curl_multi_add_handle($standardMulti, $browser) === CURLM_BAD_EASY_HANDLE, 'cross-provider handle rejected');
    $other = Async\spawn(function() use ($browser) { return curl_exec($browser); });
    check(fetch($https . '/body') === 'curl-body' && Async\await($other) === 'curl-body', 'both TLS providers on one reactor');
    $browserMulti = curl_multi_init(provider: 'impersonate');
    check(curl_multi_add_handle($browserMulti, $browser) === CURLM_OK, 'matching impersonate multi');
    do { curl_multi_exec($browserMulti, $running); if ($running > 0) curl_multi_select($browserMulti, 1.0); } while ($running > 0);
    check(curl_multi_getcontent($browser) === 'curl-body', 'impersonate multi result');
    curl_multi_remove_handle($browserMulti, $browser);
    curl_multi_close($browserMulti);
    curl_multi_close($standardMulti);
    $invalid = false;
    try { curl_init(provider: 'missing'); } catch (ValueError $error) { $invalid = true; }
    check($invalid, 'invalid provider rejected');
}
echo 'PASS curl assertions=' . $checks . "\n";
