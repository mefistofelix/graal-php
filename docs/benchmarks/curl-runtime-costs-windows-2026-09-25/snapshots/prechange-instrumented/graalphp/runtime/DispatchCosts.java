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
