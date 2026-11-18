package graalphp.runtime;

import java.util.*;
import java.util.function.Consumer;

/** Compare reclamation with graph reachability, including duplicate edges and live tails. */
public final class CycleCollectorTest {
    private static final class Node extends PhpValues.HeapNode {
        final List<Node> edges = new ArrayList<>();
        int discarded;
        Node(PhpValues.Heap heap) { super(heap); }
        void link(Node child) { edges.add(child); PhpValues.retain(child); }
        @Override void children(Consumer<PhpValues.HeapNode> visit) { edges.forEach(visit); }
        @Override void discard() {
            if (++discarded != 1) throw new AssertionError("Resource destroyed twice");
            edges.clear();
        }
    }
    public static void main(String[] args) {
        futureResults();
        var random = new Random(862026);
        for (int trial = 0; trial < 256; trial++) {
            var heap = new PhpValues.Heap();
            var nodes = new Node[48];
            var roots = new boolean[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = new Node(heap); roots[i] = true; PhpValues.retain(nodes[i]);
            }
            // Several components make both reachable and unreachable cycles likely.
            for (int i = 0; i < nodes.length; i++) for (int j = 0, count = random.nextInt(4); j < count; j++)
                nodes[i].link(nodes[i / 8 * 8 + random.nextInt(8)]);
            // Duplicate edges must each subtract a reference; a dead cycle can point to a live tail.
            nodes[0].link(nodes[0]); nodes[0].link(nodes[1]); nodes[0].link(nodes[1]);
            nodes[0].link(nodes[47]);
            for (int phase = 0; phase < 5; phase++) {
                for (int i = 0; i < nodes.length; i++) if (roots[i] && (phase == 4 || random.nextBoolean())) {
                    roots[i] = false; PhpValues.release(nodes[i]);
                }
                var reachable = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
                var pending = new ArrayDeque<Node>();
                for (int i = 0; i < nodes.length; i++) if (roots[i]) pending.add(nodes[i]);
                while (!pending.isEmpty()) {
                    var node = pending.removeLast();
                    if (reachable.add(node)) pending.addAll(node.edges);
                }
                int before = (int) Arrays.stream(nodes).filter(node -> node.discarded == 0).count();
                int collected = heap.collectCycles();
                if (collected != before - reachable.size()) throw new AssertionError("Wrong collection count");
                for (var node : nodes) if ((node.discarded == 0) != reachable.contains(node))
                    throw new AssertionError("Reclamation differs from reachability");
                if (heap.collectCycles() != 0) throw new AssertionError("Repeated collection is not idempotent");
                // A new decrement makes surviving graphs candidates again in a later epoch.
                for (int i = 0; i < nodes.length; i++) if (roots[i]) {
                    PhpValues.retain(nodes[i]); PhpValues.release(nodes[i]);
                }
            }
        }
        System.out.println("PASS: 256 ownership graphs, 1280 collection phases, exactly-once resource release");
    }

    private static void futureResults() {
        var heap = new PhpValues.Heap();
        var source = new Scheduler.Future(heap);
        var attached = new Scheduler.Future(heap);
        var abandoned = new Scheduler.Future(heap);
        PhpValues.retain(source);
        PhpValues.retain(attached);
        PhpValues.retain(abandoned);
        attached.follow(source);
        abandoned.follow(source);
        PhpValues.release(abandoned);

        var result = new Node(heap);
        source.completeValue(PhpValues.own(result));
        if (abandoned.completion.isDone()) throw new AssertionError("Disposed target was completed");
        if (Scheduler.result(attached) != result) throw new AssertionError("Result was lost");
        PhpValues.release(source);
        if (result.discarded != 0) throw new AssertionError("Linked result lost its live owner");
        PhpValues.release(attached);
        if (result.discarded != 1) throw new AssertionError("Completed result survived its last owner");

        var cyclic = new Scheduler.Future(heap);
        PhpValues.retain(cyclic);
        try (var scope = new PhpValues.Scope(heap)) {
            var array = scope.variable(scope.array(cyclic));
            cyclic.completeValue(array.read());
        }
        if (heap.liveArrays != 1) throw new AssertionError("Cycles reachable from a future must stay live");
        PhpValues.release(cyclic);
        if (heap.collectCycles() != 2 || heap.liveArrays != 0 || cyclic.completion.getNow(null) != null) {
            throw new AssertionError("Unreachable future/array cycle was not cleared");
        }
        System.out.println("PASS: future result release, detached link and future/array cycle");
    }
}
