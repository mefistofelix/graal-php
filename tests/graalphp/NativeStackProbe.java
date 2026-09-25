package graalphp;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/** Explicit bridge protocol probe, not yet a transparent FFI implementation. */
public final class NativeStackProbe {
    public static void main(String[] arguments) {
        String library = Path.of(arguments[0]).toAbsolutePath().toString();
        var output = new ByteArrayOutputStream();
        var checkpoints = new AtomicInteger();
        long owner = Thread.currentThread().threadId();
        try (var context = Context.newBuilder("php").allowAllAccess(true).out(output)
                .environment("STACK_PROBE_LIBRARY", library).environment("GRAALPHP_REACTOR", "libuv").build()) {
            context.getPolyglotBindings().putMember("checkpoint", (ProxyExecutable) values -> {
                if (Thread.currentThread().threadId() != owner) throw new AssertionError("PHP left its owner thread");
                System.gc();
                checkpoints.incrementAndGet();
                return 0;
            });
            context.eval("php", """
                $ffi = FFI::cdef('
                    int64_t gp_stack_probe_create(int64_t seed, int depth);
                    int gp_stack_probe_step(int64_t handle, int64_t reply);
                    int64_t gp_stack_probe_value(int64_t handle);
                    int gp_stack_probe_close(int64_t handle);', getenv('STACK_PROBE_LIBRARY'));
                function native_call($ffi, $seed, $depth, $nested) {
                    $handle = $ffi->gp_stack_probe_create($seed, $depth);
                    graal_assert($handle !== 0);
                    $status = $ffi->gp_stack_probe_step($handle, 0);
                    $marker = [$seed, 'kept across native suspension'];
                    $calls = 0;
                    while ($status === 1) {
                        $value = $ffi->gp_stack_probe_value($handle);
                        host_call('checkpoint');
                        Async\\delay(1);
                        $reply = $nested ? native_call($ffi, $value, 8, false) : $value * 2;
                        graal_assert($marker[0] === $seed);
                        $status = $ffi->gp_stack_probe_step($handle, $reply);
                        $calls++;
                    }
                    graal_assert($status === 2 && $calls === 3);
                    $result = $ffi->gp_stack_probe_value($handle);
                    graal_assert($ffi->gp_stack_probe_close($handle) === 0);
                    return $result;
                }
                $jobs = [];
                for ($i = 1; $i <= 8; $i++) $jobs[] = Async\\spawn(function() use ($ffi, $i) {
                    return native_call($ffi, $i, 32, false);
                });
                $sum = 0; foreach ($jobs as $job) $sum += Async\\await($job);
                echo $sum, ':', native_call($ffi, 10, 32, true);
                """);
        }
        if (!output.toString().equals("520:272") || checkpoints.get() != 36)
            throw new AssertionError(output + "; checkpoints=" + checkpoints);
        System.out.println("PASS: 12 C stacks, 36 callback suspensions and forced GC requests, nested PHP/C calls, same OS thread; result=" + output);
    }
}
