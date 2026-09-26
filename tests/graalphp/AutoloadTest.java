package graalphp;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Identical programs against pinned PHP/TrueAsync and JVM or Native Image; no performance measurements. */
public final class AutoloadTest {
    private record Case(String name, String source, Map<String, String> files, boolean async) {
        Case(String name, String source) { this(name, source, Map.of(), false); }
        Case(String name, String source, boolean async) { this(name, source, Map.of(), async); }
    }
    private static final List<Case> CASES = List.of(
        new Case("empty-and-already-loaded", """
            class Existing {}
            echo count(spl_autoload_functions()), ':', class_exists('Existing', false), ':', class_exists('eXiStInG'), ':';
            echo class_exists('Closure'), ':', class_exists('Exception'), ':', class_exists('Missing', false) === false;
            """),
        new Case("queue-order-and-return-ignored", """
            function first($name) { echo 'A'; return true; }
            function second($name) { echo 'B'; eval('class ' . $name . ' {}'); return false; }
            function third($name) { echo 'C'; }
            spl_autoload_register('second'); spl_autoload_register('third');
            spl_autoload_register('first', prepend: true);
            echo class_exists('Loaded'), ':', count(spl_autoload_functions());
            """),
        new Case("function-identity", """
            function Loader($name) {}
            function Other($name) {}
            spl_autoload_register('LoAdEr'); spl_autoload_register('Other');
            spl_autoload_register('loader', prepend: true);
            foreach (spl_autoload_functions() as $callback) echo $callback, ':';
            echo spl_autoload_unregister('LOADER'), ':', spl_autoload_unregister('Loader'), ':', count(spl_autoload_functions());
            """),
        new Case("static-callback-identity", """
            class Loader { public static function Load($name) { echo $name; } }
            spl_autoload_register('Loader::LoAd'); spl_autoload_register(['loader', 'load'], prepend: true);
            $list = spl_autoload_functions(); echo count($list), ':', $list[0][0], ':', $list[0][1], ':';
            spl_autoload_call('Missing'); echo ':', spl_autoload_unregister(['Loader', 'LOAD']);
            """),
        new Case("closure-and-object-lifetime", """
            class Loader { public $n = 3; public function load($name) { echo $this->n, ':'; } }
            function register() {
                $object = new Loader; $value = [7];
                spl_autoload_register([$object, 'load']);
                spl_autoload_register(function($name) use ($value) { echo $value[0], ':', $name; });
            }
            register(); class_exists('Missing');
            """),
        new Case("private-method-registration", """
            class Loader {
                public function register() { spl_autoload_register([$this, 'load']); }
                private function load($name) { echo $name; eval('class ' . $name . ' {}'); }
            }
            $loader = new Loader; $loader->register(); unset($loader);
            echo ':', class_exists('Loaded');
            """),
        new Case("invokable-loader", """
            class Loader { public function __invoke($name) { echo $name; } }
            $loader = new Loader; spl_autoload_register($loader);
            $list = spl_autoload_functions(); echo count($list), ':';
            class_exists('Missing'); echo ':', spl_autoload_unregister($loader);
            """),
        new Case("list-is-a-snapshot", """
            function first($name) { echo 'A'; }
            function second($name) { echo 'B'; }
            spl_autoload_register('first');
            $list = spl_autoload_functions(); $list[0] = 'second'; $list[] = 'second';
            echo count(spl_autoload_functions()), ':'; class_exists('Missing');
            """),
        new Case("live-append", """
            $next = function($name) { echo 'B'; };
            spl_autoload_register(function($name) use ($next) { echo 'A'; spl_autoload_register($next); });
            class_exists('Missing'); echo ':'; class_exists('Other');
            """),
        new Case("live-prepend", """
            $next = function($name) { echo 'B'; };
            spl_autoload_register(function($name) use ($next) { echo 'A'; spl_autoload_register($next, prepend: true); });
            class_exists('Missing'); echo ':'; class_exists('Other');
            """),
        new Case("remove-pending-and-current", """
            function second($name) { echo 'B'; }
            function third($name) { echo 'C'; }
            function first($name) {
                echo 'A'; spl_autoload_unregister('second'); spl_autoload_unregister('first');
            }
            spl_autoload_register('first'); spl_autoload_register('second'); spl_autoload_register('third');
            class_exists('Missing'); echo ':'; class_exists('Other');
            """),
        new Case("explicit-call-existing-and-recursion", """
            class Existing {}
            $depth = 0;
            spl_autoload_register(function($name) use (&$depth) {
                echo $name, ++$depth, ':';
                if ($depth < 3) spl_autoload_call($name);
            });
            spl_autoload_call('Existing');
            """),
        new Case("lookup-recursion-and-retry", """
            spl_autoload_register(function($name) { echo $name, ':', class_exists('missing') === false, ';'; });
            class_exists('Missing'); class_exists('Missing');
            """),
        new Case("exception-unwinds-guard", """
            $calls = 0;
            spl_autoload_register(function($name) use (&$calls) {
                if (++$calls === 1) throw new Exception('loader failed');
                eval('class ' . $name . ' {}');
            });
            try { class_exists('Loaded'); } catch (Exception $error) { echo 'caught:'; }
            echo class_exists('Loaded'), ':', $calls;
            """),
        new Case("nested-loading", """
            spl_autoload_register(function($name) {
                echo $name, ':';
                if ($name === 'OuterClass') new InnerClass;
                eval('class ' . $name . ' {}');
            });
            new OuterClass; echo class_exists('InnerClass', false);
            """),
        new Case("namespace-and-alias", """
            namespace App;
            use Vendor\\Package as Alias;
            spl_autoload_register(function($name) {
                echo $name, ':';
                eval('namespace Vendor; class Package {}');
            });
            echo class_exists('Alias', false) === false, ':';
            $object = new Alias; echo get_class($object), ':', class_exists('\\Vendor\\Package');
            """),
        new Case("dynamic-construction-and-argument-order", """
            function argument() { echo 'arg:'; return 8; }
            spl_autoload_register(function($name) {
                echo 'load:';
                eval('class ' . $name . ' { public $n; public function __construct($n) { $this->n = $n; } }');
            });
            $name = 'Loaded'; $object = new $name(argument());
            echo $object->n, ':', get_class(new ('Loaded')(9));
            """),
        new Case("dynamic-class-types", """
            foreach ([1, false, null, []] as $name) {
                try { new $name; } catch (Throwable $error) { echo get_class($error), ':'; }
            }
            class Existing {} $object = new Existing; echo get_class(new $object), ':';
            try { class_exists([]); } catch (TypeError $error) { echo 'name:'; }
            try { class_exists('Existing', []); } catch (TypeError $error) { echo 'flag'; }
            """),
        new Case("remove-current-skips-next", """
            function first($name) { echo 'A'; spl_autoload_unregister('first'); }
            function second($name) { echo 'B'; }
            function third($name) { echo 'C'; }
            spl_autoload_register('first'); spl_autoload_register('second'); spl_autoload_register('third');
            class_exists('Missing');
            """),
        new Case("remove-earlier-keeps-next", """
            function first($name) { echo 'A'; }
            function second($name) { echo 'B'; spl_autoload_unregister('first'); }
            function third($name) { echo 'C'; }
            spl_autoload_register('first'); spl_autoload_register('second'); spl_autoload_register('third');
            class_exists('Missing');
            """),
        new Case("static-property-and-method", """
            spl_autoload_register(function($name) {
                eval('class ' . $name . ' { public static $n = 4; public static function get() { return static::$n; } }');
            });
            Loaded::$n += 3; echo Loaded::get();
            """),
        new Case("callable-array-and-reference", """
            spl_autoload_register(function($name) {
                eval('class ' . $name . ' { public static function change(&$n, $a = 2) { $n += $a; return $n; } }');
            });
            $callback = [1 => 'change', 0 => 'Loaded']; $n = 5;
            echo $callback(a: 3, n: $n), ':', $n;
            """),
        new Case("callable-string-leading-separator", """
            spl_autoload_register(function($name) { eval('class ' . $name . ' { public static function value() { return 9; } }'); });
            $callback = '\\Loaded::value'; echo $callback();
            """),
        new Case("registration-loads-callback-class", """
            spl_autoload_register(function($name) {
                echo 'base:';
                if ($name === 'Loader') eval('class Loader { public static function load($name) { echo $name; } }');
            });
            echo spl_autoload_register(['Loader', 'load']), ':';
            class_exists('Missing');
            """),
        new Case("parent-loads-at-declaration", """
            spl_autoload_register(function($name) { echo 'load:', $name, ':'; eval('class ' . $name . ' { public $n = 7; }'); });
            echo 'before:'; class Child extends MissingParent {} echo 'after:', (new Child)->n;
            """),
        new Case("late-parent-not-hoisted", """
            echo class_exists('Child', false) === false, ':';
            try { new Child; } catch (Error $error) { echo 'missing:'; }
            class Child extends Base {} class Base {}
            echo class_exists('Child', false), ':', get_class(new Child);
            """),
        new Case("conditional-and-function-local-classes", """
            function defineLocal() { class LocalClass {} }
            if (false) { class Absent {} }
            echo class_exists('LocalClass', false) === false, ':', class_exists('Absent', false) === false, ':';
            defineLocal(); if (true) { class Present extends LocalClass {} }
            echo get_class(new Present);
            """),
        new Case("missing-parent-does-not-publish", """
            try { if (true) { class Broken extends Missing {} } } catch (Error $error) { echo 'missing:'; }
            echo class_exists('Broken', false) === false;
            """),
        new Case("validation-and-named-arguments", """
            try { spl_autoload_register(callback: 'undefined_loader'); } catch (TypeError $error) { echo 'callback:'; }
            try { spl_autoload_unregister('undefined_loader'); } catch (TypeError $error) { echo 'unregister:'; }
            try { class_exists(); } catch (ArgumentCountError $error) { echo 'count:'; }
            try { spl_autoload_functions(1); } catch (ArgumentCountError $error) { echo 'arity:'; }
            try { class_exists(unknown: 'Missing'); } catch (Error $error) { echo 'name:'; }
            echo class_exists(autoload: false, class: 'Missing') === false;
            """),
        new Case("invalid-names-do-not-autoload", """
            $calls = 0; spl_autoload_register(function($name) use (&$calls) { $calls++; });
            foreach (['', 'A B', 'A/B'] as $name) echo class_exists($name) === false;
            echo ':', $calls, ':'; class_exists('404'); echo $calls;
            """),
        new Case("include-loader-scope", """
            $secret = 'global';
            spl_autoload_register(function($name) { $secret = 'local'; require __DIR__ . '/loaded.php'; });
            $object = new Loaded; echo ':', $secret, ':', $object->n;
            """, Map.of("loaded.php", "echo $secret; class Loaded { public $n = 12; }"), false),
        new Case("included-child-parent-and-once", """
            spl_autoload_register(function($name) { echo $name, ':'; require_once __DIR__ . '/' . $name . '.php'; });
            echo (new Child)->value(), ':', class_exists('ParentClass', false);
            """, Map.of("Child.php", "echo 'child-before:'; class Child extends ParentClass {} echo 'child-after:';",
                         "ParentClass.php", "class ParentClass { public function value() { return 42; } }"), false),
        new Case("async-loader-and-constructor", """
            spl_autoload_register(function($name) {
                Async\\delay(1);
                eval('class Loaded { public $n; public function __construct($n) { Async\\delay(1); $this->n = $n; } }');
            });
            echo (new Loaded(42))->n;
            """, true),
        new Case("async-live-removal-roots", """
            $loader = null; $payload = [9];
            $loader = function($name) use (&$loader, $payload) {
                spl_autoload_unregister($loader); unset($loader);
                try { Async\\delay(1); echo $payload[0], ':'; eval('class Loaded {}'); }
                finally { echo 'finally:'; }
            };
            spl_autoload_register($loader); unset($payload);
            echo class_exists('Loaded'), ':', count(spl_autoload_functions());
            """, true),
        new Case("async-same-name-suppressed", """
            $entered = new Async\\Channel(1); $release = new Async\\Channel(1);
            spl_autoload_register(function($name) use ($entered, $release) {
                $entered->send(true); $release->recv(); eval('class Loaded {}');
            });
            $task = Async\\spawn(function() { return class_exists('Loaded'); });
            $entered->recv(); echo class_exists('Loaded') === false, ':';
            $release->send(true); echo Async\\await($task), ':', class_exists('Loaded', false);
            """, true),
        new Case("async-cancellation-releases-guard", """
            $entered = new Async\\Channel(1); $calls = 0;
            spl_autoload_register(function($name) use ($entered, &$calls) {
                if (++$calls === 1) {
                    try { $entered->send(true); Async\\delay(10000); } finally { echo 'finally:'; }
                }
                eval('class Loaded {}');
            });
            $task = Async\\spawn(function() { return class_exists('Loaded'); });
            $entered->recv(); $task->cancel();
            try { Async\\await($task); } catch (Throwable $error) { echo 'cancel:'; }
            echo class_exists('Loaded'), ':', $calls;
            """, true),
        new Case("async-callable-arguments", """
            spl_autoload_register(function($name) {
                Async\\delay(1);
                eval('class Loaded { public static function change(&$n, $a) { Async\\delay(1); $n += $a[0]; return $n; } }');
            });
            $callback = 'Loaded::change'; $n = 2;
            echo $callback(a: [7], n: $n), ':', $n;
            """, true),
        new Case("async-inherited-include", """
            spl_autoload_register(function($name) { Async\\delay(1); require __DIR__ . '/' . $name . '.php'; });
            echo (new Child)->value();
            """, Map.of("Child.php", "class Child extends ParentClass {}",
                         "ParentClass.php", "Async\\delay(1); class ParentClass { public function value() { return 11; } }"), true)
    );

