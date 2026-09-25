import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Disposable Java build output and isolated diagnostic variants; never part of the application. */
public final class BuildSupport {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 1 && arguments[0].equals("curl-states")) {
            String source = Files.readString(Path.of("build/native/deps/standard-curl-8.22.0/lib/multi.c"));
            String counters = """
                #include <time.h>
                static unsigned long long gp_state_calls[32], gp_state_nanos[32];
                static unsigned long long gp_state_clock(void) {
                    struct timespec now; timespec_get(&now, TIME_UTC);
                    return (unsigned long long) now.tv_sec * 1000000000 + now.tv_nsec;
                }
                static void gp_state_report(void) {
                    for(int i = 0; i < 32; i++) if(gp_state_calls[i])
                        fprintf(stderr, "COST curl/state%d count=%llu milliseconds=%.3f us_per_call=%.3f\\n",
                            i, gp_state_calls[i], gp_state_nanos[i] / 1e6, gp_state_nanos[i] / 1e3 / gp_state_calls[i]);
                }
                """;
            source = replaceOnce(source, "static CURLMcode multi_runsingle(", counters + "\nstatic CURLMcode multi_runsingle(");
            source = replaceOnce(source, "    switch(data->mstate) {\n    case MSTATE_INIT:\n      /* Transitional state.",
                    "    static int gp_state_initialized; if(!gp_state_initialized) { gp_state_initialized = 1; atexit(gp_state_report); }\n"
                    + "    int gp_state = data->mstate; unsigned long long gp_start = gp_state_clock();\n"
                    + "    switch(data->mstate) {\n    case MSTATE_INIT:\n      /* Transitional state.");
            source = replaceOnce(source, "    if(data->mstate >= MSTATE_CONNECT &&\n       data->mstate < MSTATE_DO &&",
                    "    gp_state_calls[gp_state]++; gp_state_nanos[gp_state] += gp_state_clock() - gp_start;\n"
                    + "    if(data->mstate >= MSTATE_CONNECT &&\n       data->mstate < MSTATE_DO &&");
            Path output = Path.of("build/probe-curl-states/multi.c");
            Files.createDirectories(output.getParent()); Files.writeString(output, source);
            source = Files.readString(Path.of("build/native/deps/standard-curl-8.22.0/lib/http.c"));
            source = replaceOnce(source, "CURLcode Curl_http(struct Curl_easy *data, bool *done)\n{",
                    counters.replace("curl/state", "curl/http") + "\nCURLcode Curl_http(struct Curl_easy *data, bool *done)\n{\n"
                    + "  static int initialized; if(!initialized) { initialized = 1; atexit(gp_state_report); }\n"
                    + "  unsigned long long gp_start = gp_state_clock();\n");
            source = replaceOnce(source, "  result = Curl_req_send(data, &req, httpversion);",
                    "  gp_state_calls[0]++; gp_state_nanos[0] += gp_state_clock() - gp_start; gp_start = gp_state_clock();\n"
                    + "  result = Curl_req_send(data, &req, httpversion);\n"
                    + "  gp_state_calls[1]++; gp_state_nanos[1] += gp_state_clock() - gp_start;\n");
            Files.writeString(output.getParent().resolve("http.c"), source);
            source = Files.readString(Path.of("build/native/deps/standard-curl-8.22.0/lib/cf-socket.c"));
            source = replaceOnce(source, "static CURLcode cf_socket_send(", counters.replace("curl/state", "curl/socket") + "\nstatic CURLcode cf_socket_send(");
            source = replaceOnce(source, "    rv = swrite(ctx->sock, buf, len);",
                    "    { static int initialized; if(!initialized) { initialized = 1; atexit(gp_state_report); }\n"
                    + "    unsigned long long gp_start = gp_state_clock(); rv = swrite(ctx->sock, buf, len);\n"
                    + "    gp_state_calls[0]++; gp_state_nanos[0] += gp_state_clock() - gp_start; }\n");
            source = replaceOnce(source, "    win_update_sndbuf_size(data, ctx);",
                    "    { unsigned long long gp_start = gp_state_clock(); win_update_sndbuf_size(data, ctx);\n"
                    + "    gp_state_calls[1]++; gp_state_nanos[1] += gp_state_clock() - gp_start; }\n");
            Files.writeString(output.getParent().resolve("cf-socket.c"), source); return;
        }
        if (arguments.length == 2 && arguments[0].equals("reactor-probe")) {
            String variant = arguments[1];
            if (!List.of("cycles", "polls", "retained", "curl-costs", "dispatch-costs").contains(variant)) throw new IllegalArgumentException("Unknown reactor probe: " + variant);
            Path classes = Path.of("build/classes"), output = Path.of("build/probe-" + variant + "-classes");
            try (var paths = Files.walk(classes)) {
                for (Path file : paths.toList()) {
                    Path target = output.resolve(classes.relativize(file));
                    if (Files.isDirectory(file)) Files.createDirectories(target);
                    else Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String filename = variant.equals("polls") ? "Scheduler.java" : "PhpValues.java";
            if (variant.equals("dispatch-costs")) {
                dispatchCosts(classes, output);
                return;
            }
            if (variant.equals("curl-costs")) {
                curlCosts(classes, output);
                return;
            }
            String source = Files.readString(Path.of("src/graalphp/runtime/" + filename));
            if (variant.equals("cycles")) {
                // Diagnostic only: removing automatic cycle collection is NOT valid production lifetime policy.
                source = replaceOnce(source, "            heap.collectCycles();", "            // Diagnostic: automatic cycle collection omitted.");
            } else if (variant.equals("retained")) {
                source = replaceOnce(source, "            candidates = Collections.newSetFromMap(new IdentityHashMap<>());",
                        "            candidates.clear();");
            } else {
                source = replaceOnce(source, "pump(root, 64);", "pump(root, 512);");
                source = replaceOnce(source, "        request.pollIO();\n        for (int i = 0; i < budget; i++)", "        for (int i = 0; i < budget; i++)");
            }
            Path file = Path.of("build/probe-" + variant + "-source/graalphp/runtime/" + filename);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            String classpath;
            try (var jars = Files.list(Path.of("build/deps/25.4.4.1.1"))) {
                classpath = classes + java.io.File.pathSeparator + jars.filter(path -> path.toString().endsWith(".jar"))
                        .map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            }
            int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "25", "-proc:none", "-cp", classpath, "-d", output.toString(), file.toString());
            if (result != 0) throw new IllegalStateException("Probe compilation failed: " + result);
            return;
        }
        if (arguments.length == 1 && arguments[0].equals("clean")) {
            Path workspace = Path.of("").toRealPath();
            Path build = workspace.resolve("build");
            if (!Files.exists(build)) return;
            if (!build.toRealPath().startsWith(workspace)) throw new IllegalStateException("Build directory is outside the workspace");
            for (String directory : List.of("classes", "generated")) {
                Path output = build.resolve(directory);
                if (!Files.exists(output)) continue;
                if (!output.toRealPath().startsWith(build.toRealPath())) throw new IllegalStateException("Output is outside build/: " + directory);
                try (var files = Files.walk(output)) {
                    for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            return;
        }
        throw new IllegalArgumentException("Usage: java BuildSupport.java clean | curl-states | reactor-probe cycles|polls|retained|curl-costs|dispatch-costs");
    }
    private static void dispatchCosts(Path classes, Path output) throws Exception {
        Path directory = Path.of("build/probe-dispatch-costs-source/graalphp/runtime");
        Files.createDirectories(directory);
        var sources = new java.util.ArrayList<String>();
        for (String name : List.of("NativeAccess", "CurlApi", "PhpValues", "Execution", "PhpString", "LibuvReactor", "Operations", "Scheduler")) {
            String source = Files.readString(Path.of("src/graalphp/runtime/" + name + ".java"));
            source = switch (name) {
                case "NativeAccess" -> dispatchInstrument(source, "public Object call(String library, String name, String signature, Object[] arguments)",
                        "name.equals(\"gp_reactor_step\") && ((Number) arguments[1]).longValue() > 0 ? 1 : 0");
                case "CurlApi" -> dispatchInstrument(source, "public static Object function(Activation caller, String name, Object[] values, IndirectCallNode call)", "2");
                case "PhpValues" -> dispatchInstrument(source, "public int collectCycles()", "3");
                case "Execution" -> {
                    source = dispatchInstrument(source, "@Override public void close() {\n            if (closed) return; closed = true;", "4");
                    source = dispatchInstrument(source, "public PhpValues.Location variable(String name)", "5");
                    yield replaceOnce(source, "            if (failure != null) throw failure;\n        }\n    }\n\n    public static final class Activation",
                            "            if (failure != null) throw failure;\n            DispatchCosts.print();\n        }\n    }\n\n    public static final class Activation");
                }
                case "PhpString" -> dispatchInstrument(source, "public static Object fromBytes(byte[] bytes)", "6");
                case "LibuvReactor" -> {
                    source = dispatchInstrument(source, "public Scheduler.Future submit(String function, String parameters, Object... values)", "7");
                    source = dispatchInstrument(source, "public void step(long timeoutNanos)", "8");
                    yield dispatchInstrument(source, "Object execute(Object[] values)", "9");
                }
                case "Operations" -> {
                    source = dispatchInstrument(source, "public static Object invoke(Activation caller, String name, Argument[] arguments, IndirectCallNode call, boolean globalFallback)", "10");
                    yield dispatchInstrument(source, "public static Object executeChild(Activation caller, Activation child, IndirectCallNode call)", "11");
                }
                case "Scheduler" -> {
                    source = dispatchInstrument(source, "private void advance(Task task, Object result, PhpError failure)", "12");
                    yield dispatchInstrument(source, "@Override public void run() {\n            if (task.waiting", "13");
                }
                default -> throw new AssertionError(name);
            };
            Path file = directory.resolve(name + ".java"); Files.writeString(file, source); sources.add(file.toString());
        }
        Path counters = directory.resolve("DispatchCosts.java");
        Files.writeString(counters, """
            package graalphp.runtime;
            import java.util.Locale;
            public final class DispatchCosts {
                private static final String[] NAMES = {"native", "native-wait", "curl-dispatch", "cycle-collector",
                    "activation-close", "variable", "string-fromBytes", "reactor-submit", "reactor-step",
                    "native-completion", "function-dispatch", "execute-child", "scheduler-advance", "scheduler-resume"};
                private static final class State {
                    final long[] starts = new long[256], children = new long[256];
                    final long[][] rows = new long[NAMES.length][3];
                    int depth;
                }
                private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
                static void enter() {
                    var state = STATE.get(); int depth = state.depth++;
                    state.children[depth] = 0; state.starts[depth] = System.nanoTime();
                }
                static void leave(int id) {
                    long end = System.nanoTime(); var state = STATE.get(); int depth = --state.depth;
                    long elapsed = end - state.starts[depth];
                    state.rows[id][0]++; state.rows[id][1] += elapsed; state.rows[id][2] += elapsed - state.children[depth];
                    if (depth > 0) state.children[depth - 1] += elapsed;
                }
                static void print() {
                    var state = STATE.get();
                    if (state.depth != 0) throw new IllegalStateException("Unbalanced diagnostic timer");
                    for (int i = 0; i < NAMES.length; i++) System.err.printf(Locale.ROOT,
                        "DISPATCH %s count=%d inclusive_ms=%.3f exclusive_ms=%.3f%n",
                        NAMES[i], state.rows[i][0], state.rows[i][1] / 1e6, state.rows[i][2] / 1e6);
                }
            }
            """);
        sources.add(counters.toString());
        var options = new java.util.ArrayList<>(List.of("--release", "25", "-proc:none", "-cp", classpath(classes), "-d", output.toString()));
        options.addAll(sources);
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new));
        if (result != 0) throw new IllegalStateException("Diagnostic compilation failed: " + result);
    }
    private static String dispatchInstrument(String source, String signature, String id) {
        return instrumentBody(source, signature, "DispatchCosts.enter(); try {", "} finally { DispatchCosts.leave(" + id + "); }\n");
    }
    private static void curlCosts(Path classes, Path output) throws Exception {
        Path sourceDirectory = Path.of("build/probe-curl-costs-source/graalphp/runtime");
        Files.createDirectories(sourceDirectory);
        var sources = new java.util.ArrayList<String>();
        for (String name : List.of("PhpValues", "NativeAccess", "CurlApi", "PhpString", "Execution")) {
            String source = Files.readString(Path.of("src/graalphp/runtime/" + name + ".java"));
            source = switch (name) {
                case "PhpValues" -> instrument(source, "public int collectCycles()", "\"cycles\"");
                case "NativeAccess" -> instrument(source, "public Object call(String library, String name, String signature, Object[] arguments)",
                        "\"native/\" + name + (name.equals(\"gp_reactor_step\") && ((Number) arguments[1]).longValue() > 0 ? \"/wait\" : \"\")");
                case "CurlApi" -> instrument(source, "public static Object function(Activation caller, String name, Object[] values, IndirectCallNode call)", "\"curl/\" + name");
                case "PhpString" -> instrument(source, "public static Object fromBytes(byte[] bytes)", "\"string/fromBytes\"");
                case "Execution" -> replaceOnce(source,
                        "            if (failure != null) throw failure;\n        }\n    }\n\n    public static final class Activation",
                        "            if (failure != null) throw failure;\n            CurlCosts.print();\n        }\n    }\n\n    public static final class Activation");
                default -> throw new AssertionError(name);
            };
            Path file = sourceDirectory.resolve(name + ".java"); Files.writeString(file, source); sources.add(file.toString());
        }
        Path counters = sourceDirectory.resolve("CurlCosts.java");
        Files.writeString(counters, """
            package graalphp.runtime;
            import java.util.*;
            public final class CurlCosts {
                private static final Map<String, long[]> costs = new TreeMap<>();
                static void record(String name, long start) {
                    long elapsed = System.nanoTime() - start;
                    var row = costs.computeIfAbsent(name, key -> new long[2]); row[0]++; row[1] += elapsed;
                }
                static void print() {
                    costs.forEach((name, row) -> System.err.printf(Locale.ROOT,
                        "COST %s count=%d milliseconds=%.3f us_per_call=%.3f%n", name, row[0], row[1] / 1e6, row[1] / 1e3 / row[0]));
                }
            }
            """);
        sources.add(counters.toString());
        var options = new java.util.ArrayList<>(List.of("--release", "25", "-proc:none", "-cp", classpath(classes), "-d", output.toString()));
        options.addAll(sources);
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new));
        if (result != 0) throw new IllegalStateException("Diagnostic compilation failed: " + result);
    }
    private static String classpath(Path classes) throws Exception {
        try (var jars = Files.list(Path.of("build/deps/25.4.4.1.1"))) {
            return classes + java.io.File.pathSeparator + jars.filter(path -> path.toString().endsWith(".jar"))
                    .map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        }
    }
    private static String instrument(String source, String signature, String key) {
        return instrumentBody(source, signature, "long costStart = System.nanoTime(); try {",
                "} finally { CurlCosts.record(" + key + ", costStart); }\n");
    }
    private static String instrumentBody(String source, String signature, String prefix, String suffix) {
        int signatureStart = source.indexOf(signature);
        if (signatureStart < 0 || source.indexOf(signature, signatureStart + 1) >= 0) throw new IllegalStateException("Diagnostic anchor changed: " + signature);
        int start = source.indexOf('{', signatureStart), end = start + 1, depth = 1;
        // The selected bodies contain no unmatched brace characters in strings/comments.
        while (depth != 0) { char c = source.charAt(end++); if (c == '{') depth++; else if (c == '}') depth--; }
        return source.substring(0, start + 1) + "\n" + prefix + source.substring(start + 1, end - 1) + suffix + source.substring(end - 1);
    }
    private static String replaceOnce(String source, String before, String after) {
        int offset = source.indexOf(before);
        if (offset < 0 || source.indexOf(before, offset + before.length()) >= 0)
            throw new IllegalStateException("Diagnostic source anchor changed: " + before);
        return source.substring(0, offset) + after + source.substring(offset + before.length());
    }
}
