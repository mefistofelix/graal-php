<?php
// Identical source for GraalPHP and the official TrueAsync binary.
$config = (new TrueAsync\HttpServerConfig())->addListener('127.0.0.1', intval(getenv('BENCH_PORT')));
$config->setWorkers(1)->setMaxConnections(2048)->setReadTimeout(30)->setWriteTimeout(30)->setShutdownTimeout(1);
$server = new TrueAsync\HttpServer($config);
$tasks = [];
$state = new Async\FutureState();
$gate = new Async\Future($state);
$upstream = getenv('BENCH_UPSTREAM');
$server->addHttpHandler(function($request, $response) use ($server, &$tasks, $state, $gate) {
    $path = $request->getPath();
    if ($path === '/park') {
        $count = intval($request->getHeaderLine('X-Count'));
        $ready = new Async\Channel($count);
        for ($i = 0; $i < $count; $i++) {
            $tasks[] = Async\spawn(function() use ($ready, $gate) {
                $ready->send(1);
                return Async\await($gate);
            });
        }
        for ($i = 0; $i < $count; $i++) $ready->recv();
        $response->setBody('parked');
    } else if ($path === '/release') {
        $state->complete(1);
        foreach ($tasks as $task) Async\await($task);
        $tasks = [];
        $response->setBody('released');
    } else if ($path === '/stop') {
        $response->setBody('stopped');
        $server->stop();
    } else $response->setBody('ready');
});
$server->addWebSocketHandler(function($socket, $request) use ($upstream) {
    $path = $request->getPath();
    $relay = $path === '/relay' || $path === '/relay-sqlite';
    $sqlite = $path === '/sqlite' || $path === '/relay-sqlite';
    $native = $path === '/callback';
    $ffi = null;
    if ($native) $ffi = FFI::cdef('int64_t gp_test_walk(int64_t (*callback)(int64_t,const char*),int64_t seed,int depth,const char *text);', getenv('FFI_TEST_LIBRARY'));
    $db = null; $statement = null;
    if ($sqlite) {
        $db = new SQLite3(':memory:');
        $db->exec('CREATE TABLE payloads (id INTEGER PRIMARY KEY, body TEXT)');
        $statement = $db->prepare('INSERT OR REPLACE INTO payloads(id, body) VALUES(1, :body)');
    }
    $curl = null;
    if ($relay) {
        $curl = curl_init($upstream);
        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_NOPROXY => '*',
            CURLOPT_TIMEOUT_MS => 10000
        ]);
    }
    while (($message = $socket->recv()) !== null) {
        $body = $message->data;
        if ($relay) {
            $body = curl_exec($curl);
            if ($body === false) throw new Exception(curl_error($curl));
        }
        if ($sqlite) {
            $statement->bindValue(':body', $body, SQLITE3_TEXT);
            $result = $statement->execute();
            $result->finalize();
            $body = $db->querySingle('SELECT body FROM payloads WHERE id = 1');
        }
        if ($native) {
            $sum = $ffi->gp_test_walk(function($n, $text) { Async\delay(0); return $n * 2; }, 1, 32, 'fixture');
            if ($sum !== 44) throw new Exception('Native callback result mismatch');
        }
        $socket->send($body);
    }
    if ($sqlite) { $statement->close(); $db->close(); }
});
$server->start();
