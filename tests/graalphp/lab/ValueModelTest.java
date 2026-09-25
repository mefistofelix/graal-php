package graalphp.lab;

import graalphp.runtime.PhpValues;

import graalphp.runtime.PhpValues.Location;
import graalphp.runtime.PhpValues.PhpArray;
import graalphp.runtime.PhpValues.Reference;
import graalphp.runtime.PhpValues.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ValueModelTest {
    private static final PhpValues.Heap HEAP = new PhpValues.Heap();
    private static final List<String> results = new ArrayList<>();

    private ValueModelTest() {}

    public static void main(String[] args) throws IOException {
        cow();
        references();
        nested();
        foreach();
        keys();
        lifetime();
        MutationTest.run();
        if (args.length == 1) {
            var oracle = Files.readAllLines(Path.of(args[0]));
            if (!results.equals(oracle)) {
                throw new AssertionError("PHP oracle mismatch\nPHP: " + oracle + "\nJava: " + results);
            }
            System.out.println("PASS: " + results.size() + " scenarios match PHP 8.6 oracle");
        } else {
            System.out.println("PASS: " + results.size() + " semantic scenarios + storage/lifetime invariants");
        }
    }

    private static void cow() {
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var b = scope.variable(a.read());
            equal(true, a.read() == b.read());
            b.element(0).set(9L);
            equal(false, a.read() == b.read());
            check("cow", "1,9", values(a.element(0), b.element(0)));
            var storage = b.read();
            b.element(1).set(8L);
            equal(true, b.read() == storage);
            a.assign(a);
            check("self_assignment", "1,2", values(a.element(0), a.element(1)));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L));
            a.element(0).assign(a);
            check("self_insertion_by_value", "1", values(a.element(0).element(0)));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L));
            var alias = scope.variable(null);
            alias.bind(a);
            var copy = scope.variable(a.read());
            alias.element(0).set(7L);
            check("array_alias", "7,7,1", values(a.element(0), alias.element(0), copy.element(0)));
            alias.set(4L);
            check("replace_aliased_array", "4,4,1", values(a, alias, copy.element(0)));
        }
    }

    private static void references() {
        try (var scope = new Scope(HEAP)) {
            var x = scope.variable(1L);
            var a = scope.variable(PhpArray.empty(HEAP));
            a.append().bind(x);
            var b = scope.variable(a.read());
            b.append().set(10L);
            x.set(3L);
            check("embedded_reference", "3,3,3,10", values(x, a.element(0), b.element(0), b.element(1)));
            b.element(0).set(6L);
            check("write_embedded_reference", "6,6,6", values(x, a.element(0), b.element(0)));
            var ordinary = scope.variable(x.read());
            ordinary.set(12L);
            check("dereference_assignment", "6,12", values(x, ordinary));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L));
            var b = scope.variable(a.read());
            var r = scope.variable(null);
            r.bind(a.element(0));
            r.set(2L);
            check("reference_after_copy", "2,1", values(a.element(0), b.element(0)));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L));
            var r = scope.variable(null);
            r.bind(a.element(0));
            r.unset();
            var b = scope.variable(a.read());
            b.element(0).set(2L);
            check("singleton_reference", "1,2", values(a.element(0), b.element(0)));
        }
        try (var scope = new Scope(HEAP)) {
            var x = scope.variable(1L);
            var a = scope.variable(PhpArray.empty(HEAP));
            a.append().bind(x);
            var r = scope.variable(null);
            r.bind(a.element(0));
            a.element(0).unset();
            a.element(0).set(99L);
            r.set(5L);
            check("unset_referenced_element", "5,5,99", values(x, r, a.element(0)));
        }
        try (var scope = new Scope(HEAP)) {
            var x = scope.variable(1L);
            var y = scope.variable(2L);
            var a = scope.variable(PhpArray.empty(HEAP));
            a.append().bind(x);
            var b = scope.variable(a.read());
            b.element(0).bind(y);
            y.set(8L);
            x.set(7L);
            check("rebind_copied_element", "7,8", values(a.element(0), b.element(0)));
            x.bind(x);
            check("self_bind", "7", values(x));
        }
    }

    private static void nested() {
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(null);
            a.element("x").element("y").set(1L);
            var b = scope.variable(a.read());
            b.element("x").element("y").set(2L);
            check("nested_cow", "1,2", values(a.element("x").element("y"), b.element("x").element("y")));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(null);
            a.element("x").element("y").set(1L);
            var r = scope.variable(null);
            r.bind(a.element("x").element("y"));
            var b = scope.variable(a.read());
            b.element("x").element("z").set(10L);
            r.set(2L);
            check("nested_reference", "2,2,10", values(a.element("x").element("y"), b.element("x").element("y"), b.element("x").element("z")));
            a.element("x").set(PhpArray.of(HEAP, 9L));
            r.set(3L);
            check("reference_survives_parent", "9,3,3", values(a.element("x").element(0), b.element("x").element("y"), r));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(null);
            a.element("x").element("y").set(1L);
            var b = scope.variable(a.read());
            var r = scope.variable(null);
            r.bind(a.element("x").element("y"));
            r.set(8L);
            check("nested_reference_after_copy", "8,1", values(a.element("x").element("y"), b.element("x").element("y")));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, PhpArray.of(HEAP, 1L)));
            var r = scope.variable(null);
            r.bind(a.element(0));
            var b = scope.variable(a.read());
            b.element(0).element(0).set(8L);
            check("array_valued_reference", "8,8,8", values(a.element(0).element(0), b.element(0).element(0), r.element(0)));
        }
    }

    private static void foreach() {
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L, 3L));
            var copy = scope.variable(a.read());
            var v = scope.variable(null);
            a.forEachReference(v, item -> item.set((long) item.read() * 2));
            v.set(9L);
            check("foreach_reference", "2,4,9,1,2,3", values(a.element(0), a.element(1), a.element(2), copy.element(0), copy.element(1), copy.element(2)));
            v.unset();
            var b = scope.variable(a.read());
            b.element(0).set(20L);
            b.element(2).set(30L);
            check("foreach_unset_alias", "2,9,20,30", values(a.element(0), a.element(2), b.element(0), b.element(2)));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            var v = scope.variable(null);
            a.forEachReference(v, item -> {});
            var b = scope.variable(a.read());
            b.element(0).set(10L);
            b.element(1).set(20L);
            check("foreach_lingering_alias", "1,20,10,20,20", values(a.element(0), a.element(1), b.element(0), b.element(1), v));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.empty(HEAP));
            var v = scope.variable(5L);
            a.forEachReference(v, item -> item.set(99L));
            check("foreach_empty", "5", values(v));
        }
    }

    private static void keys() {
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.empty(HEAP));
            a.element("8").set(1L);
            a.element(8).set(2L);
            a.element("08").set(3L);
            a.element("+8").set(4L);
            a.element("-0").set(5L);
            a.element("9223372036854775808").set(6L);
            check("key_normalization", "i:8,s:08,s:+8,s:-0,s:9223372036854775808", keyTypes(a));
            check("key_overwrite", "2,3,4,5,6", values(a.element(8), a.element("08"), a.element("+8"), a.element("-0"), a.element("9223372036854775808")));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.empty(HEAP));
            a.element(-5).set(1L);
            a.append().set(2L);
            a.element(-4).unset();
            a.append().set(3L);
            check("negative_append", "i:-5,i:-3", keyTypes(a));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L, 2L));
            a.element(1).unset();
            a.append().set(3L);
            a.element(0).unset();
            a.element(0).set(4L);
            check("append_and_order", "i:2,i:0", keyTypes(a));
            var b = scope.variable(a.read());
            b.append().set(5L);
            check("copy_append_index", "i:2,i:0,i:3", keyTypes(b));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.empty(HEAP));
            a.element(Long.MAX_VALUE).set(1L);
            boolean rejected = false;
            try {
                a.append();
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            check("append_overflow", "true", Boolean.toString(rejected));
        }
    }

    private static void lifetime() {
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L));
            var b = scope.variable(a.read());
            var storage = a.read();
            b.unset();
            a.element(0).set(2L);
            equal(true, storage == a.read());
            equal(false, b.exists());
            check("unset_array_alias", "2", values(a.element(0)));
            var r = scope.variable(null);
            try (var result = returnElementReference(a)) {
                r.bind(result);
            }
            r.set(7L);
            check("return_reference", "7,7", values(a.element(0), r));
        }
        try (var scope = new Scope(HEAP)) {
            var x = scope.variable(1L);
            var a = scope.variable(PhpArray.empty(HEAP));
            a.append().bind(x);
            x.unset();
            var b = scope.variable(a.read());
            b.element(0).set(2L);
            check("unset_external_alias", "1,2", values(a.element(0), b.element(0)));
        }
        try (var outer = new Scope(HEAP)) {
            var x = outer.variable(1L);
            try (var inner = new Scope(HEAP)) {
                var alias = inner.variable(null);
                alias.bind(x);
                alias.set(8L);
            }
            check("scope_release", "8", values(x));
        }
        try (var scope = new Scope(HEAP)) {
            var a = scope.variable(PhpArray.of(HEAP, 1L));
            try (var reference = a.element(0).reference()) {
                a.unset();
                reference.set(6L);
                check("direct_reference_lifetime", "6", reference.read().toString());
            }
        }
    }

    private static Reference returnElementReference(Location array) {
        return array.element(0).reference();
    }

    static String values(Location... locations) {
        return List.of(locations).stream().map(location -> String.valueOf(location.read())).collect(Collectors.joining(","));
    }

    private static String keyTypes(Location array) {
        return array.keys().stream().map(key -> (key instanceof Long ? "i:" : "s:") + key).collect(Collectors.joining(","));
    }

    static void check(String name, String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        results.add(name + "=" + actual);
    }

    static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
