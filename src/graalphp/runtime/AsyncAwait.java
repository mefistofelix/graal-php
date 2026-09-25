package graalphp.runtime;

import java.util.ArrayList;
import java.util.List;
import static graalphp.runtime.Execution.*;

/** TrueAsync await combinators. All completions and ownership changes run on the request loop. */
public final class AsyncAwait implements AutoCloseable {
    private static final class Entry {
        final Object key;
        final Scheduler.Future source;
        Object value;
        PhpError error;
        boolean settled;
        boolean cleanup;
        Entry(Object key, Scheduler.Future source) { this.key = key; this.source = source; }
    }

    private final Request request;
    private final String operation;
    private final boolean ignoreErrors;
    private final boolean preserveOrder;
    private final boolean fillNull;
    private final long count;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> completed = new ArrayList<>();
    private final List<Scheduler.Listener> subscriptions = new ArrayList<>();
    private final Scheduler.Future future = new Scheduler.Future();
    private int successes;
    private PhpError failure;
    private boolean finishQueued;
    private boolean draining;
    private int remaining;
    private boolean closed;

    private AsyncAwait(Activation caller, String operation, Object sources, long count,
                       boolean preserveOrder, boolean fillNull) {
        request = caller.request;
        this.operation = operation;
        this.count = count;
        this.preserveOrder = preserveOrder;
        this.fillNull = fillNull || operation.equals("await_all_or_fail");
        ignoreErrors = operation.equals("await_all") || operation.equals("await_any_of")
                || operation.equals("await_first_success");
        future.cancelOnAbort = true;
        future.cancelAction = () -> {
            future.completion.completeExceptionally(new PhpError("Async\\AsyncCancellation", "Operation has been cancelled"));
            close();
        };
        request.scheduler.keep(future);
        request.resources.add(this);
        // Snapshot keys and awaitables before registering; the caller may mutate its COW array after yielding.
        for (var key : PhpValues.keys(sources)) {
            Object value = PhpValues.element(sources, key);
            try {
                Object source = PhpValues.unwrap(value);
                if (source != null) entries.add(new Entry(key, AsyncApi.future(source)));
            } finally { PhpValues.drop(value); }
        }
    }

    public static Object await(Activation caller, String operation, Argument[] args) {
        boolean counted = operation.startsWith("await_any_of");
        int offset = counted ? 1 : 0;
        if (args.length <= offset) throw new PhpError("ArgumentCountError", "Too few arguments for Async\\" + operation);
        Object sources = PhpValues.unwrap(args[offset].value());
        if (!(sources instanceof PhpValues.PhpArray)) {
            throw new PhpError("TypeError", "Await combinators currently require an array; Traversable is not implemented");
        }
        long count = counted ? Operations.number(args[0].value()).longValue()
                : operation.equals("await_any_or_fail") || operation.equals("await_first_success") ? 1 : 0;
        Scheduler.Future cancellation = args.length <= offset + 1 || args[offset + 1].value() == null ? null
                : AsyncApi.future(PhpValues.unwrap(args[offset + 1].value()));
        boolean preserve = args.length <= offset + 2 || Operations.truth(args[offset + 2].value());
        boolean fillNull = args.length > offset + 3 && Operations.truth(args[offset + 3].value());
        var operationState = new AsyncAwait(caller, operation, sources, count, preserve, fillNull);
        try {
            if (!operationState.entries.isEmpty() && !(counted && count == 0 && !operationState.ignoreErrors)
                    && cancellation != null && cancellation.completion.isDone()) {
                cancellation.observed = true;
                throw Scheduler.operationCancelled(cancellation);
            }
            operationState.start();
            return Scheduler.awaitValue(caller, operationState.future, cancellation);
        } catch (RuntimeException error) { operationState.close(); throw error; }
    }

    private boolean enough() {
        return failure != null || (count > 0 && (ignoreErrors ? successes : completed.size()) >= count)
                || completed.size() == entries.size();
    }

    private void start() {
        if (entries.isEmpty() || count == 0 && operation.equals("await_any_of_or_fail")) { finish(); return; }
        for (var entry : entries) {
            entry.source.observed = true;
            if (entry.source.completion.isDone()) {
                try { accept(entry, Scheduler.result(entry.source), null); }
                catch (PhpError error) { accept(entry, null, error); }
                if (enough()) { finish(); return; }
            } else subscriptions.add(request.scheduler.listen(entry.source, (value, error) -> {
                accept(entry, value, error);
                if (enough() && !finishQueued) {
                    finishQueued = true;
                    request.scheduler.enqueue(this::finish);
                }
            }));
        }
    }

    private void accept(Entry entry, Object value, PhpError error) {
        if (closed || entry.settled) return;
        entry.settled = true;
        entry.value = PhpValues.own(PhpValues.unwrap(value));
        entry.error = error;
        completed.add(entry);
        if (error == null) successes++;
        else if (!ignoreErrors && failure == null) failure = error;
    }

    private void finish() {
        if (closed) return;
        if (!draining && failure == null && (operation.equals("await_first_success") || operation.equals("await_any_of"))) {
            draining = true;
            for (var subscription : subscriptions) subscription.close();
            subscriptions.clear();
            var pending = entries.stream().filter(entry -> entry.source.task != null && !entry.source.completion.isDone()).toList();
            remaining = pending.size();
            // v0.10.0 drains remaining Coroutine triggers on these two APIs. It does not cancel them.
            for (var entry : pending) subscriptions.add(request.scheduler.listen(entry.source, (value, error) -> {
                if (closed) return;
                if (error != null) {
                    entry.settled = true;
                    entry.cleanup = true;
                    entry.error = error;
                    completed.add(entry);
                }
                if (--remaining == 0) finish();
            }));
            if (remaining != 0) return;
        }
        try {
            if (failure != null) future.completion.completeExceptionally(failure);
            else future.completion.complete(results());
        } finally { close(); }
    }

    private Object results() {
        if (operation.equals("await_any_or_fail")) return completed.isEmpty() ? null
                : PhpValues.own(PhpValues.unwrap(completed.getFirst().value));
        try (var storage = new PhpValues.Scope(request.heap)) {
            var results = storage.variable(storage.emptyArray());
            var errors = storage.variable(storage.emptyArray());
            // Error keys follow completion order. fillNull affects only results.
            for (var entry : completed) if (entry.error != null) errors.element(entry.key).set(entry.error);
            for (var entry : preserveOrder || fillNull ? entries : completed) {
                if (entry.settled && !entry.cleanup && entry.error == null) results.element(entry.key).set(PhpValues.unwrap(entry.value));
                else if (fillNull) results.element(entry.key).set(null);
            }
            if (!ignoreErrors) return PhpValues.own(results.read());
            Object first = results.read();
            if (operation.equals("await_first_success")) {
                first = null;
                for (var entry : completed) if (entry.error == null) { first = PhpValues.unwrap(entry.value); break; }
            }
            return PhpValues.own(storage.array(first, errors.read()));
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var subscription : subscriptions) subscription.close();
        for (var entry : completed) PhpValues.drop(entry.value);
        subscriptions.clear();
        completed.clear();
        entries.clear();
        future.cancelAction = null;
    }
}
