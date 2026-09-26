# Argument unpacking / array spread evidence

Functional block of 26 September 2026. See the
[contract](../../unpacking.md), [results and limits](results.txt),
[source/product hashes](hashes.json) and [product metadata](products.json).

## Differential matrix

The six final reports are retained as `windows-jvm.txt`,
`windows-native-build-and-test.txt`, `windows-interpreter.txt`,
`linux-jvm.txt`, `linux-native.txt` and `linux-interpreter.txt`.
Each executes the same 46-program `UnpackTest` corpus: 44 compare stdout/stderr
and two require semantic rejection of invalid call syntax.

Windows uses PHP 8.6.0RC2 for synchronous programs and the pinned TrueAsync
0.10.0 binary for asynchronous ones. Linux intentionally uses the pinned
TrueAsync/PHP 8.6 executable for both because no separate stock PHP 8.6 binary
is installed there.

The corpus covers array and Traversable call unpacking, named/positional key
ordering, duplicate names, variadics, references and temporaries, constructors,
method/static/dynamic/autoloaded callables, strict_types, evaluation order,
suspending iterators, array merge/reindex behavior, explicit references, COW
and class-constant array spread.

## Native Image development failure

`windows-native-blocklist-failure.txt` is deliberately retained. The first
Windows Native Image build rejected generic collection code reachable through
`CallArguments.expand` from guest runtime compilation. The implementation was
not weakened or the JIT disabled: the generic binder was placed behind the same
TruffleBoundary used for other dynamic value-model operations. The retained
final Windows build and the Linux build then completed and passed the corpus.

## Regressions and products

`windows-verification.txt` / `linux-verification.txt` retain the full JVM
functional verification. `windows-native-regression.txt` and
`linux-native-regression.txt` retain TrueAsync/network checks plus the
unpacking example on the final products. The Linux native build transcript is
`linux-native-build.txt`; the ordinary Linux source/JAR build is
`linux-build.txt`.

The product hashes are in `products.json`; `hashes.json` also covers the
maintained source/test/example tree and relevant root build inputs. The
authoritative and Linux-copy maintained trees were compared byte-for-byte
before delivery.

Text evidence is normalized to UTF-8/LF, ANSI sequences and trailing horizontal
whitespace are removed, and private absolute workspace prefixes are replaced.
No executable is committed in this evidence directory. No performance result
is inferred from build output or functional test durations.

This milestone advances LANG-01 but does not close it. Reflection/full attribute
semantics are the next language block; generators/Fiber, ArrayAccess, remaining
builtin typing, the default SPL loader and other open areas remain in TODO.md.
