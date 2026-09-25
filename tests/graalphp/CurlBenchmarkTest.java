package graalphp;

/** Deterministic checks for benchmark accounting; no network or PHP process required. */
public final class CurlBenchmarkTest {
    private static int checks;
    private static final String TIMELINE = """
            VERSION test
            CLOCK 90 100
            LATENCY 5
            TIMELINE 0 0 110 5
            LATENCY 6
            TIMELINE 0 2 120 6
            LATENCY 8
            TIMELINE 1 1 105 8
            PASS curl benchmark
            """;

    public static void main(String[] args) {
        check(CurlBenchmark.expectedSamples(64, 2048, 0) == 0, "untimed control");
        check(CurlBenchmark.expectedSamples(64, 2048, 16) == 8192, "ordinary sampling");
        check(CurlBenchmark.expectedSamples(64, 2048, 1) == 131072, "every request");
        check(CurlBenchmark.expectedSamples(64, 3, 16) == 12, "offset beyond workload");
        check(CurlBenchmark.expectedSamples(2, Integer.MAX_VALUE, 2) == Integer.MAX_VALUE, "wide count");
        rejects(() -> CurlBenchmark.expectedSamples(1, 1, -1), "negative interval");
        rejects(() -> CurlBenchmark.expectedSamples(0, 1, 1), "zero clients");
        check(CurlBenchmark.timelineClock(TIMELINE).equals(new CurlBenchmark.Clock(90, 100)), "clock bracket");
        var samples = CurlBenchmark.timelineSamples(TIMELINE, 2, 3, 2);
        check(samples.size() == 3, "sample count");
        check(samples.get(2).equals(new CurlBenchmark.Timing(1, 1, 105, 8)), "concurrent client ordering");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE, 2, 3, 0), "timeline without sampling");
        rejects(() -> CurlBenchmark.timelineClock(TIMELINE.replace("CLOCK 90 100\n", "")), "missing clock");
        rejects(() -> CurlBenchmark.timelineClock(TIMELINE + "CLOCK 90 100\n"), "duplicate clock");
        rejects(() -> CurlBenchmark.timelineClock(TIMELINE.replace("CLOCK 90 100", "CLOCK 101 100")), "reversed clock");
        rejects(() -> CurlBenchmark.timelineClock(TIMELINE.replace("CLOCK 90 100", "CLOCK 90")), "malformed clock");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("TIMELINE 0 2", "TIMELINE 0 0"), 2, 3, 2), "duplicate identity");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("TIMELINE 1 1", "TIMELINE 2 1"), 2, 3, 2), "invalid client");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("LATENCY 8", "LATENCY 9"), 2, 3, 2), "duration mismatch");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("LATENCY 8\n", ""), 2, 3, 2), "missing latency");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("TIMELINE 1 1 105 8\n", ""), 2, 3, 2), "missing timeline");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("0 2 120 6", "0 2 114 6"), 2, 3, 2), "overlapping calls in one client");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("1 1 105 8", "1 1 99 8"), 2, 3, 2), "sample before clock anchor");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("LATENCY 8", "LATENCY -8").replace("1 1 105 8", "1 1 105 -8"), 2, 3, 2), "negative duration");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("1 1 105 8", "1 1 9223372036854775807 8"), 2, 3, 2), "timestamp overflow");
        rejects(() -> CurlBenchmark.timelineSamples(TIMELINE.replace("1 1 105 8", "1 1 105"), 2, 3, 2), "malformed sample");
        check(CurlBenchmark.timelineClock("CLOCK -20 -10\n").before() == -20, "no assumed monotonic clock origin");
        System.out.println("PASS CurlBenchmarkTest: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(Runnable operation, String message) {
        boolean rejected = false;
        try {
            operation.run();
        } catch (AssertionError | IllegalArgumentException | ArithmeticException expected) {
            rejected = true;
        }
        check(rejected, message);
    }
}
