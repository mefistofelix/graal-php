package graalphp.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.SourceSection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static graalphp.runtime.Execution.*;

/** Request-local diagnostics; source locations are obtained only when a diagnostic is emitted. */
public final class Diagnostics {
    private Diagnostics() {}
    public static final Map<String, Long> CONSTANTS = Map.ofEntries(
            Map.entry("E_ERROR", 1L), Map.entry("E_WARNING", 2L), Map.entry("E_PARSE", 4L),
            Map.entry("E_NOTICE", 8L), Map.entry("E_CORE_ERROR", 16L), Map.entry("E_CORE_WARNING", 32L),
            Map.entry("E_COMPILE_ERROR", 64L), Map.entry("E_COMPILE_WARNING", 128L),
            Map.entry("E_USER_ERROR", 256L), Map.entry("E_USER_WARNING", 512L), Map.entry("E_USER_NOTICE", 1024L),
            Map.entry("E_RECOVERABLE_ERROR", 4096L), Map.entry("E_DEPRECATED", 8192L),
            Map.entry("E_USER_DEPRECATED", 16384L), Map.entry("E_ALL", 30719L));
    public record LastError(long type, String message, String file, int line) {}

    @TruffleBoundary
    public static void deprecated(Activation caller, String message) { emit(caller, 8192L, "Deprecated", message); }

    @TruffleBoundary
    public static void emit(Activation caller, long type, String label, String message) {
        SourceSection section = caller.diagnosticBytecode == null ? null
                : caller.diagnosticBytecode.getSourceLocation(caller.diagnosticBytecodeIndex);
        String file = caller.function.file() != null ? caller.function.file().toString()
                : section == null ? caller.function.name() : section.getSource().getName();
        int line = section == null ? 0 : section.getStartLine();
        caller.request.lastError = new LastError(type, message, file, line);
        if ((caller.request.errorReporting & type) != 0) {
            String text = "\n" + label + ": " + message + " in " + file + " on line " + line + "\n";
            caller.request.output.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Object function(Activation caller, String name, Argument[] arguments) {
        switch (name) {
            case "error_reporting": {
                var ordered = CallArguments.builtin(name, arguments, List.of("error_level"), 0, (Object) null);
                long old = caller.request.errorReporting;
                if (ordered[0].value() != null) caller.request.errorReporting = ((Number) ObjectModel.checkType(ordered[0].value(), "int")).longValue();
                return old;
            }
            case "error_clear_last": {
                CallArguments.builtin(name, arguments, List.of(), 0);
                caller.request.lastError = null;
                return null;
            }
            case "error_get_last": {
                CallArguments.builtin(name, arguments, List.of(), 0);
                var last = caller.request.lastError;
                if (last == null) return null;
                try (var scope = new PhpValues.Scope(caller.request.heap)) {
                    var result = scope.variable(scope.emptyArray());
                    result.element("type").set(last.type);
                    result.element("message").set(last.message);
                    result.element("file").set(last.file);
                    result.element("line").set((long) last.line);
                    return caller.track(PhpValues.own(result.read()));
                }
            }
            default: return AsyncApi.UNHANDLED;
        }
    }
}
