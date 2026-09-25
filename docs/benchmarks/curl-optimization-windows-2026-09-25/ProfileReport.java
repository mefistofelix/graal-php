package graalphp;

import jdk.jfr.consumer.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Summarizes owner-thread JFR samples inside the benchmark's recorded traffic windows. */
public final class ProfileReport {
    private static final class Window {
        final String name, recording;
        final Instant start, end;
        final Map<String, Long> threads = new HashMap<>(), leaves = new HashMap<>(), inclusive = new HashMap<>();
        final Map<String, Long> allocations = new HashMap<>();
        long samples, allocationBytes, gcPauses, gcPauseNanos;
        Window(String[] row) {
            name = row[0] + "/" + row[1] + "/" + row[2] + "/" + row[3] + "/" + row[4];
            recording = row[0] + "-" + row[1] + "-" + row[2] + ".jfr";
            start = Instant.parse(row[5]); end = Instant.parse(row[6]);
        }
        boolean contains(Instant time) { return !time.isBefore(start) && time.isBefore(end); }
    }
    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args[0]);
        List<Window> windows = Files.readAllLines(directory.resolve("phases.csv")).stream()
                .skip(1).filter(line -> !line.isBlank()).map(line -> new Window(line.split(","))).toList();
        for (String name : windows.stream().map(window -> window.recording).distinct().toList()) {
            var selected = windows.stream().filter(window -> window.recording.equals(name)).toList();
            try (var recording = new RecordingFile(directory.resolve(name))) {
                while (recording.hasMoreEvents()) {
                    RecordedEvent event = recording.readEvent();
                    String type = event.getEventType().getName();
                    if (type.equals("jdk.GCPhasePause")) {
                        for (Window window : selected) if (window.contains(event.getStartTime())) {
                            window.gcPauses++;
                            window.gcPauseNanos += event.getDuration().toNanos();
                        }
                    } else if (type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample")
                            || type.equals("jdk.ObjectAllocationSample")) {
                        for (Window window : selected) if (window.contains(event.getStartTime())) add(window, event);
                    }
                }
            }
        }
        System.out.println("# Native Image JFR: traffic windows\n");
        System.out.println("Samples are observations, not exact CPU percentages. Windows Native Image uses a recurring-callback sampler. "
                + "Traffic windows exclude warmup, parked-idle and shutdown. "
                + "Allocation bytes are sampled estimates. Inclusive method counts overlap. "
                + "Leaf tables skip the recurring sampler and safepoint prologue.\n");
        for (Window window : windows) {
            System.out.println("## " + window.name + "\n");
            System.out.println("Window: " + window.start + " to " + window.end + ".\n");
            System.out.println("Owner samples: " + window.samples + "; sampled allocation weight: " + window.allocationBytes + " bytes.\n");
            System.out.println("Java GC pauses: " + window.gcPauses + "; total milliseconds: " + window.gcPauseNanos / 1e6 + ".\n");
            table("Sampled threads", window.threads, window.threads.values().stream().mapToLong(Long::longValue).sum(), 10);
            table("Owner leaf methods", window.leaves, window.samples, 20);
            table("Owner GraalPHP methods (inclusive)", window.inclusive, window.samples, 25);
            table("Owner allocated classes (sampled byte weight)", window.allocations, window.allocationBytes, 15);
        }
    }
    private static void add(Window window, RecordedEvent event) {
        boolean allocation = event.getEventType().getName().equals("jdk.ObjectAllocationSample");
        RecordedThread thread = allocation ? event.getThread() : event.getThread("sampledThread");
        String threadName = thread == null ? "unknown" : thread.getJavaName();
        if (!allocation) window.threads.merge(threadName, 1L, Long::sum);
        if (!"main".equals(threadName)) return;
        if (allocation) {
            long weight = event.getLong("weight");
            window.allocationBytes += weight;
            window.allocations.merge(event.getClass("objectClass").getName(), weight, Long::sum);
            return;
        }
        window.samples++;
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null || stack.getFrames().isEmpty()) return;
        stack.getFrames().stream().map(ProfileReport::method)
                .filter(name -> !name.startsWith("com.oracle.svm.core.thread.RecurringCallbackSupport")
                        && !name.startsWith("com.oracle.svm.core.thread.Safepoint"))
                .findFirst().ifPresent(name -> window.leaves.merge(name, 1L, Long::sum));
        Set<String> seen = new HashSet<>();
        for (RecordedFrame frame : stack.getFrames()) {
            String name = method(frame);
            if (name.startsWith("graalphp.") && seen.add(name)) window.inclusive.merge(name, 1L, Long::sum);
        }
    }
    private static String method(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        return method.getType().getName() + "." + method.getName();
    }
    private static void table(String title, Map<String, Long> values, long total, int limit) {
        System.out.println("### " + title + "\n\n| Name | Count / weight | Share |\n| --- | ---: | ---: |");
        values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                .forEach(entry -> System.out.printf(Locale.ROOT, "| `%s` | %d | %.2f%% |%n",
                        entry.getKey(), entry.getValue(), total == 0 ? 0 : entry.getValue() * 100.0 / total));
        System.out.println();
    }
}
