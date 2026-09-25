package graalphp.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

public final class PhpError extends AbstractTruffleException {
    private static final long serialVersionUID = 1L;
    public final String type;
    public final PhpError previous;
    public PhpError(String message) { this("Exception", message, null); }
    public PhpError(String type, String message) { this(type, message, null); }
    public PhpError(String type, String message, PhpError previous) {
        super(message);
        this.type = type;
        this.previous = previous;
    }
    public boolean matches(String types) {
        for (String expected : types.split("\\|")) {
            if (expected.equalsIgnoreCase(type) || expected.equalsIgnoreCase("Throwable")) return true;
            if (expected.equalsIgnoreCase("Exception") && !type.endsWith("Error")) return true;
            if (expected.equalsIgnoreCase("Error") && type.endsWith("Error")) return true;
            if (expected.equalsIgnoreCase("Async\\AsyncCancellation")
                    && (type.equals("Async\\OperationCanceledException") || type.equals("Async\\AsyncCancellation"))) return true;
            if (expected.equalsIgnoreCase("Cancellation") && type.contains("Cancel")) return true;
            if (expected.equalsIgnoreCase("Async\\AsyncException")
                    && (type.equals("Async\\ChannelException") || type.equals("Async\\ThreadChannelException") || type.equals("Async\\ContextException"))) return true;
        }
        return false;
    }
}
