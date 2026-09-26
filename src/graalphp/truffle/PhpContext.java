package graalphp.truffle;

import com.oracle.truffle.api.TruffleLanguage;
import graalphp.runtime.*;
import java.nio.file.Path;
import java.io.IOException;

public final class PhpContext implements AutoCloseable {
    public final PhpLanguage language;
    public final TruffleLanguage.Env environment;
    public final NativeAccess nativeAccess;
    private CodeRepository repository;
    private final java.util.List<HostedRequest> hostedRequests = new java.util.ArrayList<>();
    private java.util.Map<String, Execution.Function> asyncFunctions;
    private java.util.Map<String, ObjectModel.Definition> builtinTypes;
    public synchronized java.util.Map<String, ObjectModel.Definition> builtinTypes() {
        if (builtinTypes == null) builtinTypes = PhpCompiler.builtinTypes(language,
                com.oracle.truffle.api.source.Source.newBuilder("php", EnumApi.INTERFACES + IterationApi.TYPES + ReflectionApi.TYPES + "\nclass stdClass {}", "<builtin-types>").internal(true).build());
        return builtinTypes;
    }
    public synchronized Execution.Function asyncFunction(String name) {
        if (asyncFunctions == null) asyncFunctions = AsyncBuiltins.compile(this);
        return asyncFunctions.get(name);
    }
    public PhpContext(PhpLanguage language, TruffleLanguage.Env environment) {
        this.language = language; this.environment = environment;
        nativeAccess = new NativeAccess(environment);
    }
    public synchronized CodeRepository repository() { return repository; }
    private synchronized CodeRepository repository(Execution.Unit entry) {
        if (repository == null) {
            String configured = environment.getEnvironment().get("GRAALPHP_ROOT");
            Path root = configured == null ? entry.path() == null ? null : entry.path().getParent() : Path.of(configured);
            repository = new CodeRepository(language, root,
                    "1".equals(environment.getEnvironment().get("GRAALPHP_WATCH")));
        }
        return repository;
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public Object execute(Execution.Unit parsed) {
        var generation = repository(parsed).snapshot();
        var unit = parsed.path() == null ? parsed : generation.units().get(parsed.path());
        if (unit == null) throw new PhpError("Entry file absent from current generation: " + parsed.path().getFileName());
        var request = new Execution.Request(this, generation);
        if (request.hosted) {
            try {
                Object imported = environment.importSymbol("graalphp.wakeup");
                if (!environment.isHostObject(imported) || !(environment.asHostObject(imported) instanceof Runnable wakeup)) {
                    throw new PhpError("Hosted mode requires a host Runnable named graalphp.wakeup");
                }
                request.install(unit);
                var hosted = new HostedRequest(request, unit.main(), wakeup);
                hostedRequests.add(hosted);
                return hosted;
            } catch (RuntimeException error) { request.close(); throw error; }
        }
        try (request) {
            request.install(unit);
            Object result = request.scheduler.run(unit.main());
            Object value = PhpValues.unwrap(result);
            return value == null ? 0L : value instanceof String || value instanceof Number || value instanceof Boolean ? value : 1L;
        } finally {
            flushOutput(request);
        }
    }
    public void flushOutput(Execution.Request request) {
        try {
            environment.out().write(request.output.toByteArray());
            environment.out().flush();
        } catch (IOException error) { throw new PhpError("Output failed: " + error.getMessage()); }
    }
    @Override public synchronized void close() {
        for (var hosted : hostedRequests) hosted.close();
        hostedRequests.clear();
        if (repository != null) repository.close();
    }
}
