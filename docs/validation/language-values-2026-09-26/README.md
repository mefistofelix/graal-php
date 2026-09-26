# Language/value functional evidence

26 September 2026. [Implementation contract](../../language-values.md),
[results and limitations](results.txt), [source/product hashes](hashes.json),
[product sizes](products.json).

## Differential corpus

The new corpus contains 90 enum/match/clone programs, 27 string-offset programs,
and 38 strict-types programs: 155 distinct inputs. Of these, 126 require matching
stdout, empty stderr and successful exits; 29 require rejection of the same
invalid program. Rejection cases compare an error category, not identical
fatal wording or the same numeric exit code. A JVM/native crash, timeout,
unsupported-syntax fallback or unexpected guest execution is not an accepted
semantic rejection.

Each `<platform>-<mode>-<suite>.json` retains the reference version output,
case names, comparison mode, both exit codes and both stdout/stderr streams.
The shared `.programs/<suite>/<case>/` snapshots preserve exact PHP sources and
fixtures; every report records their SHA-256 hashes. The leading dot keeps
evidence files out of the runtime's eager project-root index, without changing
the original program. Copies from different platforms must match these inputs.

Windows uses stock PHP 8.6.0RC2 for synchronous cases and pinned TrueAsync
0.10.0/PHP 8.6 for asynchronous cases. Linux uses pinned TrueAsync/PHP 8.6 for
both, not a separate stock PHP executable. Native interpreter tests run the
same executable with `--interpreter`; they are not a separate implementation.

## Regressions and build failures

The regression transcripts retain the existing class, autoload, TrueAsync,
value/collector, integration, network, cURL and FFI results actually executed.
Some rejection diagnostics and numeric process exits intentionally differ
between Zend and Truffle. The six DLL-conditional integration scenarios remain
Windows-specific. cURL assertion totals include progress checks and must not
be interpreted as different feature counts or performance scores.

`development-*` logs preserve the inherited partial enum run, compiler-lowering
errors, the string-offset failure and early passing subsets. They are not
pooled into the final corpus. The first Native Image attempt rejected eagerly
initialized frontend IR in the image heap; a lazy, synchronized immutable
syntax cache corrects this without global initialization overrides. The
second attempt identified error-message construction reachable from guest
runtime compilation; that error path now has an explicit Truffle boundary.
The successful build transcripts are retained separately.

The source/test manifest is checked against the disposable Linux build copy.
It also contains the root build scripts, dependency pin file, new example and
both native products and JARs. Platform JAR ZIP containers are not claimed to
be byte-identical. The local pre-feature binaries are separately preserved
under `build/before-language-values-2026-09-26`, not stored in this archive.

## Reproduction

Use `build.bat enum-test`, `build.bat string-test`, `build.bat strict-test` and
`build.bat verify`. A Windows `native` test target rebuilds its product. To
reuse a freshly built image across suites, invoke the Java runner directly as
recorded in the transcripts. The optional fourth runner argument
`--interpreter` disables guest compilation on that same image.

Linux `build.sh` exposes the corresponding targets with explicit PHP_ORACLE
and TRUEASYNC_ORACLE. Build `native` before requesting its native tests.
The native source builds are sequential across platforms; functional JVM
checks can overlap a build. No performance campaign is run in this block.

UTF-8/LF log copies remove terminal color escapes, trailing horizontal
whitespace and private absolute workspace prefixes. Per-case JSON strings
retain their content except newline normalization and that same private path
replacement. Comparisons were checked before path sanitization. Numeric
measurements in historical performance archives were not rewritten. Build
resource summaries and network-test durations are not new runtime benchmarks.
