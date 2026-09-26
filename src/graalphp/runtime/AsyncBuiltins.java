package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;
import graalphp.truffle.PhpCompiler;
import graalphp.truffle.PhpContext;
import static graalphp.runtime.Execution.*;

/** Resumable library algorithms use the same bytecode and ownership rules as guest functions. */
public final class AsyncBuiltins {
    private AsyncBuiltins() {}
    private static final String SOURCE = """
        function map($source, $callback) { return $callback(Async\\await($source)); }
        function catch_error($source, $callback) {
            try { return Async\\await($source); } catch (Throwable $error) { return $callback($error); }
        }
        function finally_run($source, $callback) {
            $error = null;
            try { $value = Async\\await($source); } catch (Throwable $caught) { $error = $caught; }
            try { $callback(); } catch (Throwable $caught) { throw __chain_previous($caught, $error); }
            if ($error !== null) throw $error;
            return $value;
        }
        function coroutine_finally($source, $callback) {
            try { Async\\await($source); } catch (Throwable $ignored) {}
            $callback($source);
        }
        function protected_call($callback) {
            __protect_enter();
            try { return $callback(); } finally { __protect_leave(); }
        }
        function mutex_lock($mutex, $cancellation = null) {
            $waiter = null; $accepted = false;
            try {
                $waiter = __mutex_wait($mutex);
                Async\\await($waiter, $cancellation);
                __mutex_accept($mutex, $waiter);
                $accepted = true;
            } finally {
                if (!$accepted && $waiter !== null) __mutex_abort($mutex, $waiter);
            }
        }
        function mutex_synchronized($mutex, $callback) {
            $mutex->lock();
            try { return $callback(); } finally { $mutex->unlock(); }
        }
        """;

    @TruffleBoundary
    public static java.util.Map<String, Function> compile(PhpContext context) {
        return PhpCompiler.compile(context.language, Source.newBuilder("php", SOURCE + ReflectionApi.SOURCE + IterationApi.SOURCE + ClassLoading.SOURCE + NativeInvocation.SOURCE + NetworkApi.SOURCE + CurlMultiApi.SOURCE + SqliteApi.SOURCE, "<async-library>").build()).functions();
    }

    @TruffleBoundary
    public static Object chain(Activation caller, Scheduler.Future source, Object callback, String operation) {
        var function = caller.request.context.asyncFunction(operation);
        source.observed = true;
        var args = new Argument[] {new Argument(source, null), new Argument(PhpValues.own(callback), null)};
        var coroutine = caller.request.scheduler.spawn(new ObjectModel.Invocation(function, null, null, null),
                args, caller.task.context.branch(), false, caller.task.scope);
        if (operation.equals("coroutine_finally")) return null;
        coroutine.observed = true;
        var result = new Scheduler.Future();
        result.cancelAction = () -> caller.request.scheduler.cancel(coroutine);
        coroutine.completion.whenComplete((value, error) -> {
            if (error == null) result.completion.complete(PhpValues.own(PhpValues.unwrap(value)));
            else result.completion.completeExceptionally(error);
        });
        caller.request.scheduler.keep(result);
        return result;
    }
}
