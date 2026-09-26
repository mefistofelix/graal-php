# Class contracts: functional evidence

26 September 2026. [Contract and limitations](../../class-contracts.md),
[results](results.txt), [source/product hashes](hashes.json),
[product sizes](products.json).

## Final runs

The six non-development `*-classes.json` reports contain the same 93 programs:
Windows/Linux JVM, Native Image with normal guest compilation, and the same
Native Image with `--interpreter`. Each run has 61 exact-output comparisons
and 32 semantic-rejection checks. A rejected declaration is not claimed to
have identical error wording or the same numeric exit code as PHP. These
programs assert the required failure category and absence of guest execution
after a fatal declaration error; generic parser/unsupported/internal failures
are not accepted as a passing semantic rejection.

The four `*-autoload.json` files contain the existing 38-program corpus on
Windows/Linux JVM and Native Image. The four `*-fatal.json` files contain the
four immediate/suspended, sync/async native-callback fatal scenarios. JVM
fatal tests verify C counters and reuse of the same context; CLI fatal tests
verify exit status, exact prefix output, expected error and absence of a hang.
They do not read C counters after the child process has exited.

Input snapshots are shared under `.programs/classes`, `.programs/autoload`
and `.programs/fatal`. The leading dot excludes evidence fixtures from the
normal CodeRepository root index. All final runs were checked against these
same input-file hashes. Per-case stdout/stderr in JSON preserve whitespace
and final-newline state; only CRLF and private workspace prefixes are normalized.

Windows uses stock PHP 8.6.0RC2 for synchronous comparisons and pinned
TrueAsync 0.10.0/PHP 8.6 for asynchronous ones. Linux uses its pinned
TrueAsync/PHP 8.6 executable for both; there is no separate stock Linux oracle.
Version output is retained in every language-corpus report.

## Regression and build transcripts

`windows-delivery-build.txt` and `linux-delivery-build.txt` identify the final
sequential product builds. `windows-delivery-checks.txt`, `linux-jvm-delivery.txt`
and `linux-native-delivery.txt` retain the exact test commands and outcomes.
`windows-host-repeated.txt` has the 12 targeted host-reactor repetitions and
the final full Windows `verify`; the Linux transcript contains six targeted
host repetitions. Reload fixtures are on native temporary filesystems.

`copied-linux-executable.txt` checks the final Linux executable copied back to
the authoritative workspace. The manifest compares all 92 source/test files
between the Windows root and Linux build copy, plus selected root build
scripts, examples and products. JAR archives are platform-specific and are
not claimed to be byte-identical ZIP files.

## Earlier attempts retained

All `development-*` files are earlier revisions, not extra final repetitions.
They retain the initial 75/86/88-case failures and passes, diagnostic location
and annotation errors, the missing Native Image compilation boundary and a
command-line quoting failure. The earlier 88-case executable and its checks
are distinctly labeled; they are not the delivered 93-case products.

The trait-default probes and host-alarm reproduction preserve later defects:
trait static defaults were evaluated too early, and a completion arriving
between idle pump and deadline lookup could schedule a zero-delay alarm.
The targeted host fixture was then made deterministic about actually waiting
for a connection and explicitly selected libuv in both contexts. The final
repeated passes do not erase those earlier failures.

`native-failure-propagation.txt` was recorded with the native compilation
error still present and the wrapper corrected: nonzero exit, previous binary
unchanged and no execution of tests on that stale binary. The direct driver
exit-code probe is separate. This is a functional build-robustness check.

## Reproduction and scope

Root commands are documented in the [contract](../../class-contracts.md).
The product CLI and a dedicated Native Image test runner are distinct: this
block runs the ordinary FFI corpus in both product CLIs and requests 80 full
collections in the JVM bridge runner on each platform. It does not claim a
fresh run of the dedicated forced-GC Native Image harness.

Logs are UTF-8/LF; ANSI escapes, trailing horizontal log whitespace and
private home/workspace prefixes were normalized. Numeric results, input
snapshots and historical performance archives were not rewritten. No binary
is committed in this evidence directory. No performance campaign is included.
