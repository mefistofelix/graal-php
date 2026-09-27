package graalphp.runtime;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;
import com.oracle.truffle.api.interop.TruffleObject;

/** Request-confined ownership, COW and references for the PHP 8.6 target. */
public final class PhpValues {
    public static final String PHP_TARGET = "8.6";


    private PhpValues() {}

    public static final class Owned implements AutoCloseable {
        public final Object value;
        private boolean closed;
        private Set<Owned> owner;
        private Owned(Object value) { this.value = value; retain(value); }
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void track(Set<Owned> next) {
            if (owner != null) owner.remove(this);
            owner = next;
            if (next != null && !closed) next.add(this);
        }
        @Override @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void close() {
            if (!closed) { closed = true; track(null); release(value); }
        }
    }

    public static Object own(Object value) { return value instanceof HeapNode ? new Owned(value) : value; }
    public static Object unwrap(Object value) { return value instanceof Owned owned ? owned.value : value; }
    public static void drop(Object value) { if (value instanceof Owned owned) owned.close(); }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static List<Object> keys(Object value) {
        if (!(unwrap(value) instanceof PhpArray array)) throw new PhpError("Expected an array");
        return List.copyOf(array.entries.keySet());
    }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object element(Object value, Object key) { return element(value, key, null); }
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static Object element(Object value, Object key, Diagnostics.Origin origin) {
        Object raw = unwrap(value);
        if (StringOffsets.isString(raw)) return StringOffsets.read(raw, key, StringOffsets.ReadMode.NORMAL, origin);
        if (!(raw instanceof PhpArray array)) throw new PhpError("Error", "Cannot access an array offset on this value");
        var slot = array.entries.get(normalizeKey(key));
        if (slot == null) throw new PhpError("Undefined array key: " + key);
        return own(slot.read());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static void copyElement(Object array, Object key, Location destination) {
        var slot = ((PhpArray) unwrap(array)).entries.get(normalizeKey(key));
        if (slot == null) throw new PhpError("Error", "Undefined array key");
        destination.copySlot(slot);
    }

    public record Statistics(long storageCopies, long liveArrays, long liveCells, int cycleCandidates) {}

    public static Statistics statistics(Heap heap) {

        return new Statistics(heap.storageCopies, heap.liveArrays, heap.liveCells, heap.candidates.size());
    }

    /** Returns reclaimed model nodes, not PHP's gc_collect_cycles() count. */
    public static int collectCycles(Heap heap) {
        return heap.collectCycles();
    }

    public static final class Scope implements AutoCloseable {
        public final Heap heap;

        public Scope(Heap heap) { this.heap = heap; }
        public PhpArray array(Object... values) { return PhpArray.of(heap, values); }
        public PhpArray emptyArray() { return PhpArray.empty(heap); }

        private final List<Location> variables = new ArrayList<>();

        public Location variable(Object value) {
            var location = new Location(new Slot(heap), null, null);
            location.set(value);
            variables.add(location);
            return location;
        }

        @Override
        public void close() {
            variables.forEach(location -> location.local.clear());
            variables.clear();
            heap.collectCycles();
        }
    }

    /** A location is resolved for each operation; a bound reference captures a cell. */
    public static final class Location {
        private static final Object APPEND = new Object();
        private final Slot local;
        private final Location parent;
        private Object key;
        private boolean readonly;
        private Diagnostics.Origin origin;
        public Location at(Execution.Activation caller) { origin = Diagnostics.origin(caller); return this; }
        public Location freeze() { readonly = true; return this; }

        private Location(Slot local, Location parent, Object key) {
            this.local = local;
            this.parent = parent;
            this.key = key;
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Location element(Object key) {
            return new Location(null, this, key);
        }

        public Location deferredAppend() { return new Location(null, this, APPEND); }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Object readOrNull() { return read(StringOffsets.ReadMode.COALESCE); }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Object probe() { return read(StringOffsets.ReadMode.PROBE); }

        private Object read(StringOffsets.ReadMode mode) {
            if (parent != null) {
                Object container = parent.readOrNull();
                if (StringOffsets.isString(container)) {
                    if (key == APPEND) throw new PhpError("Error", "[] operator not supported for strings");
                    return StringOffsets.read(container, key, mode, origin);
                }
            }
            var slot = resolve(false);
            if (slot == null && mode == StringOffsets.ReadMode.NORMAL) throw new NoSuchElementException("Undefined location: " + key);
            return slot == null ? null : slot.read();
        }

        private boolean stringOffset() { return parent != null && StringOffsets.isString(parent.readOrNull()); }

        public Object readForAssignOperation() { return stringOffset() ? null : read(); }

        public void checkIncrement() {
            if (stringOffset()) throw new PhpError("Error", "Cannot increment/decrement string offsets");
        }

        public void checkAssignOperation() {
            if (stringOffset()) throw new PhpError("Error", "Cannot use assign-op operators with string offsets");
        }

        /** Borrowed value: store it in a location to give it an owning lifetime. */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Object read() {
            return read(StringOffsets.ReadMode.NORMAL);
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void set(Object value) { write(value); }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Object write(Object value) {
            if (readonly) throw new PhpError("Error", "Cannot modify readonly property");
            validateValue(value);
            if (stringOffset()) {
                if (parent.stringOffset()) throw new PhpError("Error", "Cannot use string offset as an array");
                if (key == APPEND) throw new PhpError("Error", "[] operator not supported for strings");
                parent.resolve(true);
                var written = StringOffsets.write(parent.read(), key, value, origin);
                if (written.string() != null) parent.set(written.string());
                return written.result();
            }
            // Preserve the RHS before resolving a destination inside that same array.
            retain(value);
            try {
                var slot = resolve(true);
                slot.set(value, origin);
                return slot.read();
            } finally {
                release(value);
            }
        }

        public void assign(Location source) {
            set(source.read());
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void bind(Location source) {
            try (var reference = source.reference()) {
                bind(reference);
            }
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void copyValueFrom(Location other) {
            var slot = other.resolve(false);
            if (slot == null) set(null); else copySlot(slot);
        }
        private void copySlot(Slot slot) {
            if (slot.content instanceof Cell cell && cell.owners > 1) {
                try (var reference = new Reference(cell)) { bind(reference); }
            } else set(slot.read());
        }

        public void bind(Reference reference) {
            if (stringOffset()) throw new PhpError("Error", "Cannot create references to/from string offsets");
            resolve(true).bind(reference.cell());
        }

        public Reference reference() {
            if (stringOffset()) throw new PhpError("Error", "Cannot create references to/from string offsets");
            if (parent == null && local.readonlyLabel != null)
                throw new PhpError("Error", "Cannot acquire reference to readonly property " + local.readonlyLabel);
            return new Reference(resolve(true).reference());
        }

        public boolean exists() { return stringOffset() ? probe() != null : resolve(false) != null; }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void unset() {
            if (stringOffset()) throw new PhpError("Error", "Cannot unset string offsets");
            if (readonly) throw new PhpError("Error", "Cannot unset readonly property");
            if (parent == null) {
                if (local.readonlyLabel != null) throw new PhpError("Error", "Cannot unset readonly property " + local.readonlyLabel);
                if (local.container != null && !local.declared) local.container.fields.remove(local.fieldName, local);
                local.clear();
                return;
            }
            if (!exists()) return;
            var array = parent.writableArray();
            array.entries.remove(normalizeKey(key)).clear();
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Location append() {
            var array = writableArray();
            long index = array.nextIndex;
            if (array.entries.containsKey(index)) {
                throw new IllegalStateException("Cannot append: next integer key is occupied");
            }
            var location = element(index);
            location.set(null);
            return location;
        }

        public List<Object> keys() {
            return List.copyOf(((PhpArray) read()).entries.keySet());
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public ReferenceIterator iterateReferences() {
            if (!(read() instanceof PhpArray)) throw new IllegalStateException("foreach requires an array");
            writableArray();
            return new ReferenceIterator(reference());
        }

        public void forEachReference(Location variable, Consumer<Location> body) {
            try (var iterator = iterateReferences()) {
                while (iterator.next(variable)) body.accept(variable);
            }
        }

        private Slot resolve(boolean write) {
            if (write && readonly) throw new PhpError("Error", "Cannot modify readonly property");
            if (parent == null) {
                Slot slot = local;
                if (write) local.checkWritable();
                if (local.container != null) {
                    var current = local.container.fields.get(local.fieldName);
                    if (current != null) slot = current;
                    else if (write) local.container.attach(local.fieldName, local);
                    else return null;
                }
                if (write) slot.checkWritable();
                return write || slot.defined ? slot : null;
            }
            PhpArray array;
            if (write) {
                array = parent.writableArray();
                if (key == APPEND) {
                    key = array.nextIndex;
                    if (array.entries.containsKey(key)) throw new PhpError("Cannot append: next integer key is occupied");
                }
            } else {
                if (key == APPEND) throw new PhpError("Cannot read an append location");
                var parentSlot = parent.resolve(false);
                if (parentSlot == null || parentSlot.read() == null) return null;
                if (!(parentSlot.read() instanceof PhpArray value)) throw new PhpError("Cannot access an array offset on this value");
                array = value;
            }
            Object index = normalizeKey(key);
            var slot = array.entries.get(index);
            if (slot == null && write) {
                slot = new Slot(array.heap);
                array.insert(index, slot);
                array.recordKey(index);
            }
            return slot;
        }

        private PhpArray writableArray() {
            var slot = resolve(true);
            if (slot.read() == null) slot.set(PhpArray.empty(slot.heap));
            if (!(slot.read() instanceof PhpArray value)) throw new PhpError("Cannot write an array offset on this value");
            var array = value;
            if (array.owners > 1) {
                array = array.copyStorage();
                slot.set(array);
            }
            return array;
        }
    }

    public static final class PhpArray extends HeapNode {
        private final LinkedHashMap<Object, Slot> entries = new LinkedHashMap<>();
        private final List<Bucket> order = new ArrayList<>();
        private final Set<ReferenceIterator> iterators = Collections.newSetFromMap(new IdentityHashMap<>());
        private long nextIndex;
        private boolean hasIntegerKey;

        private PhpArray(Heap heap) {
            super(heap);
            heap.liveArrays++;
        }

        public static PhpArray empty(Heap heap) {
            return new PhpArray(heap);
        }

        public static PhpArray of(Heap heap, Object... values) {
            var array = empty(heap);
            for (var value : values) {
                validateValue(value);
                var slot = new Slot(array.heap);
                slot.set(value);
                array.insert(array.nextIndex, slot);
                array.recordKey(array.nextIndex);
            }
            return array;
        }

        private void recordKey(Object key) {
            if (!(key instanceof Long index)) return;
            if (!hasIntegerKey || index >= nextIndex) {
                nextIndex = index == Long.MAX_VALUE ? Long.MAX_VALUE : index + 1;
            }
            hasIntegerKey = true;
        }

        private PhpArray copyStorage() {
            var copy = empty(heap);
            for (var bucket : order) {
                if (bucket.slot.defined) {
                    copy.insert(bucket.key, bucket.slot.copyForArray());
                } else {
                    copy.order.add(bucket);
                }
            }
            copy.nextIndex = nextIndex;
            copy.hasIntegerKey = hasIntegerKey;
            for (var iterator : iterators) {
                iterator.register(copy, iterator.positions.get(this));
            }
            heap.storageCopies++;
            return copy;
        }

        private void insert(Object key, Slot slot) {
            entries.put(key, slot);
            order.add(new Bucket(key, slot));
        }

        @Override
        void children(Consumer<HeapNode> visit) {
            for (var slot : entries.values()) {
                if (slot.content instanceof HeapNode node) visit.accept(node);
            }
        }

        @Override
        void discard() {
            entries.values().forEach(Slot::forget);
            entries.clear();
            order.clear();
            for (var iterator : iterators) iterator.positions.remove(this);
            iterators.clear();
            heap.liveArrays--;
        }
    }

    /** Identity objects and captured environments participate in the same ownership graph as arrays. */
    public static final class PhpObject extends HeapNode implements TruffleObject {
        public final Object descriptor;
        private final LinkedHashMap<String, Slot> fields = new LinkedHashMap<>();
        private final ArrayList<FieldBucket> fieldOrder = new ArrayList<>();
        private String sealedType;

        public PhpObject(Heap heap, Object descriptor) {
            super(heap);
            this.descriptor = descriptor;
            heap.liveObjects++;
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Location field(String name) {
            Slot slot = fields.get(name);
            if (slot == null && sealedType != null) {
                slot = new Slot(heap);
                slot.writeError = "Cannot create dynamic property " + sealedType + "::$" + name;
            } else if (slot == null) {
                slot = new Slot(heap);
                slot.container = this;
                slot.fieldName = name;
            }
            return new Location(slot, null, null);
        }

        public void sealEnum(String type) {
            for (var entry : fields.entrySet()) entry.getValue().readonlyLabel = type + "::$" + entry.getKey();
            sealedType = type;
        }

        public PhpObject copyObject() {
            var copy = new PhpObject(heap, descriptor);
            for (var entry : fields.entrySet()) {
                var slot = entry.getValue();
                var cloned = slot.copyForArray();
                cloned.defined = slot.defined;
                cloned.type = slot.type;
                cloned.readonlyLabel = slot.readonlyLabel;
                cloned.writeError = slot.writeError;
                cloned.declared = slot.declared;
                copy.attach(entry.getKey(), cloned);
            }
            copy.sealedType = sealedType;
            return copy;
        }

        private void attach(String name, Slot slot) {
            slot.container = this;
            slot.fieldName = name;
            fields.put(name, slot);
            fieldOrder.add(new FieldBucket(name, slot));
        }
        public void fieldType(String name, String type) {
            Slot slot = fields.get(name);
            if (slot == null) { slot = new Slot(heap); attach(name, slot); }
            slot.type = type;
            slot.declared = true;
        }
        public List<String> fieldNames() { return List.copyOf(fields.keySet()); }
        public FieldCursor iterateFields() { return new FieldCursor(this); }

        @Override void children(Consumer<HeapNode> visit) {
            for (var slot : fields.values()) if (slot.content instanceof HeapNode node) visit.accept(node);
        }

        @Override void discard() {
            if (descriptor instanceof GeneratorApi.State generator) generator.close();
            fields.values().forEach(Slot::forget);
            fields.clear();
            fieldOrder.clear();
            heap.liveObjects--;
        }
    }

    private record Bucket(Object key, Slot slot) {}

    /** Holds the source reference, so unset/rebind of its variable does not redirect the loop. */
    public static final class ReferenceIterator implements AutoCloseable {
        private final Reference source;
        private final IdentityHashMap<PhpArray, Integer> positions = new IdentityHashMap<>();
        public Object key;

        private ReferenceIterator(Reference source) {
            this.source = source;
            register((PhpArray) source.read(), 0);
        }

        private void register(PhpArray array, int position) {
            positions.put(array, position);
            array.iterators.add(this);
        }

        public boolean next(Location variable) {
            if (!(source.read() instanceof PhpArray array)) {
                throw new IllegalStateException("foreach source is no longer an array");
            }
            if (!positions.containsKey(array)) register(array, 0);
            int position = positions.get(array);
            while (position < array.order.size()) {
                var bucket = array.order.get(position++);
                positions.put(array, position);
                if (!bucket.slot.defined) continue;
                key = bucket.key;
                // Fetch-by-reference promotes the live bucket, even when a copy shares it.
                // COW happens at reset or a subsequent write, not at every foreach fetch.
                try (var reference = new Reference(bucket.slot.reference())) {
                    variable.bind(reference);
                }
                return true;
            }
            return false;
        }

        @Override
        public void close() {
            for (var array : positions.keySet()) array.iterators.remove(this);
            positions.clear();
            source.close();
        }
    }

    private record FieldBucket(String name, Slot slot) {}

    /** Live object property order: deleted dynamic slots stay as holes; reinsertion appends. */
    public static final class FieldCursor implements AutoCloseable {
        private PhpObject object;
        private Object owner;
        private int position;
        private FieldBucket current;
        private FieldCursor(PhpObject object) { this.object = object; owner = own(object); }
        public PhpObject object() { return object; }
        public String name() { return current.name; }
        public Location location() { return new Location(current.slot, null, null); }
        public boolean next() {
            while (object != null && position < object.fieldOrder.size()) {
                current = object.fieldOrder.get(position++);
                if (current.slot.defined) return true;
            }
            current = null;
            return false;
        }
        @Override public void close() { drop(owner); owner = null; object = null; current = null; }
    }

    /** An owning, direct reference, also usable as a return-by-reference result. */
    public static final class Reference implements AutoCloseable {
        private Cell cell;

        private Reference(Cell cell) {
            this.cell = cell;
            cell.owners++;
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public Object read() {
            return cell().value;
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public void set(Object value) {
            validateValue(value);
            cell().set(value);
        }

        private Cell cell() {
            if (cell == null) throw new IllegalStateException("Reference has been closed");
            return cell;
        }

        @Override
        public void close() {
            if (cell == null) return;
            release(cell);
            cell = null;
        }
    }

    private static final class Cell extends HeapNode {
        Object value;

        Cell(Heap heap, Object value) {
            super(heap);
            this.value = value;
            owners = 1;
            heap.liveCells++;
        }

        void set(Object replacement) {
            retain(replacement);
            release(value);
            value = replacement;
        }

        @Override
        void children(Consumer<HeapNode> visit) {
            if (value instanceof HeapNode node) visit.accept(node);
        }

        @Override
        void discard() {
            value = null;
            heap.liveCells--;
        }
    }

    private static final class Slot {
        final Heap heap;
        Slot(Heap heap) { this.heap = heap; }
        Object content;
        boolean defined;
        String type;
        String readonlyLabel;
        String writeError;
        PhpObject container;
        String fieldName;
        boolean declared;

        void checkWritable() {
            if (writeError != null) throw new PhpError("Error", writeError);
            if (readonlyLabel != null) throw new PhpError("Error", "Cannot modify readonly property " + readonlyLabel);
        }

        Object read() {
            return content instanceof Cell cell ? cell.value : content;
        }

        void set(Object value) { set(value, null); }
        void set(Object value, Diagnostics.Origin origin) {
            if (type != null) value = TypeRelations.check(value, type, origin != null && origin.site().strictTypes(), origin);
            if (value instanceof HeapNode node && node.heap != heap) {
                throw new IllegalArgumentException("PHP containers must belong to the same request heap");
            }
            defined = true;
            if (content instanceof Cell cell) {
                cell.set(value);
                return;
            }
            retain(value);
            release(content);
            content = value;
        }

        Cell reference() {
            if (type != null) throw new PhpError("References to typed properties are not implemented");
            defined = true;
            if (content instanceof Cell cell) return cell;
            // Transfer the owned value to the cell; do not acquire it a second time.
            var cell = new Cell(heap, content);
            content = cell;
            return cell;
        }

        void bind(Cell cell) {
            defined = true;
            cell.owners++;
            release(content);
            content = cell;
        }

        Slot copyForArray() {
            var copy = new Slot(heap);
            if (content instanceof Cell cell && cell.owners > 1) {
                copy.bind(cell);
            } else {
                // A reference with no other alias must not connect the separated arrays.
                copy.set(read());
            }
            return copy;
        }

        void clear() {
            var previous = content;
            forget();
            release(previous);
        }

        void forget() {
            content = null;
            defined = false;
        }
    }

    private static Object normalizeKey(Object key) {
        if (key == null) return "";
        if (key instanceof Boolean bool) return bool ? 1L : 0L;
        if (key instanceof Integer integer) return integer.longValue();
        if (key instanceof Long || key instanceof PhpString) return key;
        if (key instanceof Double number) return number.longValue();
        if (key instanceof String string) {
            try {
                long integer = Long.parseLong(string);
                if (Long.toString(integer).equals(string)) return integer;
            } catch (NumberFormatException ignored) {
                // Non-integer strings, including overflow, remain string keys in PHP.
            }
            return string;
        }
        throw new PhpError("TypeError", "Illegal offset type");
    }

    private static void validateValue(Object value) {
        if (value == null || value instanceof Long || value instanceof Double || value instanceof Boolean
                || value instanceof String || value instanceof PhpString || value instanceof PhpArray || value instanceof TruffleObject) return;
        throw new PhpError("Unsupported PHP value type: " + value.getClass().getSimpleName());
    }

    static void retain(Object value) {
        if (value instanceof HeapNode node) node.owners++;
    }

    static void release(Object value) {
        if (!(value instanceof HeapNode node)) return;
        var heap = node.heap;

        if (--node.owners > 0) {
            heap.candidates.add(node);
            return;
        }
        var pending = new ArrayDeque<HeapNode>();
        pending.add(node);
        while (!pending.isEmpty()) {
            var dead = pending.removeLast();
            heap.candidates.remove(dead);
            dead.children(child -> {
                if (--child.owners == 0) pending.add(child);
                else heap.candidates.add(child);
            });
            dead.discard();
        }
    }

    abstract static class HeapNode {
        final Heap heap;
        HeapNode(Heap heap) { this.heap = heap; }
        int owners;
        long cycleEpoch;
        int cycleOwners;
        boolean cycleLive;
        abstract void children(Consumer<HeapNode> visit);
        abstract void discard();
    }

    /** Trial deletion: internal graph edges do not keep an unreachable cycle alive. */
    public static final class Heap {
        Set<HeapNode> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        long storageCopies;
        long liveArrays;
        long liveCells;
        public long liveObjects;
        private long cycleEpoch;

        public int collectCycles() {
            if (candidates.isEmpty()) return 0;
            long epoch = ++cycleEpoch;
            var graph = new ArrayList<HeapNode>(candidates.size());
            Consumer<HeapNode> discover = node -> {
                if (node.cycleEpoch == epoch) return;
                node.cycleEpoch = epoch;
                node.cycleOwners = node.owners;
                node.cycleLive = false;
                graph.add(node);
            };
            candidates.forEach(discover);
            // Do not scan a table sized for a past coroutine peak on every scope close.
            candidates = Collections.newSetFromMap(new IdentityHashMap<>());
            // Scratch counts belong to the nodes: each graph edge is visited without
            // allocating boxed counts or repeatedly scanning identity hash tables.
            for (int i = 0; i < graph.size(); i++) graph.get(i).children(discover);
            for (var node : graph) node.children(child -> child.cycleOwners--);
            var pending = new ArrayDeque<HeapNode>();
            Consumer<HeapNode> markLive = node -> {
                if (node.cycleLive) return;
                node.cycleLive = true;
                pending.add(node);
            };
            for (var node : graph) if (node.cycleOwners > 0) markLive.accept(node);
            while (!pending.isEmpty()) {
                pending.removeLast().children(markLive);
            }
            for (var node : graph) if (!node.cycleLive) {
                node.children(child -> {
                    if (child.cycleLive) release(child);
                });
            }
            int collected = 0;
            for (var node : graph) if (!node.cycleLive) {
                node.owners = 0;
                node.discard();
                collected++;
            }
            return collected;
        }
    }
}
