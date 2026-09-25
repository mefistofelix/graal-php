package graalphp.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.source.Source;
import graalphp.truffle.PhpCompiler;
import graalphp.truffle.PhpLanguage;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Events update only affected units. Overflow explicitly requests a full reconciliation. */
public final class CodeRepository implements AutoCloseable {
    public record Generation(long id, Map<Path, Execution.Unit> units, Assumption current) {}
    private final PhpLanguage language;
    private final Path root;
    private final AtomicReference<Generation> current = new AtomicReference<>(new Generation(0, Map.of(),
            Truffle.getRuntime().createAssumption("generation 0")));
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final Set<Path> registered = new HashSet<>();
    private final Set<Path> dirty = new HashSet<>();
    private WatchService watcher;
    private Thread thread;
    private volatile boolean closed;
    private boolean reconcile;
    public volatile String reloadError;
    public volatile int lastCompiledUnits;

    public CodeRepository(PhpLanguage language, Path root, boolean watch) {
        this.language = language;
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        if (root == null) return;
        try {
            if (watch) watcher = FileSystems.getDefault().newWatchService();
            reconcile = true;
            publish();
            if (watch) {
                thread = new Thread(this::watch, "graalphp-reload");
                thread.setDaemon(true);
                thread.start();
            }
        } catch (IOException error) { throw new PhpError("Project index: " + error.getMessage()); }
    }

    public Generation snapshot() { return current.get(); }
    public Path root() { return root == null ? Path.of("").toAbsolutePath() : root; }

    private Set<Path> scan(Path start) throws IOException {
        var files = new HashSet<Path>();
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (excluded(directory)) return FileVisitResult.SKIP_SUBTREE;
                if (watcher != null && registered.add(directory)) {
                    directories.put(directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE), directory);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.toString().endsWith(".php") && !excluded(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private void publish() throws IOException {
        var old = current.get();
        var units = new HashMap<>(old.units);
        var changed = new HashSet<Path>(dirty);
        if (reconcile) {
            var present = scan(root);
            changed.addAll(present);
            changed.addAll(old.units.keySet());
        }
        // A directory create may contain a complete tree moved into the project.
        for (var path : Set.copyOf(changed)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) changed.addAll(scan(path));
            else if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) units.keySet().removeIf(unit -> unit.startsWith(path));
        }
        int compiled = 0;
        for (var path : changed) {
            if (!path.toString().endsWith(".php") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
            String content = Files.readString(path);
            var previous = old.units.get(path);
            if (previous != null && previous.content().equals(content)) continue;
            units.put(path, PhpCompiler.compile(language,
                    Source.newBuilder("php", content, path.getFileName().toString()).uri(path.toUri()).build()));
            compiled++;
        }
        if (!units.equals(old.units)) {
            var next = new Generation(old.id + 1, Map.copyOf(units),
                    Truffle.getRuntime().createAssumption("generation " + (old.id + 1)));
            current.set(next);
            old.current.invalidate();
        }
        lastCompiledUnits = compiled;
        dirty.clear();
        reconcile = false;
        reloadError = null;
    }

    private boolean excluded(Path path) {
        for (var part : root.relativize(path)) {
            String name = part.toString();
            if (name.equals("tools") || name.equals("build") || name.startsWith(".")) return true;
        }
        return false;
    }

    private void collect(WatchKey key) {
        Path directory = directories.get(key);
        for (var event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) { reconcile = true; continue; }
            if (directory == null) { reconcile = true; continue; }
            Path path = directory.resolve((Path) event.context()).normalize();
            if (!excluded(path)) dirty.add(path);
        }
        if (!key.reset()) {
            Path removed = directories.remove(key);
            if (removed != null) { registered.remove(removed); dirty.add(removed); }
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void watch() {
        while (!closed) {
            try {
                collect(watcher.take());
                Thread.sleep(40);
                WatchKey key;
                while ((key = watcher.poll()) != null) collect(key);
                if (dirty.isEmpty() && !reconcile) continue;
                try { publish(); }
                catch (PhpError | IOException error) {
                    // Keep every dirty path until the entire pending batch can publish.
                    reloadError = error.getMessage();
                }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            catch (ClosedWatchServiceException error) { return; }
        }
    }

    @Override public void close() {
        closed = true;
        if (thread != null) thread.interrupt();
        if (watcher != null) {
            try { watcher.close(); }
            catch (IOException error) { throw new PhpError(error.getMessage()); }
        }
    }
}
