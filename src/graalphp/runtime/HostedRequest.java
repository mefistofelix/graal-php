package graalphp.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import static graalphp.runtime.Execution.*;

/** Entered through Polyglot on the host's owner thread; never blocks waiting for runnable PHP. */
@ExportLibrary(InteropLibrary.class)
public final class HostedRequest implements TruffleObject, AutoCloseable {
    private final Request request;
    private final Scheduler.Future root;
    private final Thread owner = Thread.currentThread();
    private boolean closed;
    public HostedRequest(Request request, Function main, Runnable wakeup) {
        this.request = request;
        request.scheduler.setWakeup(wakeup);
        root = request.scheduler.start(main);
    }
    @ExportMessage boolean hasMembers() { return true; }
    @ExportMessage Object getMembers(boolean includeInternal) { return new Members(); }
    @ExportMessage boolean isMemberInvocable(String name) { return Members.NAMES.contains(name); }
    @ExportMessage @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object invokeMember(String name, Object[] args) throws UnknownIdentifierException {
        if (Thread.currentThread() != owner) throw new PhpError("Hosted PHP must be pumped on its owning thread");
        if (closed && !name.equals("close")) throw new PhpError("Hosted request is closed");
        return switch (name) {
            case "pump" -> request.scheduler.pump(root, args.length == 0 ? 8 : ((Number) args[0]).intValue());
            case "pending" -> request.scheduler.pending();
            case "deadline" -> request.scheduler.nextDeadline();
            case "result" -> {
                Object value = PhpValues.unwrap(request.scheduler.finish(root));
                yield value == null ? 0L : value instanceof String || value instanceof Number || value instanceof Boolean ? value : 1L;
            }
            case "cancel" -> { request.scheduler.cancelScope(request.scheduler.rootScope, new PhpError("Async\\AsyncCancellation", "Host cancelled request")); yield 0L; }
            case "close" -> { close(); yield 0L; }
            default -> throw UnknownIdentifierException.create(name);
        };
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        request.scheduler.setWakeup(() -> {});
        try { request.close(); }
        finally { request.context.flushOutput(request); }
    }

    @ExportLibrary(InteropLibrary.class)
    public static final class Members implements TruffleObject {
        static final java.util.List<String> NAMES = java.util.List.of("pump", "pending", "deadline", "result", "cancel", "close");
        @ExportMessage boolean hasArrayElements() { return true; }
        @ExportMessage long getArraySize() { return NAMES.size(); }
        @ExportMessage boolean isArrayElementReadable(long index) { return index >= 0 && index < NAMES.size(); }
        @ExportMessage Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
            return NAMES.get((int) index);
        }
    }
}
