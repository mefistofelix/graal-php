package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import static graalphp.runtime.Execution.*;

/** The heap identifies dead objects. Guest execution happens later on the dispatcher. */
final class ObjectFinalizers {
    static final String SOURCE = """
        function drain_object_finalizers($scope = null) {
            while (($object = __object_next_finalizer()) !== null) {
                $deferred = __object_defer_finalizers();
                try { __object_run_finalizer($object, $scope); }
                finally {
                    unset($object);
                    __object_restore_finalizers($deferred);
                }
            }
        }
        function collect_object_cycles($scope) {
            $count = __object_collect_cycles();
            while (__object_finalizers_pending()) {
                __object_drain_finalizers($scope);
                $count += __object_collect_cycles();
            }
            return $count;
        }
        """;

    private final LinkedHashSet<PhpValues.PhpObject> live = new LinkedHashSet<>();
    private ArrayDeque<PhpValues.PhpObject> pending = new ArrayDeque<>();
    boolean ready;
    private boolean shuttingDown;

    void register(PhpValues.PhpObject object) { live.add(object); }

    boolean schedule(PhpValues.PhpObject object) {
        if (!live.remove(object)) return false;
        PhpValues.retain(object);
        pending.addLast(object);
        ready = true;
        return true;
    }

    Object take() {
        var object = pending.pollFirst();
        ready = !pending.isEmpty();
        if (object == null) return null;
        Object owner = PhpValues.own(object);
        PhpValues.release(object);
        return owner;
    }

    Batch defer(Activation owner) {
        if (pending.isEmpty()) return null;
        var batch = new Batch(owner, pending);
        owner.resources.add(batch);
        pending = new ArrayDeque<>();
        ready = false;
        return batch;
    }

    final class Batch implements TruffleObject, AutoCloseable {
        final Activation owner;
        private ArrayDeque<PhpValues.PhpObject> saved;
        Batch(Activation owner, ArrayDeque<PhpValues.PhpObject> saved) {
            this.owner = owner;
            this.saved = saved;
        }
        void restore() {
            close();
            owner.resources.remove(this);
        }
        @Override public void close() {
            if (saved == null) return;
            if (pending.isEmpty()) pending = saved;
            else pending.addAll(saved);
            saved = null;
            ready = true;
        }
    }

    void scheduleShutdown() {
        shuttingDown = true;
        while (!live.isEmpty()) schedule(live.getLast());
    }

    void discardPending() {
        live.clear();
        while (!pending.isEmpty()) PhpValues.release(pending.removeFirst());
        ready = false;
    }

    @TruffleBoundary
    Object invoke(Activation caller, PhpValues.PhpObject object, String scope, IndirectCallNode call) {
        var type = (ObjectModel.RuntimeClass) object.descriptor;
        var method = type.destructor;
        String owner = method.function().owner();
        if (!ObjectModel.canAccess(caller.request, scope, caller.request.type(owner), method.visibility())) {
            String message = "Call to " + method.visibility() + " " + owner + "::__destruct() from "
                    + (scope == null ? "global scope" : "scope " + scope);
            if (!shuttingDown) throw new PhpError("Error", message);
            new Diagnostics.Origin(caller.request, new Diagnostics.Site(null, -1, null, "Unknown", false))
                    .warning(message + " during shutdown ignored");
            return null;
        }
        return ObjectModel.invoke(caller, new ObjectModel.Invocation(method.function(), object, type, null),
                new Argument[0], call);
    }
}
