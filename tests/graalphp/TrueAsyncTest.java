package graalphp;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Identical PHP scripts run against the pinned upstream binary and this Truffle runtime. */
public final class TrueAsyncTest {
    private record Case(String name, String source) {}
    private static final List<Case> CASES = List.of(
        new Case("curl-multi-empty-reference", """
            $m = curl_multi_init(); $running = 99; $queued = 77;
            echo get_class($m), ':';
            echo curl_multi_exec(still_running: $running, multi_handle: $m), ':', $running, ':', curl_multi_select($m, 0), ':';
            echo curl_multi_info_read($m, $queued) === false, ':', $queued, ':', count(curl_multi_get_handles($m)), ':';
            echo curl_multi_errno($m), ':', curl_multi_strerror(CURLM_OK), ':', curl_multi_strerror(999) === null;
            """),
        new Case("curl-multi-membership", """
            $a = curl_multi_init(); $b = curl_multi_init(); $c = curl_init('unsupported-scheme://localhost/');
            echo curl_multi_add_handle($a, $c), ':', curl_multi_add_handle($a, $c), ':', curl_multi_errno($a), ':';
            echo curl_multi_add_handle($b, $c), ':', curl_multi_remove_handle($b, $c), ':';
            $handles = curl_multi_get_handles($a); echo $handles[0] === $c, ':';
            echo curl_multi_remove_handle($a, $c), ':', curl_multi_remove_handle($a, $c), ':';
            echo curl_multi_add_handle($b, $c), ':', count(curl_multi_get_handles($a)), ':', count(curl_multi_get_handles($b));
            """),
        new Case("curl-multi-results-and-reuse", """
            $m = curl_multi_init();
            $a = curl_init('unsupported-scheme://localhost/a'); $b = curl_init('unsupported-scheme://localhost/b');
            curl_setopt($a, CURLOPT_RETURNTRANSFER, true); curl_setopt($b, CURLOPT_RETURNTRANSFER, true);
            curl_multi_add_handle($m, $a); curl_multi_add_handle($m, $b);
            do { $code = curl_multi_exec($m, $running); if ($running) curl_multi_select($m); } while ($running);
            echo $code, ':', $running, ':';
            $count = 0; $queue = 99; $valid = true;
            while (($info = curl_multi_info_read($m, $queue)) !== false) {
                $valid = $valid && $info['msg'] === CURLMSG_DONE && $info['result'] === 1;
                $valid = $valid && ($info['handle'] === $a || $info['handle'] === $b) && curl_errno($info['handle']) === 1;
                $keys = ''; foreach ($info as $key => $value) $keys .= $key . ':';
                $valid = $valid && $keys === 'msg:result:handle:';
                $count++;
            }
            echo $valid, ':', $count, ':', $queue, ':', curl_multi_getcontent($a) === '', ':';
            echo count(curl_multi_get_handles($m)), ':', curl_multi_remove_handle($m, $a), ':';
            curl_multi_add_handle($m, $a); curl_multi_exec($m, $running);
            $info = curl_multi_info_read($m); echo $info['handle'] === $a, ':', $info['result'];
            """),
        new Case("curl-multi-close-and-ownership", """
            $m = curl_multi_init(); $alias = $m;
            $a = curl_init('unsupported-scheme://localhost/'); curl_multi_add_handle($m, $a);
            unset($a); $handles = curl_multi_get_handles($m); $a = $handles[0]; unset($handles);
            curl_multi_exec($m, $running); unset($m);
            echo curl_multi_info_read($alias)['handle'] === $a, ':';
            curl_multi_close($alias); echo count(curl_multi_get_handles($alias)), ':', curl_multi_info_read($alias) === false, ':';
            // Upstream 0.10.0 Windows crashes after close/re-add/destruction/reuse;
            // that sequence is covered independently by IntegrationTest and the network suite.
            """),
        new Case("curl-multi-options-validation", """
            $m = curl_multi_init();
            echo curl_multi_setopt($m, CURLMOPT_MAX_HOST_CONNECTIONS, 1), ':';
            echo curl_multi_setopt($m, CURLMOPT_MAX_TOTAL_CONNECTIONS, 2), ':';
            echo curl_multi_setopt($m, CURLMOPT_PIPELINING, CURLPIPE_MULTIPLEX), ':';
            echo curl_multi_setopt($m, CURLMOPT_MAX_CONCURRENT_STREAMS, 10), ':', curl_multi_errno($m), ':';
            try { curl_multi_select($m, -1); } catch (ValueError $e) { echo 'timeout:'; }
            try { curl_multi_setopt($m, 999, 1); } catch (ValueError $e) { echo 'option:', curl_multi_errno($m); }
            """),
        new Case("curl-immediate-error-alias", """
            $curl = curl_init('unsupported-scheme://localhost/');
            curl_setopt($curl, CURLOPT_RETURNTRANSFER, true);
            echo get_class($curl), ':', curl_exec($curl) === false, ':', curl_errno($curl), ':';
            $alias = $curl;
            unset($curl);
            echo curl_exec($alias) === false, ':', curl_errno($alias);
            unset($alias);
            """),
        new Case("named-arguments", """
            function f($a = 1, $b = 2, ...$extra) { Async\\delay(ms: 1); return $a + $b + $extra['tail']; }
            echo f(b: 4, tail: 8), ':';
            $a = Async\\Future::completed(2); $b = Async\\Future::completed(7);
            $results = Async\\await_all(triggers: ['a' => $a, 'b' => $b], fillNull: true);
            echo $results[0]['a'], ':', $results[0]['b'], ':', count($results[1]), ':';
            echo Async\\await(awaitable: $a), ':', Async\\protect(closure: fn() => 9);
            """),
        new Case("thread-channel-buffered", """
            $channel = new Async\\ThreadChannel(capacity: 1);
            $task = Async\\spawn(function() use ($channel) { $channel->send('a'); $channel->send('b'); $channel->close(); });
            echo $channel->capacity(), ':', $channel->recv(), ':', $channel->recv(), ':';
            Async\\await($task);
            try { $channel->recv(); } catch (Async\\ThreadChannelException $error) { echo 'closed'; }
            echo ':', $channel->count(), ':', $channel->isEmpty(), ':', $channel->isClosed();
            """),
        new Case("named-reference-variadic", """
            function change($ignored = 1, &...$values) { $values['n']++; }
            $n = 7; change(n: $n); echo $n, ':';
            function call($a = 2, $b = 3) { return $a * $b; }
            $callback = 'call'; echo $callback(b: 5);
            """),
        new Case("coroutine-result", """
            use function Async\\spawn;
            use function Async\\await;
            $task = spawn(function($n) { Async\\delay(1); return $n * 2; }, 21);
            echo get_class($task), ':', await($task), ':', $task->isCompleted(), ':', $task->getResult();
            """),
        new Case("capture-lifetime", """
            use function Async\\spawn;
            use function Async\\await;
            function create() {
                $array = [4]; $n = 2;
                return spawn(function() use ($array, &$n) { Async\\delay(1); $array[0]++; return [$array[0], ++$n]; });
            }
            $a = await(create()); echo $a[0], ':', $a[1];
            """),
        new Case("scope-context", """
            Async\\current_context()->set('key', 'root');
            $scope = Async\\Scope::inherit();
            $task = $scope->spawn(function() {
                $context = Async\\current_context();
                echo $context->find('key'), ':', $context->hasLocal('key'), ':';
                $context->set('key', 'child');
                $nested = Async\\spawn(fn() => Async\\current_context()->get('key'));
                return Async\\await($nested);
            });
            echo Async\\await($task), ':', Async\\current_context()->get('key');
            $scope->awaitCompletion(Async\\timeout(1000));
            """),
        new Case("detached-scope-context", """
            Async\\root_context()->set('key', 7);
            $scope = new Async\\Scope();
            $task = $scope->spawn(function() {
                echo Async\\current_context()->has('key'), ':', Async\\root_context()->get('key'), ':';
                return Async\\request_context() === null;
            });
            echo Async\\await($task);
            $scope->awaitCompletion(Async\\timeout(1000));
            """),
        new Case("coroutine-private-context", """
            Async\\coroutine_context()->set('private', 1);
            $task = Async\\spawn(function() {
                $context = Async\\coroutine_context();
                echo $context->has('private'), ':';
                $context->set('private', 2);
                return $context->get('private');
            });
            echo Async\\await($task), ':', Async\\coroutine_context()->get('private');
            """),
        new Case("scope-joins-descendants", """
            $scope = Async\\Scope::inherit(); $values = [];
            $scope->spawn(function() use (&$values) {
                Async\\spawn(function() use (&$values) { Async\\delay(2); $values[] = 8; });
                Async\\delay(1); $values[] = 4;
            });
            $scope->awaitCompletion(Async\\timeout(1000));
            echo count($values), ':', $values[0], ':', $values[1], ':', $scope->isFinished();
            """),
        new Case("detached-scope-reopens-after-descendants-finish", """
            $parent = new Async\\Scope(); $child = Async\\Scope::inherit($parent);
            for ($round = 0; $round < 3; $round++) {
                $gate = new Async\\Channel(1);
                $task = $child->spawn(function() use ($gate) { return $gate->recv(); });
                echo $parent->isFinished(), ':', $child->isFinished(), ':';
                $gate->send($round);
                $parent->awaitCompletion(Async\\timeout(1000));
                echo Async\\await($task), ':', $parent->isFinished(), ':', $child->isFinished(), ';';
            }
            """),
        new Case("detached-scope-keeps-request-alive", """
            $scope = new Async\\Scope();
            $scope->spawn(function() {
                Async\\delay(2);
                Async\\spawn(function() { Async\\delay(2); echo 'child'; });
                echo 'parent:';
            });
            echo 'root:';
            """),
        new Case("timeout-cancels-wait-only", """
            $task = Async\\spawn(function() { Async\\delay(20); return 9; });
            try { Async\\await($task, Async\\timeout(1)); }
            catch (Async\\OperationCanceledException $e) {
                echo get_class($e), ':', get_class($e->getPrevious()), ':';
            }
            echo Async\\await($task);
            """),
        new Case("cancel-unwinds-finally", """
            $task = Async\\spawn(function() {
                try { Async\\delay(1000); } finally { echo 'finally:'; }
            });
            Async\\delay(2); $task->cancel();
            try { Async\\await($task); } catch (Async\\AsyncCancellation $e) { echo 'cancelled'; }
            """),
        new Case("buffered-channel-close-drains", """
            $channel = new Async\\Channel(2);
            $channel->send([3]); $channel->send([4]); $channel->close();
            echo $channel->count(), ':', $channel->recv()[0], ':', $channel->recv()[0], ':';
            try { $channel->recv(); } catch (Async\\ChannelException $e) { echo 'closed'; }
            """),
        new Case("rendezvous-channel", """
            $channel = new Async\\Channel();
            $task = Async\\spawn(function() use ($channel) { $channel->send(6); return 7; });
            echo $channel->recv(), ':', Async\\await($task), ':', $channel->isEmpty();
            """),
        new Case("channel-timeout-removes-waiter", """
            $channel = new Async\\Channel(1);
            try { $channel->recv(Async\\timeout(1)); }
            catch (Async\\OperationCanceledException $e) { echo 'timeout:'; }
            echo $channel->sendAsync(8), ':', $channel->recv();
            """),
        new Case("future-array-cow", """
            $future = Async\\Future::completed([4, 5]);
            $a = $future->await(); $a[0] = 9;
            $b = Async\\await($future); echo $a[0], ':', $b[0], ':', $future->isCompleted();
            """),
        new Case("future-state", """
            $state = new Async\\FutureState(); $future = new Async\\Future($state);
            $task = Async\\spawn(function() use ($state) { Async\\delay(1); $state->complete([7]); });
            echo Async\\await($future)[0], ':', $state->isCompleted();
            Async\\await($task);
            """),
        new Case("future-error", """
            $future = Async\\Future::failed(new Exception('broken'));
            try { $future->await(); } catch (Throwable $e) { echo $e->getMessage(); }
            """),
        new Case("await-all-order-and-cow", """
            $slow = Async\\spawn(function() { Async\\delay(15); return [1]; });
            $fast = Async\\spawn(fn() => [2]);
            $sources = ['slow' => $slow, 'fast' => $fast];
            $results = Async\\await_all_or_fail($sources, null, false);
            foreach ($results as $key => $value) echo $key, ':', $value[0], ';';
            $results['slow'][0] = 7;
            $again = Async\\await_all_or_fail($sources);
            foreach ($again as $key => $value) echo $key, ':', $value[0], ';';
            """),
        new Case("await-errors-fill-null", """
            $sources = [
                'slow' => Async\\spawn(function() { Async\\delay(15); return 3; }),
                'bad' => Async\\Future::failed(new Exception('broken')),
                'fast' => Async\\Future::completed(null)
            ];
            $all = Async\\await_all($sources, null, true, true);
            echo count($all[0]), ':', count($all[1]), ':';
            foreach ($all[0] as $key => $value) echo $key, '=', $value ?? 'null', ';';
            echo $all[1]['bad']->getMessage(), ':';
            $all = Async\\await_all($sources);
            foreach ($all[0] as $key => $value) echo $key, ';';
            """),
        new Case("await-first-success-errors", """
            $slow = Async\\spawn(function() { Async\\delay(15); return [9]; });
            $result = Async\\await_first_success(['bad' => Async\\Future::failed(new Exception('first')), 'ok' => $slow]);
            echo $result[0][0], ':', $result[1]['bad']->getMessage(), ':';
            $result = Async\\await_first_success(['x' => Async\\Future::failed(new Exception('x')),
                'y' => Async\\Future::failed(new Exception('y'))]);
            echo $result[0] === null, ':', count($result[1]);
            """),
        new Case("await-count-successes", """
            $a = Async\\spawn(function() { Async\\delay(10); return 1; });
            $b = Async\\spawn(function() { Async\\delay(25); return 2; });
            $c = Async\\spawn(function() { Async\\delay(100); return 3; });
            $result = Async\\await_any_of(2, ['bad' => Async\\Future::failed(new Exception('bad')), 'a' => $a, 'b' => $b, 'c' => $c]);
            echo count($result[0]), ':', count($result[1]), ':', $result[0]['a'], ':', $result[0]['b'], ':';
            echo $c->isCompleted(), ':', Async\\await($c);
            """),
        new Case("await-race-keeps-other-tasks", """
            $slow = Async\\spawn(function() { Async\\delay(25); return 7; });
            $fast = Async\\spawn(function() { Async\\delay(2); return 4; });
            echo Async\\await_any_or_fail([$slow, $fast]), ':', $slow->isCancellationRequested(), ':', Async\\await($slow);
            """),
        new Case("await-fail-fast", """
            $slow = Async\\spawn(function() { Async\\delay(30); return 8; });
            $bad = Async\\spawn(function() { Async\\delay(2); throw new Exception('bad'); });
            try { Async\\await_all_or_fail([$slow, $bad]); }
            catch (Throwable $e) { echo $e->getMessage(), ':', $slow->isCompleted(), ':'; }
            echo Async\\await($slow);
            """),
        new Case("await-combinator-timeout", """
            $slow = Async\\spawn(function() { Async\\delay(30); return [8]; });
            try { Async\\await_all_or_fail([$slow], Async\\timeout(1)); }
            catch (Async\\OperationCanceledException $e) { echo get_class($e->getPrevious()), ':'; }
            echo Async\\await($slow)[0];
            """),
        new Case("await-empty-and-count-zero", """
            echo Async\\await_any_or_fail([]) === null, ':', count(Async\\await_all_or_fail([])), ':';
            $first = Async\\await_first_success([]); echo $first[0] === null, ':', count($first[1]), ':';
            $f = Async\\Future::completed(9);
            echo count(Async\\await_any_of_or_fail(0, [$f])), ':';
            $all = Async\\await_any_of(0, [$f]); echo $all[0][0], ':', count($all[1]), ':';
            echo count(Async\\await_any_of_or_fail(8, [$f]));
            """),
        new Case("await-duplicate-future", """
            $state = new Async\\FutureState(); $future = new Async\\Future($state);
            Async\\spawn(function() use ($state) { Async\\delay(2); $state->complete([6]); });
            $result = Async\\await_all_or_fail(['a' => $future, 'b' => $future]);
            $result['a'][0] = 10;
            echo $result['a'][0], ':', $result['b'][0], ':', Async\\await($future)[0];
            """),
        new Case("await-already-complete-no-yield", """
            $f = Async\\Future::completed([1]); $token = Async\\Future::completed(null);
            $token->ignore();
            Async\\spawn(function() { echo 'task:'; });
            echo Async\\await($f, $token)[0], ':', Async\\await($f, $f)[0], ':';
            echo Async\\await_all_or_fail([$f])[0][0], ':';
            """),
        new Case("await-pending-same-token", """
            $state = new Async\\FutureState(); $f = new Async\\Future($state);
            Async\\spawn(function() use ($state) { Async\\delay(2); $state->complete(5); });
            echo Async\\await($f, $f);
            """),
        new Case("protect-cancel-unwind", """
            $task = Async\\spawn(function() {
                try {
                    Async\\protect(function() {
                        echo 'enter:';
                        Async\\delay(25);
                        echo 'protected:';
                        return [9];
                    });
                    echo 'unreachable:';
                } catch (Async\\AsyncCancellation $e) { echo $e->getMessage(), ':'; }
                finally { echo 'finally:'; }
            });
            Async\\delay(2);
            $task->cancel(new Async\\AsyncCancellation('requested'));
            Async\\await($task);
            """),
        new Case("protect-context-and-return", """
            Async\\suspend();
            $id = Async\\current_coroutine(); $value = [1];
            $result = Async\\protect(function() use ($id, &$value) {
                Async\\delay(1); echo Async\\current_coroutine() === $id, ':';
                $value[0] = 4;
                return Async\\protect(fn() => $value);
            });
            $result[0] = 7;
            echo $result[0], ':', $value[0];
            """),
        new Case("cancel-reason-before-start", """
            $task = Async\\spawn(function() { echo 'unreachable'; });
            $task->cancel(new Async\\AsyncCancellation('custom'));
            try { Async\\await($task); } catch (Async\\AsyncCancellation $e) { echo $e->getMessage(); }
            """),
        new Case("context-duplicate-and-unset", """
            $context = Async\\current_context(); $context->set('n', null);
            echo $context->has('n'), ':', $context->find('n') === null, ':';
            try { $context->set('n', 2); } catch (Async\\AsyncException $e) { echo get_class($e), ':'; }
            $context->set('n', 3, true); echo $context->get('n'), ':';
            $context->unset('n'); echo $context->has('n');
            """)
    );