    public static void main(String[] arguments) throws Exception {
        Path oracle = Path.of(arguments.length > 0 ? arguments[0] : "tools/php-8.6.0RC2/php.exe").toAbsolutePath();
        Path async = Path.of(arguments.length > 1 ? arguments[1] : "tools/trueasync-0.10.0/php.exe").toAbsolutePath();
        Path executable = arguments.length > 2 && !arguments[2].isBlank() ? Path.of(arguments[2]).toAbsolutePath() : null;
        if (!Files.isRegularFile(oracle) || !Files.isRegularFile(async)) throw new IllegalArgumentException("Specify both pinned PHP and TrueAsync binaries");
        Path directory = Files.createTempDirectory(Path.of("build"), "autoload-test-").toAbsolutePath();
        var report = new StringBuilder("Target: " + (executable == null ? "JVM" : executable.getFileName()) + "\n");
        for (var reference : List.of(oracle, async)) {
            var process = new ProcessBuilder(reference.toString(), "-n", "-v").redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Version timeout"); }
            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            report.append(version.replace("\r\n", "\n"));
            if (process.exitValue() != 0 || !version.contains("PHP 8.6")) throw new AssertionError("Unexpected oracle version");
        }
        int failures = 0;
        for (var test : CASES) {
            Path root = directory.resolve(test.name);
            Files.createDirectories(root);
            Path script = root.resolve("main.php");
            Files.writeString(script, "<?php\n" + test.source);
            for (var file : test.files.entrySet()) Files.writeString(root.resolve(file.getKey()), "<?php\n" + file.getValue());
            Path reference = test.async ? async : oracle;
            int expectedExit = process(List.of(reference.toString(), "-n", script.toString()), root, "oracle");
            int actualExit;
            if (executable != null) {
                actualExit = process(List.of(executable.toString(), script.toString()), root, "actual");
            } else {
                var output = new ByteArrayOutputStream();
                var error = new ByteArrayOutputStream();
                actualExit = 0;
                try (var context = Context.newBuilder("php").allowAllAccess(true).out(output).err(error)
                        .environment("GRAALPHP_ROOT", root.toString()).build()) {
                    context.eval(Source.newBuilder("php", script.toFile()).build());
                } catch (Exception failure) {
                    actualExit = 1;
                    error.writeBytes(failure.toString().getBytes(StandardCharsets.UTF_8));
                }
                Files.write(root.resolve("actual.out"), output.toByteArray());
                Files.write(root.resolve("actual.err"), error.toByteArray());
            }
            String expected = Files.readString(root.resolve("oracle.out")).replace("\r\n", "\n");
            String actual = Files.readString(root.resolve("actual.out")).replace("\r\n", "\n");
            boolean pass = expectedExit == 0 && actualExit == 0 && expected.equals(actual)
                    && Files.size(root.resolve("oracle.err")) == 0 && Files.size(root.resolve("actual.err")) == 0;
            String line = (pass ? "PASS " : "FAIL ") + test.name + " oracle=" + expectedExit + " target=" + actualExit + "\n";
            report.append(line);
            System.out.print(line);
            if (!pass) {
                failures++;
                System.out.println("expected=" + expected + " actual=" + actual);
                System.out.println(Files.readString(root.resolve("actual.err")));
            }
        }
        report.append("Cases: ").append(CASES.size()).append("; failures: ").append(failures).append('\n');
        Files.writeString(directory.resolve("results.txt"), report);
        System.out.println("Evidence: " + directory);
        if (failures != 0) throw new AssertionError(failures + " autoload cases failed");
        System.out.println("PASS: " + CASES.size() + " identical autoload programs");
    }

    private static int process(List<String> command, Path root, String label) throws Exception {
        var builder = new ProcessBuilder(command).redirectOutput(root.resolve(label + ".out").toFile())
                .redirectError(root.resolve(label + ".err").toFile());
        builder.environment().put("GRAALPHP_ROOT", root.toString());
        builder.environment().put("GRAALPHP_WATCH", "0");
        var process = builder.start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            return -1;
        }
        return process.exitValue();
    }
}
