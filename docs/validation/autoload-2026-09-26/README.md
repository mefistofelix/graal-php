# Autoload: functional evidence

26 September 2026. [Implementation contract](../../autoload.md),
[results and limits](results.txt), [source/product hashes](hashes.json),
[product sizes](products.json).

## Final comparisons

`windows-jvm.json`, `windows-native.json`, `linux-jvm.json` and
`linux-native.json` each retain 38 case names, oracle/target exit codes,
stdout/stderr and input-file SHA256 hashes. They include the exact reference
version output. Windows uses stock PHP 8.6.0RC2 for synchronous cases and
TrueAsync 0.10.0 for asynchronous cases; Linux uses the pinned TrueAsync/PHP 8.6
executable for both. Do not label the Linux reference as a separate stock PHP.

The `.programs/` directory contains the common input snapshots. Its leading
dot intentionally keeps the evidence out of CodeRepository's normal root
index, without changing the snapshot filenames or contents. These are
committed test evidence, not another implementation or executable source root.
All four runs were checked against these same files.

Driver/build/regression transcripts are separate from per-case outputs.
`windows-postbuild.txt` includes the final integration rerun after moving
watcher fixtures to native temp storage, the native TrueAsync/network suites
and the new example in all execution modes. `linux-regression.txt` includes
the Linux equivalents; six DLL-conditional integration cases remain
Windows-specific. `copied-linux-executable.txt` checks the delivered local copy.

## Earlier development attempts

The three `development-*` transcripts retain the initial compile error,
the initial internal-state/fixture failures and the first 35-case pass. These
are not additional repetitions of the final corpus. The final 38-case suite
adds dynamic type and queue-removal cases; all remain in the committed test.
No failed final case was removed or replaced with a runtime-specific program.

## Reproduction

The normal entry points are `build.bat autoload-test`, `build.bat verify`,
`build.bat oracle`, and `build.bat autoload-test native`. Linux requires explicit
`PHP_ORACLE` and `TRUEASYNC_ORACLE` paths for `bash build.sh autoload-test`;
`bash build.sh native` precedes `bash build.sh autoload-test native`.
The main contract documents these commands and their limitations.

The additional direct Java invocations, classpaths and selected executables
are retained in the transcripts. The Linux build uses the disposable
`build/linux-workspace` copy, checked against all 87 src/tests files. Root
build scripts and dependency pins are also in the manifest. The JAR hashes
are platform-specific, not asserted to be byte-identical ZIP containers.

Text was normalized to UTF-8/LF, ANSI/trailing horizontal log whitespace was
removed and private absolute workspace prefixes were replaced. Case outputs
in JSON retain their content with newline normalization. No binary is stored
in this evidence directory. The old performance archives were not rewritten
and no performance conclusions are drawn from this functional block.
