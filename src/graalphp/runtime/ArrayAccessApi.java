package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import static graalphp.runtime.Execution.*;

/** Dynamic PHP ArrayAccess dispatch without turning ordinary array locations into callbacks. */
public final class ArrayAccessApi {
    private ArrayAccessApi() {}

    /** Distinct from PHP null, which is a valid explicit array key. */
    public static final Object APPEND = new Object();

    @TruffleBoundary
    public static boolean implementsArrayAccess(Object value) {
        value = PhpValues.unwrap(value);
        return value instanceof PhpValues.PhpObject object
                && object.descriptor instanceof ObjectModel.RuntimeClass type
                && type.isA("ArrayAccess");
    }

    /** One evaluated offset expression. It owns temporary receiver/key values until closed. */
    public static final class Access implements AutoCloseable, TruffleObject {
        final Activation caller;
        final PhpValues.Location location;
        final Object source;
        final Object key;
        final boolean overloaded;
        boolean closed;

        private Access(Activation caller, PhpValues.Location location, Object source, Object key, boolean overloaded) {
            this.caller = caller;
            this.location = location;
            this.source = source;
            this.key = key;
            this.overloaded = overloaded;
        }

        public static Access create(Activation caller, Object source, Object key) {
            if (source instanceof PhpValues.Location parent) {
                Object raw = parent.readOrNull();
                if (implementsArrayAccess(raw)) {
                    return new Access(caller, null, PhpValues.own(raw), key, true);
                }
                var location = key == APPEND ? parent.deferredAppend() : parent.element(PhpValues.unwrap(key));
                return new Access(caller, location.at(caller), null, key, false);
            }
            Object raw = PhpValues.unwrap(source);
            return new Access(caller, null, source, key, implementsArrayAccess(raw));
        }

        public boolean directLocation() { return !overloaded && location != null; }
        public PhpValues.Location location() {
            if (!directLocation()) throw new PhpError("Error", "ArrayAccess offset is not a PHP variable");
            return location;
        }

        private Object receiver() {
            Object raw = PhpValues.unwrap(source);
            if (raw == null) throw new PhpError("Error", "Cannot use a null value as an array");
            return PhpValues.own(raw);
        }

        private Object argumentKey() {
            return key == APPEND ? null : PhpValues.own(PhpValues.unwrap(key));
        }

        private Object call(String name, Object[] values, IndirectCallNode call) {
            var args = new Argument[values.length + 1];
            args[0] = new Argument(argumentKey(), null);
            for (int i = 0; i < values.length; i++) args[i + 1] = new Argument(values[i], null);
            return Operations.invokeMember(caller, "method", name, receiver(), args, call);
        }

        public Object read(IndirectCallNode call) {
            if (overloaded) return call("offsetGet", new Object[0], call);
            if (location != null) {
                try { return caller.track(PhpValues.own(location.read())); }
                catch (java.util.NoSuchElementException error) { throw new PhpError(error.getMessage()); }
            }
            if (key == APPEND) throw new PhpError("Error", "Cannot read an append location");
            return caller.track(PhpValues.element(source, PhpValues.unwrap(key), Diagnostics.origin(caller)));
        }

        public Object readForAssign(IndirectCallNode call) {
            if (overloaded) return call("offsetGet", new Object[0], call);
            if (location == null) return read(call);
            return caller.track(PhpValues.own(location.readForAssignOperation()));
        }

        public Object exists(IndirectCallNode call) {
            if (overloaded) return call("offsetExists", new Object[0], call);
            if (location != null) return location.probe() != null;
            if (key == APPEND) return false;
            Object value = PhpValues.probeElement(source, PhpValues.unwrap(key), Diagnostics.origin(caller));
            try { return PhpValues.unwrap(value) != null; }
            finally { PhpValues.drop(value); }
        }

        /** Consumes the supplied owned value. */
        public Object write(Object value, IndirectCallNode call) {
            if (overloaded) return call("offsetSet", new Object[] {value}, call);
            try {
                if (location == null) throw new PhpError("Error", "Cannot modify a temporary array value");
                location.write(PhpValues.unwrap(value));
                return null;
            } finally { PhpValues.drop(value); }
        }

        public Object unset(IndirectCallNode call) {
            if (overloaded) return call("offsetUnset", new Object[0], call);
            if (location == null) throw new PhpError("Error", "Cannot unset a temporary array value");
            location.unset();
            return null;
        }

        public void checkAssignOperation() {
            if (!overloaded && location != null) location.checkAssignOperation();
        }

        @TruffleBoundary
        public void indirectModification() {
            String type = ObjectModel.className(PhpValues.unwrap(source));
            Diagnostics.origin(caller).notice("Indirect modification of overloaded element of " + type + " has no effect");
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            PhpValues.drop(source);
            PhpValues.drop(key);
        }
    }
}
