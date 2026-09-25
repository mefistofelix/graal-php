<?php
// Used by websocket-sqlite.php: public PHP multi API on the shared libuv reactor.
function multiRequest($url, $certificate) {
    $curl = curl_init($url);
    curl_setopt_array($curl, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CAINFO => $certificate,
        CURLOPT_NOPROXY => '*',
        CURLOPT_TIMEOUT_MS => 15000
    ]);
    graal_assert(curl_impersonate($curl, 'chrome136'));
    return $curl;
}
function driveMulti($multi) {
    do {
        graal_assert(curl_multi_exec($multi, $running) === CURLM_OK);
        if ($running > 0) curl_multi_select($multi, 5);
    } while ($running > 0);
}
function multiBatch($socket, $database, $upstream, $certificate) {
    $multi = curl_multi_init();
    $handles = [];
    try {
        graal_assert(curl_multi_setopt($multi, CURLMOPT_MAX_TOTAL_CONNECTIONS, 2));
        graal_assert(curl_multi_setopt($multi, CURLMOPT_MAX_HOST_CONNECTIONS, 2));
        for ($i = 0; $i < 6; $i++) {
            $curl = multiRequest($upstream . '/multi-batch', $certificate);
            graal_assert(curl_multi_add_handle($multi, $curl) === CURLM_OK);
            $handles[] = $curl;
        }
        $bad = multiRequest('unsupported-scheme://localhost/', $certificate);
        curl_multi_add_handle($multi, $bad);
        $socket->send('multi-batch-started');
        driveMulti($multi);
        $successes = 0; $failures = 0; $remaining = 99;
        $database->exec('CREATE TABLE IF NOT EXISTS multi_results (body TEXT, status INTEGER)');
        $insert = $database->prepare('INSERT INTO multi_results VALUES (?, ?)');
        try {
            while (($info = curl_multi_info_read($multi, $remaining)) !== false) {
                graal_assert($info['msg'] === CURLMSG_DONE);
                $curl = $info['handle'];
                if ($curl === $bad) {
                    graal_assert($info['result'] === CURLE_UNSUPPORTED_PROTOCOL && curl_errno($curl) === 1);
                    graal_assert(curl_multi_getcontent($curl) === '' && curl_error($curl) !== '');
                    $failures++;
                } else {
                    graal_assert($info['result'] === CURLE_OK);
                    $body = curl_multi_getcontent($curl);
                    graal_assert($body === 'fixture-body');
                    $details = curl_getinfo($curl);
                    graal_assert($details['http_code'] === 200 && curl_getinfo($curl, CURLINFO_RESPONSE_CODE) === 200);
                    $insert->bindValue(1, $body, SQLITE3_TEXT);
                    $insert->bindValue(2, $details['http_code'], SQLITE3_INTEGER);
                    $result = $insert->execute(); $result->finalize();
                    $successes++;
                }
            }
        } finally { $insert->close(); }
        graal_assert($successes === 6 && $failures === 1 && $remaining === 0);
        graal_assert(count(curl_multi_get_handles($multi)) === 7);
        curl_multi_close($multi);
        graal_assert(count(curl_multi_get_handles($multi)) === 0);
        curl_setopt($bad, CURLOPT_URL, $upstream . '/reuse');
        curl_multi_add_handle($multi, $bad);
        driveMulti($multi);
        $info = curl_multi_info_read($multi);
        graal_assert($info['handle'] === $bad && $info['result'] === CURLE_OK);
        graal_assert(curl_multi_getcontent($bad) === 'fixture-body');
        $socket->send('multi-batch-ok:6:1');
    } finally { curl_multi_close($multi); }
}
function multiCancellation($socket, $upstream, $certificate) {
    $multi = curl_multi_init();
    $first = multiRequest($upstream . '/multi-wait', $certificate);
    $second = multiRequest($upstream . '/multi-remove', $certificate);
    curl_multi_add_handle($multi, $first);
    curl_multi_add_handle($multi, $second);
    $waiter = Async\spawn(function() use ($multi) {
        while (true) curl_multi_select($multi, 30);
    });
    $observer = Async\spawn(function() use ($multi) { driveMulti($multi); return 'completed'; });
    try {
        $socket->send('multi-cancel-started');
        $command = $socket->recv();
        graal_assert($command->data === 'cancel-wait');
        $waiter->cancel();
        try { Async\await($waiter); throw exception('Cancelled select returned normally'); }
        catch (Async\AsyncCancellation $cancelled) {}
        graal_assert(count(curl_multi_get_handles($multi)) === 2 && !$observer->isCompleted());
        graal_assert(curl_multi_select($multi, 0.01) >= 0);
        graal_assert(curl_multi_exec($multi, $running) === CURLM_OK && $running === 2);
        $socket->send('multi-wait-cancelled-transfers-active');
        graal_assert(curl_multi_remove_handle($multi, $second) === CURLM_OK);
        curl_setopt($second, CURLOPT_URL, $upstream . '/reuse');
        graal_assert(curl_exec($second) === 'fixture-body');
        $socket->send('multi-removed-and-reused');
        $command = $socket->recv();
        graal_assert($command->data === 'release-first');
        graal_assert(Async\await($observer) === 'completed');
        $info = curl_multi_info_read($multi);
        graal_assert($info['handle'] === $first && $info['result'] === CURLE_OK);
        graal_assert(curl_multi_getcontent($first) === 'fixture-body');
        graal_assert(curl_multi_info_read($multi) === false);
        curl_multi_remove_handle($multi, $first);
        curl_setopt($second, CURLOPT_URL, $upstream . '/multi-close');
        curl_multi_add_handle($multi, $second);
        $closing = Async\spawn(function() use ($multi) { driveMulti($multi); return true; });
        $socket->send('multi-close-started');
        $command = $socket->recv();
        graal_assert($command->data === 'close-now');
        curl_multi_close($multi);
        graal_assert(Async\await($closing));
        curl_setopt($second, CURLOPT_URL, $upstream . '/reuse');
        graal_assert(curl_exec($second) === 'fixture-body');
        $socket->send('multi-close-and-reuse-ok');
    } finally {
        $waiter->cancel();
        curl_multi_close($multi);
    }
}
