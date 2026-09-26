package graalphp.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Verify live-field cursor ownership directly, including object cycles and escaped aliases. */
public final class FieldIterationTest {
    private static int checks;

    public static void main(String[] args) {
        cursorPinsObject();
        dynamicAndDeclaredOrder();
        independentCursors();
        aliasOutlivesObject();
        cyclicOwner();
        cloneAndReadonly();
        failedWritesDoNotPublishFields();
        System.out.println("PASS: " + checks + " field-iteration ownership and ordering checks");
    }

    private static void cursorPinsObject() {
        var heap = new PhpValues.Heap();
        var roots = new PhpValues.Scope(heap);
        var object = new PhpValues.PhpObject(heap, "fixture");
        roots.variable(object);
        object.field("a").set(roots.array(1L));
        var iterator = object.iterateFields();
        roots.close();
        equal(1L, heap.liveObjects, "cursor pins object after all variable roots close");
        equal(1L, heap.liveArrays, "cursor transitively pins field array");
        check(iterator.next(), "first field survives root closure");
        equal("a", iterator.name(), "field name");
        Object value = PhpValues.element(iterator.location().read(), 0L);
        try { equal(1L, PhpValues.unwrap(value), "field payload"); } finally { PhpValues.drop(value); }
        iterator.close();
        iterator.close();
        empty(heap);
    }

    private static void dynamicAndDeclaredOrder() {
        var heap = new PhpValues.Heap();
        try (var scope = new PhpValues.Scope(heap)) {
            var object = new PhpValues.PhpObject(heap, "fixture");
            scope.variable(object);
            object.field("a").set(1L);
            object.field("missing").readOrNull();
            object.field("b").set(2L);
            object.fieldType("declared", null);
            object.field("declared").set(3L);
            equal(List.of("a", "b", "declared"), object.fieldNames(), "undefined probe has no bucket");
            try (var cursor = object.iterateFields()) {
                check(cursor.next(), "first dynamic property");
                equal("a", cursor.name(), "first dynamic key");
                object.field("a").unset();
                object.field("a").set(4L);
                object.field("declared").unset();
                object.field("declared").set(5L);
                var names = new ArrayList<String>();
                while (cursor.next()) names.add(cursor.name());
                equal(List.of("b", "declared", "a"), names, "dynamic reinsertion appends, declared reinsertion stays");
            }
        }
        empty(heap);
    }

    private static void independentCursors() {
        var heap = new PhpValues.Heap();
        var roots = new PhpValues.Scope(heap);
        var object = new PhpValues.PhpObject(heap, "fixture");
        roots.variable(object);
        object.field("first").set(1L);
        var first = object.iterateFields();
        var second = object.iterateFields();
        check(first.next(), "cursor one starts");
        check(second.next(), "cursor two starts independently");
        object.field("later").set(2L);
        roots.close();
        first.close();
        equal(1L, heap.liveObjects, "second cursor still owns object");
        check(second.next(), "second cursor sees appended field");
        equal("later", second.name(), "second cursor position");
        check(!second.next(), "second cursor ends");
        second.close();
        check(!second.next(), "closed cursor does not resurrect owner");
        empty(heap);
    }

    private static void aliasOutlivesObject() {
        var heap = new PhpValues.Heap();
        var roots = new PhpValues.Scope(heap);
        var object = new PhpValues.PhpObject(heap, "fixture");
        roots.variable(object);
        object.field("a").set(roots.array(7L));
        var cursor = object.iterateFields();
        check(cursor.next(), "alias source exists");
        try (var alias = cursor.location().reference()) {
            roots.close();
            cursor.close();
            equal(0L, heap.liveObjects, "alias cell does not own former object container");
            equal(1L, heap.liveCells, "alias cell remains alive");
            equal(1L, heap.liveArrays, "alias cell owns payload independently");
            Object value = PhpValues.element(alias.read(), 0L);
            try { equal(7L, PhpValues.unwrap(value), "escaped alias payload"); } finally { PhpValues.drop(value); }
            alias.set(9L);
            equal(0L, heap.liveArrays, "overwritten payload released");
        }
        empty(heap);
    }

    private static void cyclicOwner() {
        var heap = new PhpValues.Heap();
        var roots = new PhpValues.Scope(heap);
        var object = new PhpValues.PhpObject(heap, "fixture");
        roots.variable(object);
        object.field("self").set(object);
        var cursor = object.iterateFields();
        roots.close();
        heap.collectCycles();
        equal(1L, heap.liveObjects, "cursor root protects object cycle");
        check(cursor.next(), "self field survives collection");
        check(cursor.location().read() == object, "self field identity");
        cursor.close();
        heap.collectCycles();
        empty(heap);
    }

    private static void cloneAndReadonly() {
        var heap = new PhpValues.Heap();
        try (var scope = new PhpValues.Scope(heap)) {
            var object = new PhpValues.PhpObject(heap, "fixture");
            scope.variable(object);
            object.field("gone").set(1L);
            object.field("gone").unset();
            object.field("kept").set(2L);
            object.fieldType("declared", null);
            object.field("declared").set(3L);
            var copy = object.copyObject();
            scope.variable(copy);
            try (var cursor = copy.iterateFields()) {
                check(cursor.next(), "clone first live field");
                equal("kept", cursor.name(), "clone excludes deleted dynamic bucket");
                copy.field("declared").unset();
                copy.field("declared").set(4L);
                check(cursor.next(), "clone retains declared bucket");
                equal("declared", cursor.name(), "clone declared position");
                check(!cursor.next(), "clone has no duplicate declared field");
            }
            copy.sealEnum("Fixture");
            try (var cursor = copy.iterateFields()) {
                check(cursor.next(), "readonly field is readable by cursor");
                rejects(() -> cursor.location().set(8L), "readonly write rejected");
                rejects(() -> { try (var ignored = cursor.location().reference()) {} }, "readonly reference rejected");
            }
        }
        empty(heap);
    }

    private static void failedWritesDoNotPublishFields() {
        var heap = new PhpValues.Heap();
        try (var scope = new PhpValues.Scope(heap)) {
            var object = new PhpValues.PhpObject(heap, "fixture");
            scope.variable(object);
            object.field("name").set("A");
            object.sealEnum("Fixture");
            rejects(() -> object.field("new").set(1L), "sealed dynamic write rejected");
            equal(List.of("name"), object.fieldNames(), "failed sealed write creates no bucket");
        }
        empty(heap);
    }

    private static void empty(PhpValues.Heap heap) {
        heap.collectCycles();
        equal(0L, heap.liveObjects, "no live objects");
        equal(0L, heap.liveArrays, "no live arrays");
        equal(0L, heap.liveCells, "no live reference cells");
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static void equal(Object expected, Object actual, String label) {
        check(Objects.equals(expected, actual), label + ": expected=" + expected + ", actual=" + actual);
    }
    private static void rejects(Runnable operation, String label) {
        boolean rejected = false;
        try { operation.run(); } catch (PhpError expected) { rejected = true; }
        check(rejected, label);
    }
}
