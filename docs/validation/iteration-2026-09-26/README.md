# Iteration / attribute metadata evidence

Functional block of 26 September 2026. See
[the contract](../../iteration.md), [results and limits](results.txt) and
[source/product hashes](hashes.json).

The six final differential reports are retained as
`windows-jvm.txt`, `windows-native.txt`, `windows-interpreter.txt`,
`linux-jvm.txt`, `linux-native.txt` and `linux-interpreter.txt`.
Each is produced by the same `IterationTest` corpus: 74 programs, of which
68 compare clean stdout/stderr and six require a semantic rejection.

Windows uses PHP 8.6.0RC2 for synchronous cases and the pinned TrueAsync 0.10.0
binary for asynchronous ones. Linux intentionally uses the pinned
TrueAsync/PHP 8.6 binary as both references because a separate stock PHP 8.6
binary is not installed there. The report headers retain the exact versions.

`windows-native-build-and-test.txt` and `linux-native-build.txt` retain the
Native Image build transcripts. `linux-verification.txt` retains verify plus
all three Linux iteration modes and native examples. The two
`*-native-regression.txt` files cover 82 TrueAsync cases and 50 network
assertions on each final Native Image.

Text evidence is normalized to UTF-8/LF, ANSI sequences and trailing horizontal
whitespace are removed, and the private absolute workspace prefix is replaced.
Numerical/product values are not altered. No performance data is inferred from
Native Image build output or the network test duration.

The final manifest hashes maintained `src/`, `tests/`, `examples/`, root
build inputs and the four delivered/generated products. The disposable Linux
workspace was checked against the authoritative maintained files before the
Linux executable was copied to the root delivery path.

This milestone does not close LANG-01. Argument/array unpacking, generators and
Fiber, full Reflection/attribute semantics, ArrayAccess, remaining builtin
typing and other PHP surface remain explicitly open.
