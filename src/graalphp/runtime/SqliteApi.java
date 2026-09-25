package graalphp.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.util.*;
import java.util.concurrent.Callable;
import static graalphp.runtime.Execution.*;

/** SQLite3 resources stay native; query bindings are copied before crossing the named worker boundary. */
public final class SqliteApi {
    private SqliteApi() {}
    public static final String SOURCE = """
        function sqlite_fetch($result, $mode) {
            $row = __sqlite_fetch($result);
            return __sqlite_row($row, $mode);
        }
        function sqlite_query($database, $query) {
            $statement = $database->prepare($query);
            return $statement->execute();
        }
        """;
    public static final Map<String, Long> CONSTANTS = Map.of("SQLITE3_INTEGER", 1L, "SQLITE3_FLOAT", 2L,
            "SQLITE3_TEXT", 3L, "SQLITE3_BLOB", 4L, "SQLITE3_NULL", 5L,
            "SQLITE3_ASSOC", 1L, "SQLITE3_NUM", 2L, "SQLITE3_BOTH", 3L);
    public static final class Database implements TruffleObject, AutoCloseable {
        final NativeAccess nativeAccess;
        long pointer;
        Database(NativeAccess nativeAccess) { this.nativeAccess = nativeAccess; }
        Object nativeCall(String function, String signature, Object... arguments) {
            return nativeAccess.call("builtin:sqlite3", "gp_sqlite_" + function, signature, arguments);
        }
        Object call(String function, String signature, Object... values) {
            if (pointer == 0) throw new PhpError("Error", "SQLite database is closed");
            Object[] args = new Object[values.length + 1]; args[0] = pointer;
            System.arraycopy(values, 0, args, 1, values.length);
            return nativeCall(function, signature, args);
        }
        void check(Object status) {
            if (((Number) status).intValue() != 0) throw failure();
        }
        PhpError failure() { return new PhpError("Exception", Operations.string(call("error", "(UINT64):STRING"))); }
        @Override public void close() {
            if (pointer != 0) { check(call("close", "(UINT64):SINT32")); pointer = 0; }
        }
    }
    public static final class Statement implements TruffleObject, AutoCloseable {
        final Database database;
        long pointer;
        volatile boolean busy;
        int generation;
        final Map<Object, Binding> bindings = new LinkedHashMap<>();
        Statement(Database database) { this.database = database; }
        Object call(String function, String signature, Object... values) {
            if (pointer == 0 || database.pointer == 0) throw new PhpError("SQLite statement is closed");
            Object[] args = new Object[values.length + 1]; args[0] = pointer;
            System.arraycopy(values, 0, args, 1, values.length);
            return database.nativeCall(function, signature, args);
        }
        void idle() { if (busy) throw new PhpError("SQLite statement is already in use"); }
        @Override public void close() {
            if (pointer != 0) { database.nativeCall("finalize", "(UINT64):SINT32", pointer); pointer = 0; generation++; }
        }
    }
    public static final class Result implements TruffleObject {
        final Statement statement;
        final int generation;
        Row first;
        boolean firstPending = true, ended;
        Result(Statement statement, Row first) { this.statement = statement; this.first = first; generation = statement.generation; }
    }
    private record Binding(Object value, int type) {}
    private record Row(String[] columns, Object[] values) implements TruffleObject {}
    public static String className(Object value) {
        if (value instanceof Database) return "SQLite3";
        if (value instanceof Statement) return "SQLite3Stmt";
        if (value instanceof Result) return "SQLite3Result";
        return null;
    }
    public static Object allocate(Activation caller, String name) {
        if (!name.equalsIgnoreCase("SQLite3")) return AsyncApi.UNHANDLED;
        var database = new Database(caller.request.context.nativeAccess);
        caller.request.resources.add(database);
        return database;
    }
    private static Argument[] ordered(String name, Argument[] args, int required, String... names) {
        return CallArguments.builtin(name, args, List.of(names), required);
    }
    private static Object offload(Activation caller, Callable<Object> work) {
        if (caller.synchronousCallback) throw new PhpError("Cannot suspend a native callback");
        var future = caller.request.workers(caller, "sqlite", new WorkerPool.Configuration(0, 1, 256)).submit(caller, work);
        future.cancelOnAbort = true;
        return Scheduler.awaitValue(caller, future, null);
    }
    private static Object offload(Activation caller, Statement statement, Callable<Object> work) {
        if (caller.synchronousCallback) throw new PhpError("Cannot suspend a native callback");
        statement.idle(); statement.busy = true;
        Scheduler.Future future;
        try {
            future = caller.request.workers(caller, "sqlite", new WorkerPool.Configuration(0, 1, 256)).submit(caller, work);
        }
        catch (RuntimeException error) { statement.busy = false; throw error; }
        var released = new Scheduler.Future();
        future.observed = true;
        released.cancelAction = future.cancelAction;
        released.cancelOnAbort = true;
        future.completion.whenComplete((value, failure) -> {
            statement.busy = false;
            if (failure == null) released.completion.complete(value);
            else released.completion.completeExceptionally(failure);
        });
        return Scheduler.awaitValue(caller, released, null);
    }
    public static Object method(Activation caller, Object value, String name, Argument[] args, IndirectCallNode call) {
        name = name.toLowerCase(Locale.ROOT);
        if (value instanceof Database database) {
            switch (name) {
                case "__construct": {
                    String path = Operations.string(ordered(name, args, 1, "filename")[0].value());
                    if (path.indexOf(0) >= 0) throw new PhpError("Invalid SQLite path");
                    return offload(caller, () -> {
                        database.pointer = ((Number) database.nativeCall("open", "(STRING):UINT64", path)).longValue();
                        if (database.pointer == 0) throw new PhpError("SQLite allocation failed");
                        database.check(database.call("exec", "(UINT64,STRING):SINT32", "PRAGMA schema_version"));
                        return null;
                    });
                }
                case "exec": {
                    String sql = sql(ordered(name, args, 1, "query")[0].value());
                    return offload(caller, () -> { database.check(database.call("exec", "(UINT64,STRING):SINT32", sql)); return true; });
                }
                case "prepare": {
                    String sql = sql(ordered(name, args, 1, "query")[0].value());
                    var statement = new Statement(database);
                    caller.request.resources.add(statement);
                    return offload(caller, () -> {
                        statement.pointer = ((Number) database.call("prepare", "(UINT64,STRING):UINT64", sql)).longValue();
                        if (statement.pointer == 0) throw database.failure();
                        return statement;
                    });
                }
                case "query": {
                    var query = ordered(name, args, 1, "query")[0];
                    return Operations.invokeFunction(caller, caller.request.context.asyncFunction("sqlite_query"), new Argument[] {new Argument(database, null), query}, call);
                }
                case "querysingle": {
                    var arguments = CallArguments.builtin(name, args, List.of("query", "entireRow"), 1, false);
                    if (Operations.truth(arguments[1].value())) throw new PhpError("querySingle entireRow is not implemented; use query/fetchArray");
                    String sql = sql(arguments[0].value());
                    return offload(caller, () -> {
                        var statement = new Statement(database);
                        statement.pointer = ((Number) database.call("prepare", "(UINT64,STRING):UINT64", sql)).longValue();
                        if (statement.pointer == 0) throw database.failure();
                        try { Row row = step(statement); return row == null || row.values.length == 0 ? null : row.values[0]; }
                        finally { statement.close(); }
                    });
                }
                case "busytimeout": {
                    long timeout = Operations.number(ordered(name, args, 1, "milliseconds")[0].value()).longValue();
                    if (timeout < 0 || timeout > 30000) throw new PhpError("SQLite busy timeout must be 0..30000 ms");
                    return offload(caller, () -> { database.check(database.call("busy_timeout", "(UINT64,SINT32):SINT32", timeout)); return true; });
                }
                case "lastinsertrowid":
                    ordered(name, args, 0);
                    return offload(caller, () -> database.call("last_id", "(UINT64):SINT64"));
                case "changes":
                    ordered(name, args, 0);
                    return offload(caller, () -> database.call("changes", "(UINT64):SINT32"));
                case "close":
                    ordered(name, args, 0);
                    return offload(caller, () -> { database.close(); return true; });
                default: throw new PhpError("Unimplemented SQLite3::" + name);
            }
        }
        if (value instanceof Statement statement) {
            statement.idle();
            if (name.equals("bindvalue")) {
                var arguments = CallArguments.builtin(name, args, List.of("param", "value", "type"), 2, 3L);
                Object key = PhpValues.unwrap(arguments[0].value()), item = PhpValues.unwrap(arguments[1].value());
                int type = Operations.number(arguments[2].value()).intValue();
                if (!(key instanceof Long) && !(key instanceof String) || type < 1 || type > 5) throw new PhpError("Invalid SQLite binding");
                if (type == 3 || type == 4) item = PhpString.bytes(item);
                else if (type == 1) item = Operations.number(item).longValue();
                else if (type == 2) item = Operations.number(item).doubleValue();
                else item = null;
                statement.bindings.put(key, new Binding(item, type)); return true;
            }
            ordered(name, args, 0);
            if (name.equals("close")) return offload(caller, statement, () -> { statement.close(); return true; });
            if (name.equals("clear")) { statement.bindings.clear(); return true; }
            if (name.equals("execute")) {
                var bindings = Map.copyOf(statement.bindings);
                statement.generation++;
                return offload(caller, statement, () -> {
                    statement.call("reset", "(UINT64):SINT32");
                    for (var binding : bindings.entrySet()) bind(statement, binding.getKey(), binding.getValue());
                    return new Result(statement, step(statement));
                });
            }
            throw new PhpError("Unimplemented SQLite3Stmt::" + name);
        }
        if (value instanceof Result result) {
            if (result.generation != result.statement.generation) throw new PhpError("SQLite result invalidated by statement reuse");
            if (name.equals("fetcharray")) {
                var arguments = CallArguments.builtin(name, args, List.of("mode"), 0, 3L);
                int mode = Operations.number(arguments[0].value()).intValue();
                if (mode < 1 || mode > 3) throw new PhpError("Invalid SQLite fetch mode");
                return Operations.invokeFunction(caller, caller.request.context.asyncFunction("sqlite_fetch"),
                        new Argument[] {new Argument(result, null), new Argument((long) mode, null)}, call);
            }
            if (name.equals("finalize")) {
                ordered(name, args, 0); result.ended = true; result.first = null;
                return offload(caller, result.statement, () -> { result.statement.call("reset", "(UINT64):SINT32"); return true; });
            }
            throw new PhpError("Unimplemented SQLite3Result::" + name);
        }
        return AsyncApi.UNHANDLED;
    }
    public static Object function(Activation caller, String name, Object[] values) {
        if (name.equals("__sqlite_fetch")) {
            var result = (Result) values[0];
            if (result.ended) return false;
            return offload(caller, result.statement, () -> {
                Row row = result.firstPending ? result.first : step(result.statement);
                result.firstPending = false; result.first = null;
                if (row == null) { result.ended = true; return false; }
                return row;
            });
        }
        if (name.equals("__sqlite_row")) {
            if (Boolean.FALSE.equals(values[0])) return false;
            var row = (Row) values[0];
            int mode = ((Long) values[1]).intValue();
            var array = caller.locals.variable(caller.locals.emptyArray());
            for (int i = 0; i < row.values.length; i++) {
                if ((mode & 1) != 0) array.element(row.columns[i]).set(row.values[i]);
                if ((mode & 2) != 0) array.element((long) i).set(row.values[i]);
            }
            return caller.track(PhpValues.own(array.read()));
        }
        return AsyncApi.UNHANDLED;
    }
    private static String sql(Object value) {
        String sql = Operations.string(value);
        if (sql.indexOf(0) >= 0) throw new PhpError("SQL text contains NUL");
        return sql;
    }
    private static void bind(Statement statement, Object key, Binding binding) {
        int index;
        if (key instanceof Long number) index = Math.toIntExact(number);
        else {
            String name = (String) key;
            if (!name.startsWith(":") && !name.startsWith("@") && !name.startsWith("$")) name = ":" + name;
            index = ((Number) statement.call("bind_index", "(UINT64,STRING):SINT32", name)).intValue();
        }
        if (index < 1) throw new PhpError("Invalid SQLite parameter " + key);
        Object status = switch (binding.type) {
            case 1 -> statement.call("bind_int", "(UINT64,SINT32,SINT64):SINT32", index, binding.value);
            case 2 -> statement.call("bind_double", "(UINT64,SINT32,DOUBLE):SINT32", index, binding.value);
            case 3, 4 -> {
                byte[] bytes = (byte[]) binding.value;
                yield statement.call("bind_bytes", "(UINT64,SINT32,[UINT8],SINT32,SINT32):SINT32", index, bytes, bytes.length, binding.type);
            }
            default -> statement.call("bind_null", "(UINT64,SINT32):SINT32", index);
        };
        statement.database.check(status);
    }
    private static Row step(Statement statement) {
        int status = ((Number) statement.call("step", "(UINT64):SINT32")).intValue();
        if (status == 101) return null;
        if (status != 100) throw statement.database.failure();
        int count = ((Number) statement.call("columns", "(UINT64):SINT32")).intValue();
        String[] columns = new String[count];
        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            columns[i] = Operations.string(statement.call("column_name", "(UINT64,SINT32):STRING", i));
            int type = ((Number) statement.call("column_type", "(UINT64,SINT32):SINT32", i)).intValue();
            values[i] = switch (type) {
                case 1 -> statement.call("column_int", "(UINT64,SINT32):SINT64", i);
                case 2 -> statement.call("column_double", "(UINT64,SINT32):DOUBLE", i);
                case 3, 4 -> {
                    int length = ((Number) statement.call("column_size", "(UINT64,SINT32):SINT32", i)).intValue();
                    byte[] bytes = new byte[length];
                    statement.call("column_copy", "(UINT64,SINT32,[UINT8],SINT32):VOID", i, bytes, length);
                    yield PhpString.fromBytes(bytes);
                }
                default -> null;
            };
        }
        return new Row(columns, values);
    }
}
