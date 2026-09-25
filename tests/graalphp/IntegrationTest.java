package graalphp;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class IntegrationTest {
    private static int scenarios;
    private IntegrationTest() {}
    public static void main(String[] arguments) throws Exception {
        check("binary native values and PHP environment semantics", """
            $bytes = hex2bin('00ff80fe') . hex2bin('0041');
            graal_assert(hex2bin('invalid') === false);
            graal_assert(getenv('GRAALPHP_UNDEFINED_TEST_VARIABLE_97D42A') === false);
            echo strlen($bytes), ':', bin2hex($bytes);
            """, "6:00ff80fe0041");
        check("multi destruction detaches a retained easy handle", """
            $easy = curl_init('unsupported-scheme://localhost/');
            curl_setopt($easy, CURLOPT_RETURNTRANSFER, true);
            $multi = curl_multi_init(); curl_multi_add_handle($multi, $easy);
            curl_multi_exec($multi, $running);
            curl_multi_close($multi); curl_multi_add_handle($multi, $easy);
            curl_multi_exec($multi, $running); unset($multi);
            echo curl_exec($easy) === false, ':', curl_errno($easy);
            """, "1:1");
        check("multi ownership inside a collected reference cycle", """
            function cycle($easy) {
                $multi = curl_multi_init(); curl_multi_add_handle($multi, $easy);
                $cycle = []; $cycle['self'] =& $cycle; $cycle['multi'] = $multi;
            }
            $easy = curl_init('unsupported-scheme://localhost/');
            curl_setopt($easy, CURLOPT_RETURNTRANSFER, true);
            cycle($easy);
            echo curl_exec($easy) === false, ':', curl_errno($easy);
            """, "1:1");
        check("nested continuations, array lifetime and finally", """
            function inner($a) { sleep_ms(2); return $a; }
            function outer($a) { try { return inner($a); } finally { echo 'F'; } }
            $result = outer([4, 5]); echo $result[0], ':', $result[1];
            """, "F4:5");
        check("branch context isolation", """
            function child() { echo context_get('key'); context_set('key', 'C'); sleep_ms(2); echo context_get('key'); }
            context_set('key', 'P'); $task = spawn('child'); sleep_ms(1); echo context_get('key'); await($task);
            """, "PPC");
        check("cancellation unwinds nested finally", """
            function nested() { try { sleep_ms(500); } finally { echo 'N'; } }
            function child() { try { nested(); } finally { echo 'C'; } }
            $task = spawn('child'); sleep_ms(2); cancel($task);
            try { await($task); } catch (Throwable $error) { echo 'X'; }
            """, "NCX");
        check("exception crosses continuation chain", """
            function fail() { sleep_ms(1); throw new Exception('broken'); }
            function wrapper() { try { fail(); } finally { echo 'F'; } }
            try { wrapper(); } catch (Throwable $error) { echo error_message($error); }
            """, "Fbroken");
        check("real workers and shared promotion", """
            function add($counter) { $i = 0; while ($i < 2000) { shared_add($counter, 1); $i += 1; } return 1; }
            $counter = shared_counter(0); $a = parallel('add', $counter); $b = parallel('add', $counter);
            await($a); await($b); echo shared_get($counter);
            """, "4000");
        check("short circuit and reference parameter", """
            function mutate(&$n) { $n += 1; return true; }
            $n = 1; false && mutate($n); true || mutate($n); true && mutate($n); echo $n;
            """, "2");
        check("eval shares local scope", "function run() { $x = 1; eval('$x = 9;'); return $x; } echo run();", "9");
        check("COW return and async expression temporaries", """
            function one() { sleep_ms(1); return [7]; }
            $a = [1, one()[0], 3]; $b = $a; $a[1] = 20; echo $b[1], ':', $a[1];
            """, "7:20");
        check("finally overrides return", "function f() { try { return 1; } finally { sleep_ms(1); return 2; } } echo f();", "2");
        check("return through foreach cleanup", "function f() { $a = [9]; foreach ($a as &$v) { return $v; } } echo f();", "9");
        check("parent updates reach existing descendants", """
            function child() { sleep_ms(2); echo context_get('key'); context_set('key', 'C'); }
            context_set('key', 'P'); $task = spawn('child'); context_set('key', 'Q'); await($task); echo context_get('key');
            """, "QQ");
        check("null keys differ from append", "$a = [null => 2, 3]; $a[null] = 4; $a[] = 5; echo $a[''], ':', $a[0], ':', $a[1];", "4:3:5");
        language();
        namedArguments();
        synchronization();
        nativePoolsAndLibraries();
        hostedReactor();
        boundedWorkers();
        cpuDeadline();
        objectCycles();
        isolationAndHost();
        includesAndReload();
        nativeCall();
        if (Files.exists(Path.of("build/graalphp-native.dll"))) nativeBundle();
        readFile();
        rejection();
        System.out.println("PASS: " + scenarios + " integration scenarios (Truffle, async, reload, threads, NFI, host)");
    }
    private static void language() {
        check("object identity, inheritance and late static binding", """
            class Base {
                private $value = 3;
                public static $n = 1;
                public function value() { return $this->value; }
                public static function number() { return static::$n; }
            }
            class Child extends Base {
                private $value = 8;
                public static $n = 9;
                public function childValue() { return $this->value; }
            }
            $a = new Child; $b = $a; $b->extra = [7];
            echo $a->value(), ':', $b->childValue(), ':', Child::number(), ':', $a->extra[0];
            """, "3:8:9:7");
        check("constructors, methods and closures suspend", """
            class Counter {
                public $n;
                public function __construct($n) { sleep_ms(1); $this->n = $n; }
                public function add($n = 2) { sleep_ms(1); return $this->n += $n; }
                public function callback() { return fn($n) => $this->add($n); }
            }
            $counter = new Counter(10); $callback = $counter->callback();
            $future = spawn($callback, 4); unset($callback); echo await($future), ':', $counter->n;
            """, "14:14");
        check("closure captures survive outer activation and remain isolated", """
            function factory() {
                $value = [2]; $n = 1;
                return function() use ($value, &$n) {
                    $value[0]++; sleep_ms(1); return [$value[0], ++$n];
                };
            }
            $f = factory(); $a = $f(); $b = $f(); echo $a[0], ':', $a[1], ':', $b[0], ':', $b[1];
            """, "3:2:3:3");
        check("deferred append and compound destination evaluated once", """
            function index(&$n) { sleep_ms(1); return $n++; }
            $a = []; $a[] = count($a); $a[] = count($a);
            $n = 0; $a[index($n)] += 4; echo $a[0], ':', $a[1], ':', $n;
            """, "4:1:1");
        check("for continue updates and do while", """
            $sum = 0; for ($i = 0; $i < 5; $i++) { if ($i == 1) continue; $sum += $i; }
            do { $sum--; if ($sum == 8) continue; } while ($sum > 6); echo $sum, ':', $i;
            """, "6:5");
        check("coalesce assignment and isset short circuit", """
            function side(&$n) { $n++; return 3; }
            $n = 0; $a['x'] ??= side($n); $a['x'] ??= side($n);
            echo $a['x'], ':', $n, ':', ($absent['x'] ?? 9), ':', isset($a['x']), ':', empty($a['z']);
            """, "3:1:9:1:1");
        check("namespaces aliases and callable arrays", """
            namespace App;
            use App\\Box as Alias;
            class Box { public function get($n = 4): int { return $n; } }
            function add(int ...$values): int { $sum = 0; foreach ($values as $v) $sum += $v; return $sum; }
            $box = new Alias; $callable = [$box, 'get']; echo $callable(), ':', add(1, 2, 3);
            """, "4:6");
        check("typed arguments defaults and return failure", """
            function f(int $n = 4): string { return $n + 1; }
            function bad(): array { return 1; }
            echo f(), ':', f('6'); try { bad(); } catch (Throwable $e) { echo ':caught'; }
            """, "5:7:caught");
        check("private member access fails before mutation", """
            class Box { private $value = 5; public function get() { return $this->value; } }
            $b = new Box; try { $b->value = 9; } catch (Throwable $e) { echo 'private:'; } echo $b->get();
            """, "private:5");
        check("array COW retains object identity", """
            class Box { public $n = 1; }
            $a = [new Box]; $b = $a; $b[0]->n = 8; $b[] = 3; echo $a[0]->n, ':', count($a), ':', count($b);
            """, "8:1:2");
    }
    private static void check(String name, String script, String expected) {
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output).build()) { context.eval("php", script); }
        equal(name, expected, output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void equal(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(name + " expected [" + expected + "] got [" + actual + "]");
        scenarios++;
    }
    private static void isolationAndHost() {
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output).build()) {
            context.getPolyglotBindings().putMember("twice", (ProxyExecutable) values -> values[0].asLong() * 2);
            context.eval("php", "$x = 99; echo host_call('twice', 21);");
            context.eval("php", "echo $x == null;");
        }
        equal("host direct call and request isolation", "421", output.toString());
    }
    private static void includesAndReload() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "reload-").toAbsolutePath();
        Path main = root.resolve("main.php"); Path library = root.resolve("library.php");
        Files.writeString(library, "<?php function version() { return 'old'; }");
        Files.writeString(main, "<?php include_once 'library.php'; include_once 'library.php'; host_call('gate'); echo version();");
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output)
                .environment("GRAALPHP_ROOT", root.toString()).environment("GRAALPHP_WATCH", "1").build()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            context.getPolyglotBindings().putMember("gate", (ProxyExecutable) values -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Gate timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return 0;
            });
            var source = Source.newBuilder("php", main.toFile()).build();
            var running = CompletableFuture.runAsync(() -> context.eval(source));
            if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("Request did not enter");
            try {
                Files.writeString(library, "<?php function version() { return 'new'; }");
                awaitVersion(context, "new");
                equal("reload compiles only changed unit", 1L, context.eval("php", "return reload_compiled_units();").asLong());
            } finally { release.countDown(); }
            running.get(10, TimeUnit.SECONDS);
            equal("old request pins code generation", "old", output.toString()); output.reset();
            context.eval(source);
            equal("next request gets new code", "new", output.toString()); output.reset();
            Files.writeString(library, "<?php function version( broken");
            long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!context.eval("php", "return reload_failed();").asBoolean() && System.nanoTime() < limit) Thread.sleep(20);
            equal("reload parse failure is observable", true, context.eval("php", "return reload_failed();").asBoolean());
            context.eval(source);
            equal("invalid edit keeps last complete generation", "new", output.toString());
            long previousGeneration = context.eval("php", "return generation_id();").asLong();
            Files.writeString(root.resolve("added.php"), "<?php function added() { return 7; }");
            Thread.sleep(150);
            equal("invalid pending edit prevents partial publication", previousGeneration,
                    context.eval("php", "return generation_id();").asLong());
            Files.writeString(library, "<?php function version() { return 'fixed'; }");
            awaitVersion(context, "fixed");
            equal("pending created file publishes with repaired batch", 7L,
                    context.eval("php", "include 'added.php'; return added();").asLong());
        }
        var localOutput = new ByteArrayOutputStream();
        Files.writeString(library, "<?php $x = 12; return 4;");
        Files.writeString(main, "<?php function f() { $x = 1; $y = include 'library.php'; return $x + $y; } echo f();");
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(localOutput).build()) {
            context.eval(Source.newBuilder("php", main.toFile()).build());
        }
        equal("include inherits function locals", "16", localOutput.toString());
    }
    private static void awaitVersion(Context context, String expected) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < limit) {
            var result = context.eval("php", "include 'library.php'; return version();");
            if (result.asString().equals(expected)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Watcher did not publish new code");
    }
    private static void nativeCall() {
        String library = System.getProperty("os.name").startsWith("Windows") ? "ucrtbase.dll" : System.getProperty("os.name").equals("Mac OS X") ? "/usr/lib/libSystem.B.dylib" : "libc.so.6";
        check("dynamic native signature", "echo ffi_call('" + library + "', 'abs', '(SINT32):SINT32', -42);", "42");
        check("native offload", "$f = ffi_call_async('" + library + "', 'abs', '(SINT32):SINT32', -41); echo await($f);", "41");
        check("cdef scalar declarations and invocation flags", """
            $ffi = FFI::cdef('typedef int signed_value; signed_value abs(signed_value value);', '%s');
            echo get_class($ffi), ':', $ffi->abs(-40), ':', $ffi->abs(value: -41, async: true, pool: 'math'), ':';
            echo $ffi->abs(-42, async: false);
            """.formatted(library), "FFI:40:41:42");
        check("unsupported C declarations and FFI options fail explicitly", """
            try { FFI::cdef('struct Item { int x; };', '%s'); } catch (FFI\\ParserException $e) { echo 'T'; }
            $ffi = FFI::cdef('int abs(int value);', '%s');
            try { $ffi->abs(-1, async: 1); } catch (TypeError $e) { echo 'A'; }
            try { $ffi->abs(-1, async: true, pool: ''); } catch (ValueError $e) { echo 'P'; }
            try { $ffi->abs(unknown: -1); } catch (Error $e) { echo 'N'; }
            try { $ffi->abs(-1, -2); } catch (ArgumentCountError $e) { echo 'C'; }
            """.formatted(library, library), "TAPNC");
    }
    private static void rejection() {
        try (var context = Context.newBuilder("php").allowAllAccess(true).build()) {
            try { context.eval("php", "trait Unsupported {}"); throw new AssertionError("Unsupported syntax accepted"); }
            catch (PolyglotException error) { if (error.isInternalError()) throw error; scenarios++; }
        }
    }
    private static void nativeBundle() {
        check("native bundle and C callback", """
            function twice($n) { return $n * 2; }
            $lib = 'build/graalphp-native.dll';
            echo ffi_call($lib, 'gp_callback', '((SINT64):SINT64,SINT64):SINT64', ffi_callback('twice'), 21), ':';
            echo ffi_call($lib, 'gp_regex_match', '(STRING,STRING):SINT32', '^a+$', 'aaa'), ':';
            echo ffi_call($lib, 'gp_crc32', '(STRING):SINT64', 'hello');
            """, "42:1:907060870");
        check("callback suspension rejects with finally", """
            function callback($n) { try { sleep_ms(1); return 1; } finally { echo 'F'; } }
            try { ffi_call('build/graalphp-native.dll', 'gp_callback', '((SINT64):SINT64,SINT64):SINT64', ffi_callback('callback'), 1); }
            catch (Throwable $error) { echo 'C'; }
            """, "FC");
        check("callback dispatched from offload worker and foreign native thread", """
            $calls = 0;
            $callback = ffi_callback(function($n) use (&$calls) { $calls++; return $n * 2; });
            $lib = 'build/graalphp-native.dll';
            $signature = '((SINT64):SINT64,SINT64):SINT64';
            echo await(ffi_call_async($lib, 'gp_callback', $signature, $callback, 20)), ':';
            echo await(ffi_call_async($lib, 'gp_callback_thread', '(ENV,(ENV,SINT64):SINT64,SINT64):SINT64', $callback, 21)), ':', $calls;
            """, "40:42:2");
        check("cdef callback typedef, string and native library bindings", """
            $ffi = FFI::cdef('typedef int64_t (*transform)(int64_t value);
                int64_t gp_callback(transform function, int64_t value);
                int gp_regex_match(const char *pattern, const char *text);
                int64_t gp_crc32(const char *text);', 'build/graalphp-native.dll');
            $calls = 0;
            echo $ffi->gp_callback(function($n) use (&$calls) { $calls++; return $n * 2; }, 21, async: true, pool: 'callbacks'), ':';
            echo $ffi->gp_regex_match('^a+$', 'aaa'), ':', $ffi->gp_crc32('hello'), ':', $calls;
            """, "42:1:907060870:1");
        check("named pools reuse idle workers and separate names", """
            $ffi = FFI::cdef('int64_t gp_thread_id(void);', 'build/graalphp-native.dll');
            $main = $ffi->gp_thread_id();
            $a = $ffi->gp_thread_id(async: true, pool: 'alpha');
            $b = $ffi->gp_thread_id(async: true, pool: 'beta');
            echo $main !== $a, ':', $a !== $b, ':';
            $same = true;
            for ($i = 0; $i < 12; $i++) $same = $same && $a === $ffi->gp_thread_id(async: true, pool: 'alpha');
            echo $same;
            """, "1:1:1");
        check("native async allows coroutine progress and cancellation cleanup", """
            $ffi = FFI::cdef('int64_t gp_sleep_echo(unsigned int ms, int64_t value);', 'build/graalphp-native.dll');
            $started = false;
            $task = Async\\spawn(function() use ($ffi, &$started) {
                $started = true;
                try { return $ffi->gp_sleep_echo(80, 42, async: true, pool: 'blocking'); }
                finally { echo 'F'; }
            });
            while (!$started) Async\\suspend();
            echo 'P'; $task->cancel();
            try { Async\\await($task); } catch (Async\\AsyncCancellation $error) { echo 'C'; }
            echo $ffi->gp_sleep_echo(1, 9, async: true, pool: 'blocking');
            """, "PFC9");
    }

    private static void namedArguments() {
        check("named defaults, references, variadics and evaluation order", """
            function mark(&$log, $text) { $log .= $text; return $text; }
            function f($a = 'a', $b = 'b', ...$extra) { return $a . $b . $extra['tail']; }
            $log = ''; echo f(b: mark($log, 'B'), a: mark($log, 'A'), tail: 'T'), ':', $log, ':';
            function update($unused = 0, &$value = null) { $value++; }
            $n = 4; update(value: $n); echo $n, ':', f(tail: 't');
            """, "ABT:BA:5:abt");
        check("named methods and closure through suspension", """
            class Box {
                public $value;
                public function __construct($unused = 1, $value = 2) { $this->value = $value; }
                public function sum($a = 3, $b = 4) { Async\\delay(ms: 1); return $this->value + $a + $b; }
            }
            $box = new Box(value: 10); echo $box->sum(b: 8), ':';
            $f = fn($a = 1, $b = 2) => $a + $b; echo $f(b: 7);
            """, "21:8");
        check("named duplicate and unknown parameters", """
            function f($a) { return $a; }
            try { f(1, a: 2); } catch (Error $error) { echo 'D'; }
            try { f(b: 2); } catch (Error $error) { echo 'N'; }
            """, "DN");
    }

    private static void synchronization() throws Exception {
        check("mutex coroutine identity and synchronized finally", """
            $mutex = new Async\\Mutex; $total = 0; $tasks = [];
            for ($i = 0; $i < 8; $i++) $tasks[] = Async\\spawn(function() use ($mutex, &$total) {
                $mutex->synchronized(function() use (&$total) { $old = $total; Async\\delay(1); $total = $old + 1; });
            });
            Async\\await_all_or_fail($tasks); echo $total, ':';
            try { $mutex->synchronized(function() { throw new Exception('fail'); }); } catch (Throwable $error) {}
            echo $mutex->tryLock(); $mutex->unlock();
            """, "8:1");
        check("mutex reservation cancelled after grant does not leak", """
            $mutex = new Async\\Mutex; $mutex->lock();
            $waiting = false;
            $task = Async\\spawn(function() use ($mutex, &$waiting) { $waiting = true; $mutex->lock(); try { echo 'bad'; } finally { $mutex->unlock(); } });
            while (!$waiting) Async\\suspend();
            $mutex->unlock(); $task->cancel();
            try { Async\\await($task); } catch (Async\\AsyncCancellation $error) { echo 'C'; }
            echo $mutex->tryLock(); $mutex->unlock();
            """, "C1");
        check("mutex timeout and wrong owner preserve holder", """
            $mutex = new Async\\Mutex; $mutex->lock();
            $task = Async\\spawn(function() use ($mutex) {
                try { $mutex->unlock(); } catch (Throwable $error) { echo 'O'; }
                try { $mutex->lock(Async\\timeout(1)); } catch (Async\\OperationCanceledException $error) { echo 'T'; }
            });
            Async\\await($task); echo $mutex->isLocked(); $mutex->unlock(); echo $mutex->tryLock(); $mutex->unlock();
            """, "OT11");
        check("thread channel cross-worker delivery and bounded backpressure", """
            function producer($channel) { for ($i = 1; $i <= 12; $i++) $channel->send($i); $channel->close(); return 12; }
            $channel = new Async\\ThreadChannel(1); $worker = parallel('producer', $channel);
            $sum = 0;
            try { while (true) $sum += $channel->recv(); } catch (Async\\ThreadChannelException $error) {}
            echo $sum, ':', await($worker), ':', $channel->isClosed();
            """, "78:12:1");
        check("thread channel rejects unpromoted graph and cancels pending send", """
            $channel = new Async\\ThreadChannel(1);
            try { $channel->send([1]); } catch (TypeError $error) { echo 'G'; }
            $channel->send(4);
            try { $channel->send(9, Async\\timeout(1)); } catch (Async\\OperationCanceledException $error) { echo 'C'; }
            $channel->close(); echo $channel->recv(), ':', $channel->count();
            """, "GC4:0");
        var mutex = new graalphp.runtime.AsyncMutex();
        int[] count = {0};
        var threads = new java.util.ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 4; i++) threads.add(CompletableFuture.runAsync(() -> {
            Object owner = new Object();
            for (int j = 0; j < 500; j++) {
                var acquired = mutex.acquire(owner);
                acquired.completion.join(); mutex.accept(owner, acquired);
                count[0]++;
                mutex.unlock(owner);
            }
        }));
        CompletableFuture.allOf(threads.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        equal("mutex protects mutable data across physical threads", 2000, count[0]);
    }
    private static void boundedWorkers() {
        var threads = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output).environment("GRAALPHP_WORKERS", "2").build()) {
            context.getPolyglotBindings().putMember("worker", (ProxyExecutable) values -> {
                threads.add(Thread.currentThread().threadId());
                return 1;
            });
            context.eval("php", """
                function work() { sleep_ms(2); return host_call('worker'); }
                $tasks = [];
                for ($i = 0; $i < 12; $i++) $tasks[] = parallel('work');
                $sum = 0; foreach ($tasks as $task) $sum += await($task);
                echo $sum;
                """);
        }
        equal("bounded workers finish queued jobs", "12", output.toString());
        equal("bounded workers reuse exactly two threads", 2, threads.size());
    }

    private static void nativePoolsAndLibraries() {
        check("detached scope drains external completions after the root returns", """
            $scope = new Async\\Scope();
            $scope->spawn(function() {
                $ffi = FFI::cdef('int64_t gp_sleep_echo(unsigned int ms, int64_t value);', 'builtin:runtime');
                $jobs = [];
                for ($i = 0; $i < 24; $i++) $jobs[] = Async\\spawn(function() use ($ffi, $i) {
                    return $ffi->gp_sleep_echo(1, $i, async: true);
                });
                $sum = 0; foreach ($jobs as $job) $sum += Async\\await($job);
                echo $sum;
            });
            echo 'root:';
            """, "root:276");
        check("explicit pool limits, warm minimum and immutable configuration", """
            FFI::definePool('serial', min: 1, max: 1, queueCapacity: 8);
            FFI::definePool('warm', min: 2, max: 3);
            FFI::definePool('lazy', min: 0, max: 2);
            echo FFI::poolSize('serial'), ':', FFI::poolSize('warm'), ':', FFI::poolSize('lazy'), ':';
            FFI::definePool('serial', min: 1, max: 1, queueCapacity: 8);
            try { FFI::definePool('serial', max: 2); } catch (ValueError $error) { echo 'fixed'; }
            try { FFI::definePool('invalid', min: 3, max: 1); } catch (ValueError $error) { echo ':invalid'; }
            """, "1:2:0:fixed:invalid");
        check("single-worker C pool serializes concurrent calls on one thread", """
            FFI::definePool('serial', min: 0, max: 1);
            $ffi = FFI::cdef('int64_t gp_sleep_echo(unsigned int ms, int64_t value); int64_t gp_thread_id(void);', 'builtin:runtime');
            $tasks = []; $order = []; $threads = [];
            for ($i = 0; $i < 8; $i++) $tasks[] = Async\\spawn(function() use ($ffi, &$order, &$threads, $i) {
                $order[] = $ffi->gp_sleep_echo(2, $i, async: true, pool: 'serial');
                $threads[] = $ffi->gp_thread_id(async: true, pool: 'serial');
            });
            Async\\await_all_or_fail($tasks);
            $same = true;
            for ($i = 0; $i < 8; $i++) $same = $same && $order[$i] === $i && $threads[$i] === $threads[0];
            echo $same, ':', FFI::poolSize('serial');
            """, "1:1");
        check("static library catalog resolves real SQLite zlib PCRE2 and libuv", """
            $db = FFI::cdef('int sqlite3_libversion_number(void); int gp_sqlite_scalar(const char *sql, int64_t (*result)(int64_t));', 'builtin:sqlite3');
            $z = FFI::cdef('int64_t gp_crc32(const char *text);', 'builtin:zlib');
            $regex = FFI::cdef('int gp_regex_match(const char *pattern, const char *text);', 'builtin:pcre2');
            $uv = FFI::cdef('unsigned int uv_version(void);', 'builtin:libuv');
            $answer = 0;
            FFI::definePool('sqlite', max: 1);
            $db->gp_sqlite_scalar('select 6 * 7', function($n) use (&$answer) { $answer = $n; return $n; }, async: true, pool: 'sqlite');
            echo $db->sqlite3_libversion_number(), ':', $answer, ':', $z->gp_crc32('hello'), ':', $regex->gp_regex_match('^a+$', 'aaa'), ':', $uv->uv_version();
            """, "3053002:42:907060870:1:78849");
    }

    private static void hostedReactor() throws Exception {
        var loop = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var scheduledDelays = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var output = new ByteArrayOutputStream();
        var uiSawPending = new CompletableFuture<Boolean>();
        var driver = new EmbeddedPhp.Driver() {
            @Override public void post(Runnable work) { loop.execute(work); }
            @Override public EmbeddedPhp.Alarm schedule(long delay, Runnable work) {
                scheduledDelays.add(delay);
                var future = loop.schedule(work, delay, TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            }
        };
        try {
            var session = loop.submit(() -> {
                var builder = Context.newBuilder("php").allowAllAccess(true).out(output);
                builder.environment("GRAALPHP_REACTOR", "libuv");
                String source = """
                    $sum = 0; for ($i = 0; $i < 20000; $i++) $sum += $i;
                    Async\\delay(80);
                    FFI::definePool('serial', max: 1);
                    $ffi = FFI::cdef('int64_t gp_sleep_echo(unsigned int ms, int64_t value);', 'builtin:runtime');
                    echo $sum, ':', $ffi->gp_sleep_echo(5, 7, async: true, pool: 'serial');
                    return 42;
                    """;
                var php = new EmbeddedPhp(builder, Source.create("php", source), driver);
                loop.execute(() -> uiSawPending.complete(!php.completion.isDone()));
                return php;
            }).get(10, TimeUnit.SECONDS);
            equal("host loop gets control during CPU-only PHP", true, uiSawPending.get(10, TimeUnit.SECONDS));
            equal("hosted PHP returns result", 42, ((Number) session.completion.get(15, TimeUnit.SECONDS)).intValue());
            equal("hosted PHP output", "199990000:7", output.toString());
            equal("libuv wakes host without a polling timer", true,
                    !scheduledDelays.isEmpty() && scheduledDelays.stream().allMatch(delay -> delay > TimeUnit.SECONDS.toNanos(10)));
            int port;
            try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            var server = loop.submit(() -> new EmbeddedPhp(Context.newBuilder("php").allowAllAccess(true), Source.create("php", """
                $config = (new TrueAsync\\HttpServerConfig())->addListener('127.0.0.1', %d);
                $server = new TrueAsync\\HttpServer($config);
                $server->addHttpHandler(function($request, $response) use ($server) {
                    Async\\delay(2);
                    $response->setBody('host-socket-ready');
                    $server->stop();
                });
                $server->start(); return 42;
                """.formatted(port)), driver)).get(10, TimeUnit.SECONDS);
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/"))
                    .timeout(java.time.Duration.ofSeconds(5)).build();
            String reply = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (reply == null && System.nanoTime() < deadline) {
                try { reply = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body(); }
                catch (java.io.IOException notListeningYet) {
                    if (server.completion.isDone()) server.completion.join();
                    Thread.sleep(10);
                }
            }
            equal("host backend readiness dispatches TCP and shorter timer on the owner", "host-socket-ready", reply);
            equal("host socket loop drains on shutdown", 42, ((Number) server.completion.get(10, TimeUnit.SECONDS)).intValue());
        } finally { loop.shutdownNow(); }
        var nativeOutput = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(nativeOutput).environment("GRAALPHP_REACTOR", "libuv").build()) {
            context.getPolyglotBindings().putMember("force_gc", (org.graalvm.polyglot.proxy.ProxyExecutable) values -> { System.gc(); return null; });
            context.getPolyglotBindings().putMember("inspect_reactor", (org.graalvm.polyglot.proxy.ProxyExecutable) values -> {
                equal("CLI has no dedicated libuv or backend-poller thread", false,
                        Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.getName().startsWith("graalphp-libuv")));
                return null;
            });
            context.eval("php", """
                $task = Async\\spawn(function() { try { Async\\delay(10000); } finally { echo 'F'; } });
                Async\\delay(2); $task->cancel();
                try { Async\\await($task); } catch (Async\\AsyncCancellation $error) { echo 'C'; }
                host_call('force_gc');
                Async\\delay(1); echo 'R';
                host_call('inspect_reactor');
                $long = Async\\spawn(function() { Async\\delay(10000); });
                Async\\delay(1);
                $ffi = FFI::cdef('int64_t gp_sleep_echo(unsigned int ms, int64_t value);', 'builtin:runtime');
                echo $ffi->gp_sleep_echo(10, 7, async: true);
                $long->cancel();
                try { Async\\await($long); } catch (Async\\AsyncCancellation $error) {}
                """);
        }
        equal("native reactor cancellation, external wakeup and shutdown drain", "FCR7", nativeOutput.toString());
    }
    private static void objectCycles() {
        var heap = new graalphp.runtime.PhpValues.Heap();
        try (var scope = new graalphp.runtime.PhpValues.Scope(heap)) {
            var object = new graalphp.runtime.PhpValues.PhpObject(heap, "test");
            scope.variable(object);
            var array = scope.variable(scope.array(object));
            object.field("self").set(object);
            object.field("array").set(array.read());
        }
        equal("object/array cycles reclaimed", 0L, heap.liveObjects);
        equal("arrays in object cycles reclaimed", 0L, graalphp.runtime.PhpValues.statistics(heap).liveArrays());
    }
    private static void cpuDeadline() {
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output).environment("GRAALPHP_TIMEOUT_MS", "25").build()) {
            try {
                context.eval("php", "try { while (true) {} } finally { echo 'cleanup'; }");
                throw new AssertionError("CPU deadline did not interrupt loop");
            } catch (PolyglotException error) { if (error.isInternalError()) throw error; }
        }
        equal("CPU deadline polls unwind finally and preserve output", "cleanup", output.toString());
        output.reset();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output).environment("GRAALPHP_TIMEOUT_MS", "25").build()) {
            try {
                context.eval("php", """
                    Async\\protect(function() {
                        try { while (true) {} } finally { echo 'protected cleanup'; }
                    });
                    """);
                throw new AssertionError("protect disabled the request CPU deadline");
            } catch (PolyglotException error) { if (error.isInternalError()) throw error; }
        }
        equal("protect preserves the host CPU deadline", "protected cleanup", output.toString());
    }
    private static void readFile() throws Exception {
        Path file = Files.createTempFile(Path.of("build"), "async-", ".txt");
        Files.writeString(file, "content");
        check("blocking file offload", "$task = read_file_async('" + file.toString().replace('\\', '/') + "'); echo await($task);", "content");
    }
}
