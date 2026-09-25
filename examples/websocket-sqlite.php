<?php
use TrueAsync\HttpServer;
use TrueAsync\HttpServerConfig;
use TrueAsync\HttpRequest;
use TrueAsync\HttpResponse;
use TrueAsync\WebSocket;
require __DIR__ . '/curl-multi.php';

// GRAALPHP_DEMO_PORT, GRAALPHP_DEMO_DB, GRAALPHP_DEMO_UPSTREAM and GRAALPHP_DEMO_CA
// are supplied by the integration harness. The server itself uses only PHP APIs.
FFI::definePool('sqlite', min: 1, max: 1, queueCapacity: 256);
// cURL transfers share the libuv reactor; SQLite remains on its named blocking pool.
$database = new SQLite3(getenv('GRAALPHP_DEMO_DB'));
$database->exec('PRAGMA journal_mode=WAL');
$database->exec('CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, payload TEXT NOT NULL, remote TEXT NOT NULL, status INTEGER NOT NULL)');
$database->exec('CREATE TABLE IF NOT EXISTS rollback_probe (value INTEGER)');
$transaction = new Async\Mutex;
$upstream = getenv('GRAALPHP_DEMO_UPSTREAM');
$certificate = getenv('GRAALPHP_DEMO_CA');

function fetchRemote($url, $certificate, $profile = true, $timeout = 5000) {
    $curl = curl_init($url);
    try {
        curl_setopt($curl, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($curl, CURLOPT_TIMEOUT_MS, $timeout);
        curl_setopt($curl, CURLOPT_NOPROXY, '*');
        if ($certificate !== '') curl_setopt($curl, CURLOPT_CAINFO, $certificate);
        if ($profile) graal_assert(curl_impersonate($curl, 'chrome136'), 'Cannot apply Chrome profile');
        $body = curl_exec($curl);
        return [$body, curl_getinfo($curl, CURLINFO_RESPONSE_CODE), curl_errno($curl), curl_error($curl)];
    } finally {
        curl_close($curl);
    }
}

$config = (new HttpServerConfig())->addListener('127.0.0.1', intval(getenv('GRAALPHP_DEMO_PORT')));
$config->setReadTimeout(10)->setWriteTimeout(5)->setShutdownTimeout(1)->setWsMaxMessageSize(262144);
$server = new HttpServer($config);
$server->addHttpHandler(function(HttpRequest $request, HttpResponse $response) use ($server, $database, $upstream, $certificate) {
    $path = $request->getPath();
    if ($path === '/health') $response->setBody('ready');
    else if ($path === '/count') $response->setBody(strval($database->querySingle('SELECT count(*) FROM messages')));
    else if ($path === '/baseline') {
        $result = fetchRemote($upstream . '/baseline', $certificate, false);
        $response->setBody($result[0]);
    } else if ($path === '/rollback') {
        $database->exec('BEGIN IMMEDIATE');
        $database->exec('INSERT INTO rollback_probe VALUES (42)');
        $database->exec('ROLLBACK');
        $response->setBody(strval($database->querySingle('SELECT count(*) FROM rollback_probe')));
    } else if ($path === '/shutdown') {
        $response->setBody('stopping');
        $server->stop();
    } else $response->setStatusCode(404)->setBody('not found');
});
$server->addWebSocketHandler(function(WebSocket $socket, HttpRequest $request) use ($database, $transaction, $upstream, $certificate) {
    if ($request->getPath() === '/concurrent-read') {
        $reader = Async\spawn(function() use ($socket) { return $socket->recv(); });
        Async\delay(5);
        try {
            $socket->recv();
            throw exception('Concurrent recv was accepted');
        } catch (TrueAsync\WebSocketConcurrentReadException $error) {
            $socket->send('concurrent-read-rejected');
        } finally {
            $reader->cancel();
            try { Async\await($reader); } catch (Throwable $cancelled) {}
        }
        return;
    }
    $insert = $database->prepare('INSERT INTO messages(payload, remote, status) VALUES (:payload, :remote, :status)');
    try {
        while (($message = $socket->recv()) !== null) {
            if ($message->binary) {
                $socket->sendBinary($message->data);
                continue;
            }
            if ($message->data === 'CURL-MULTI-BATCH') {
                multiBatch($socket, $database, $upstream, $certificate);
                continue;
            }
            if ($message->data === 'CURL-MULTI-CANCEL') {
                multiCancellation($socket, $upstream, $certificate);
                continue;
            }
            if ($message->data === 'CANCEL-CURL') {
                $curl = curl_init($upstream . '/cancel');
                curl_setopt($curl, CURLOPT_RETURNTRANSFER, true);
                curl_setopt($curl, CURLOPT_CAINFO, $certificate);
                curl_setopt($curl, CURLOPT_NOPROXY, '*');
                curl_setopt($curl, CURLOPT_TIMEOUT_MS, 15000);
                $transfer = Async\spawn(function() use ($curl) { return curl_exec($curl); });
                try {
                    $socket->send('cancel-started');
                    $command = $socket->recv();
                    graal_assert($command->data === 'cancel-now');
                    $transfer->cancel();
                    try {
                        Async\await($transfer);
                        throw exception('Cancelled transfer completed normally');
                    } catch (Async\AsyncCancellation $cancelled) {}
                    graal_assert(curl_errno($curl) === 42);
                    curl_setopt($curl, CURLOPT_URL, $upstream . '/reuse');
                    $body = curl_exec($curl);
                    graal_assert($body !== false, curl_error($curl));
                    $socket->send('cancelled-and-reused:' . $body);
                } finally { curl_close($curl); }
                continue;
            }
            if ($message->data === 'CURL-ERRORS') {
                $curl = curl_init('unsupported-scheme://localhost/');
                try {
                    curl_setopt($curl, CURLOPT_RETURNTRANSFER, true);
                    graal_assert(curl_exec($curl) === false && curl_errno($curl) === 1);
                    curl_setopt($curl, CURLOPT_URL, $upstream . '/reuse');
                    curl_setopt($curl, CURLOPT_CAINFO, $certificate);
                    curl_setopt($curl, CURLOPT_NOPROXY, '*');
                    graal_assert(curl_exec($curl) !== false && curl_errno($curl) === 0);
                } finally { curl_close($curl); }
                graal_assert(curl_exec($curl) !== false);
                unset($curl);
                $socket->send('curl-errors-and-reuse-ok');
                continue;
            }
            if ($message->data === 'TLS-FAIL') {
                $remote = fetchRemote($upstream . '/payload', '');
                $socket->send('TLS:' . $remote[2]);
                continue;
            }
            if ($message->data === 'TIMEOUT') {
                $remote = fetchRemote($upstream . '/slow', $certificate, true, 150);
                $socket->send('TIMEOUT:' . $remote[2]);
                continue;
            }
            $remote = fetchRemote($upstream . '/payload', $certificate);
            graal_assert($remote[0] !== false && $remote[1] === 200, $remote[3]);
            $transaction->lock();
            try {
                $database->exec('BEGIN IMMEDIATE');
                try {
                    $insert->bindValue(':payload', $message->data, SQLITE3_TEXT);
                    $insert->bindValue(':remote', $remote[0], SQLITE3_TEXT);
                    $insert->bindValue(':status', $remote[1], SQLITE3_INTEGER);
                    $result = $insert->execute();
                    $result->finalize();
                    $id = $database->lastInsertRowID();
                    $database->exec('COMMIT');
                } catch (Throwable $error) {
                    $database->exec('ROLLBACK');
                    throw $error;
                }
            } finally {
                $transaction->unlock();
            }
            $socket->send('OK:' . $id . ':' . $message->data . ':' . $remote[0]);
        }
    } finally {
        $insert->close();
    }
});
$server->start();
$database->close();
echo "server stopped\n";
