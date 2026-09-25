package graalphp.lab;

import graalphp.runtime.PhpValues;

import graalphp.runtime.PhpValues.Location;
import graalphp.runtime.PhpValues.PhpArray;
import graalphp.runtime.PhpValues.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import static graalphp.lab.ValueModelTest.check;
import static graalphp.lab.ValueModelTest.equal;
import static graalphp.lab.ValueModelTest.values;

final class MutationTest {
    private static final PhpValues.Heap HEAP = new PhpValues.Heap();
    private MutationTest() {}

    static void run() {
        foreachMutations();
        cycles();
        releaseInvariants();
    }

    private static void foreachMutations() {
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, item -> {
                seen.add(item.read());
                if (item.read().equals(1L)) a.append().set(3L);
                item.set((long) item.read() * 10);
            });
            check("foreach_append", "1,2,3,10,20,30", joined(seen) + "," + contents(a));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            try (var iterator = a.iterateReferences()) {
                while (iterator.next(v)) {
                    seen.add(v.read());
                    if (iterator.key.equals(0L)) a.element(1).unset();
                }
            }
            check("foreach_delete_next", "1,3", joined(seen));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            try (var iterator = a.iterateReferences()) {
                while (iterator.next(v)) {
                    seen.add(v.read());
                    a.element(iterator.key).unset();
                    v.set((long) v.read() * 10);
                }
            }
            check("foreach_delete_current", "1,2,0,20", joined(seen) + "," + a.keys().size() + "," + v.read());
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, item -> {
                seen.add(item.read());
                if (item.read().equals(1L)) {
                    a.element(1).unset();
                    a.element(1).set(4L);
                }
            });
            check("foreach_reinsert", "1,3,4", joined(seen));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, item -> {
                seen.add(item.read());
                if (item.read().equals(1L)) a.set(PhpArray.of(HEAP, 8L, 9L));
            });
            check("foreach_replace_array", "1,8,9,8,9", joined(seen) + "," + contents(a));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, item -> {
                seen.add(item.read());
                if (item.read().equals(1L)) a.unset();
            });
            check("foreach_unset_array", "1,2,3,0", joined(seen) + "," + (a.exists() ? 1 : 0));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            boolean invalidSource = false;
            try {
                a.forEachReference(v, item -> {
                    seen.add(item.read());
                    if (item.read().equals(1L)) a.set(8L);
                });
            } catch (IllegalStateException expected) {
                invalidSource = true;
            }
            check("foreach_replace_scalar", "1,8,1", joined(seen) + "," + a.read() + "," + (invalidSource ? 1 : 0));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var b = scope.variable(PhpArray.of(HEAP, 8L, 9L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, item -> {
                seen.add(item.read());
                if (item.read().equals(1L)) a.bind(b);
            });
            check("foreach_rebind_array", "1,2,8,9,8,9", joined(seen) + "," + contents(a) + "," + contents(b));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var b = scope.variable(PhpArray.empty(HEAP));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, item -> {
                seen.add(item.read());
                if (item.read().equals(1L)) b.assign(a);
                if (item.read().equals(2L)) a.append().set(4L);
                item.set((long) item.read() * 10);
            });
            check("foreach_copy_then_write", "1,2,3,4,10,20,30,40,10,20,3", joined(seen) + "," + contents(a) + "," + contents(b));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var v = scope.variable(null);
            var w = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, outer -> a.forEachReference(w, inner -> {
                seen.add(outer.read() + ":" + inner.read());
                if (outer.read().equals(1L) && inner.read().equals(1L)) a.append().set(3L);
            }));
            check("foreach_nested_mutation", "1:1,1:2,1:3,2:1,2:2,2:3,3:1,3:2,3:3", joined(seen));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            a.forEachReference(v, item -> {
                seen.add(item.read());
                if (item.read().equals(1L)) {
                    a.element(0).unset();
                    a.element(1).unset();
                    a.append().set(3L);
                }
            });
            check("foreach_clear_append", "1,3,3", joined(seen) + "," + contents(a));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var v = scope.variable(null);
            var seen = new ArrayList<Object>();
            try (var iterator = a.iterateReferences()) {
                while (iterator.next(v)) {
                    seen.add(v.read());
                    break;
                }
            }
            v.set(7L);
            check("foreach_break_alias", "1,7,2", joined(seen) + "," + contents(a));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var v = scope.variable(null);
            try {
                a.forEachReference(v, item -> { throw new IllegalArgumentException("stop"); });
            } catch (IllegalArgumentException expected) {
                equal("stop", expected.getMessage());
                v.set(7L);
            }
            check("foreach_exception_alias", "7,2", contents(a));
        }
    }

    private static void cycles() {
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.empty(HEAP));
            a.element("value").set(1L);
            a.element("self").bind(a);
            a.element("self").element("value").set(2L);
            check("cycle_self", "2,2", values(a.element("value"), a.element("self").element("self").element("value")));
            var b = scope.variable(a.read());
            b.element("value").set(3L);
            check("cycle_cow", "2,3,2", values(a.element("value"), b.element("value"), b.element("self").element("value")));
            b.element("self").element("value").set(4L);
            check("cycle_cow_reference", "4,3,4", values(a.element("value"), b.element("value"), b.element("self").element("value")));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.empty(HEAP));
            var b = scope.variable(PhpArray.empty(HEAP));
            a.element("value").set(1L);
            b.element("value").set(2L);
            a.element("b").bind(b);
            b.element("a").bind(a);
            a.element("b").element("a").element("value").set(7L);
            check("cycle_mutual", "7,7,2", values(a.element("value"), b.element("a").element("value"), a.element("b").element("value")));
            a.element("b").unset();
            b.element("a").element("value").set(8L);
            check("cycle_break_edge", "8,8", values(a.element("value"), b.element("a").element("value")));
        }
        try (var scope = new Scope(HEAP)) {
            var x = scope.variable(1L);
            var a = scope.variable(PhpArray.empty(HEAP));
            a.element("self").bind(a);
            a.element("x").bind(x);
            a.unset();
            equal(2, PhpValues.collectCycles(HEAP));
            var b = scope.variable(PhpArray.empty(HEAP));
            b.append().bind(x);
            x.unset();
            var c = scope.variable(b.read());
            c.element(0).set(2L);
            check("cycle_release_external_reference", "1,2", values(b.element(0), c.element(0)));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.empty(HEAP));
            a.element("value").set(1L);
            a.element("self").bind(a);
            var r = scope.variable(null);
            r.bind(a.element("self"));
            a.unset();
            equal(0, PhpValues.collectCycles(HEAP));
            r.element("value").set(9L);
            check("cycle_live_reference", "9", values(r.element("self").element("value")));
        }
    }

    private static void releaseInvariants() {
        equal(0L, PhpValues.statistics(HEAP).liveArrays());
        equal(0L, PhpValues.statistics(HEAP).liveCells());
        try (var scope = new Scope(HEAP)) {
            Object nested = 1L;
            for (int i = 0; i < 50_000; i++) nested = PhpArray.of(HEAP, nested);
            var a = scope.variable(nested);
            a.unset();
            equal(0L, PhpValues.statistics(HEAP).liveArrays());
        }
        for (int i = 0; i < 1_000; i++) {
            try (var scope = new Scope(HEAP)) {
                var a = scope.variable(PhpArray.empty(HEAP));
                a.element("self").bind(a);
            }
        }
        equal(0L, PhpValues.statistics(HEAP).liveArrays());
        equal(0L, PhpValues.statistics(HEAP).liveCells());
        equal(0, PhpValues.statistics(HEAP).cycleCandidates());
    }

    private static String contents(Location array) {
        return array.keys().stream().map(key -> String.valueOf(array.element(key).read())).collect(Collectors.joining(","));
    }

    private static String joined(List<Object> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