    public static void main(String[] arguments) throws Exception {
        Path oracle = Path.of(arguments.length == 0 ? "tools/trueasync-0.10.0/php.exe" : arguments[0]).toAbsolutePath();
        Path directory = Path.of("build/trueasync-cases").toAbsolutePath();
        Files.createDirectories(directory);
        int failures = 0;
        var report = new StringBuilder();
        var cases = new java.util.ArrayList<>(CASES);
        Path upstream = Path.of("build/reference/php-async-6acdd07ff500f5799ea83bbf333d686b5dacabbf/tests");
        if (!Files.isDirectory(upstream)) upstream = Path.of("build/reference/php-async/tests");
        for (String path : List.of("channel/001-channel_construct.phpt", "channel/002-channel_buffered_basic.phpt",
                "channel/007-channel_sendAsync.phpt", "future/001-map-basic.phpt", "future/002-map-chain.phpt",
                "future/004-map-propagates-error.phpt", "future/005-map-throws-exception.phpt",
                "future/007-catch-basic.phpt", "future/011-finally-on-success.phpt", "future/012-finally-on-error.phpt",
                "future/013-finally-throws-exception.phpt", "future/014-map-catch-finally-chain.phpt",
                "future/035-finally_exception_chain.phpt",
                "await/005-awaitAnyOrFail_basic.phpt", "await/006-awaitAnyOrFail_empty.phpt",
                "await/007-awaitAnyOrFail_exception.phpt", "await/008-awaitFirstSuccess_basic.phpt",
                "await/011-awaitAllOrFail_exception.phpt", "await/014-awaitAnyOfOrFail_basic.phpt",
                "await/016-awaitAnyOf_basic.phpt", "await/017-awaitAnyOf_all_success.phpt",
                "await/045-awaitAnyOfOrFail_edge_cases.phpt",
                "await/073-await_future_cancel_token_pre_completed.phpt",
                "await/074-await_future_cancel_token_completed_during.phpt",
                "await/075-await_future_cancel_token_pre_rejected.phpt",
                "await/076-await_future_cancel_token_rejected_during.phpt",
                "await/077-await_future_cancel_token_pre_rejected_cancellation.phpt",
                "await/078-await_future_cancel_token_rejected_cancellation_during.phpt",
                "await/079-await_completes_before_future_cancel_token.phpt",
                "await/084-awaitAnyOrFail_already_completed_returns_immediately.phpt",
                "await/093-awaitAnyOrFail_with_future_triggers.phpt",
                "protect/001-protect_basic.phpt", "protect/003-protect_nested.phpt",
                "protect/007-protect_exception_in_closure.phpt", "protect/008-protect_with_exception.phpt",
                "protect/009-protect_with_spawn.phpt", "protect/010-protect_with_await.phpt",
                "scope/022-scope_awaitCompletion_basic.phpt",
                "scope/041-scope_awaitCompletion_marks_future_used.phpt",
                "scope/044-scope_awaitAfterCancellation_not_cancelled.phpt",
                "scope/045-scope_awaitCompletion_self_deadlock.phpt",
                "scope/051-scope_awaitCompletion_deadlock_from_grandchild.phpt")) {
            String contents = Files.readString(upstream.resolve(path)).replace("\r\n", "\n");
            int start = contents.indexOf("--FILE--\n") + 9;
            int end = contents.indexOf("\n--EXPECT", start);
            if (start < 9 || end < 0) throw new AssertionError("Unsupported PHPT sections: " + path);
            cases.add(new Case("upstream-" + path.replace('/', '-').replace(".phpt", ""), contents.substring(start, end)));
        }
        for (var test : cases) {
            String source = test.source.startsWith("<?php") ? test.source : "<?php\n" + test.source;
            Path script = directory.resolve(test.name + ".php");
            Files.writeString(script, source);
            var oracleCommand = new java.util.ArrayList<>(List.of(oracle.toString(), "-n"));
            if (test.name.startsWith("curl-") && System.getProperty("os.name").startsWith("Windows")) {
                oracleCommand.addAll(List.of("-d", "extension_dir=" + oracle.getParent().resolve("ext"), "-d", "extension=curl"));
            }
            oracleCommand.add(script.toString());
            var process = new ProcessBuilder(oracleCommand).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Oracle timeout: " + test.name); }
            String expected = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            var output = new ByteArrayOutputStream();
            String error = "";
            if (arguments.length > 1) {
                var nativeProcess = new ProcessBuilder(Path.of(arguments[1]).toAbsolutePath().toString(), script.toString())
                        .redirectErrorStream(true).start();
                if (!nativeProcess.waitFor(15, TimeUnit.SECONDS)) {
                    nativeProcess.destroyForcibly();
                    throw new AssertionError("Native runtime timeout: " + test.name);
                }
                output.writeBytes(nativeProcess.getInputStream().readAllBytes());
                if (nativeProcess.exitValue() != 0) error = "Native exit " + nativeProcess.exitValue();
            } else {
                try (var context = Context.newBuilder("php").allowAllAccess(true).out(output).build()) {
                    context.eval(Source.newBuilder("php", source, test.name).build());
                } catch (Exception failure) { error = failure.toString(); }
            }
            String actual = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            boolean passed = process.exitValue() == 0 && error.isEmpty() && expected.equals(actual);
            report.append(passed ? "PASS " : "FAIL ").append(test.name).append('\n');
            if (!passed) {
                failures++;
                report.append("  upstream: ").append(expected).append("\n  graalphp: ").append(actual)
                        .append("\n  error: ").append(error).append('\n');
            }
        }
        Files.writeString(directory.resolve(arguments.length > 1 ? "native-results.txt" : "results.txt"), report);
        System.out.print(report);
        if (failures != 0) throw new AssertionError(failures + " TrueAsync differential cases failed");
        System.out.println("PASS: " + cases.size() + " differential scenarios against TrueAsync 0.10.0");
    }
}
