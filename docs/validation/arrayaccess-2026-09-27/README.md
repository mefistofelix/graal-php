# ArrayAccess validation

Functional evidence for ArrayAccess, 27 September 2026.

The same 20-program corpus runs on Windows and Linux in JVM, Native Image and
the same Native Image with --interpreter: 120 executions total. Eighteen
programs compare output with PHP 8.6 / pinned TrueAsync 0.10.0 and two require
semantic rejection.

The two windows-native-failure logs are intentionally retained. The first
Native Image exposed guest partial evaluation through RuntimeClass.isA; the
second exposed blocklisted StringBuilder outlining while formatting the
indirect-modification notice. The final implementation places only those two
dynamic/diagnostic paths behind TruffleBoundary; final Windows and Linux
Native Images build successfully with guest JIT enabled.

Windows/Linux verification logs retain the general runtime regressions.
Generator and Unpack reports verify the offset lowering did not regress the
two immediately preceding language blocks.

Logs are normalized to UTF-8/LF, ANSI sequences and private workspace prefixes
are removed/replaced. No executable is committed in this evidence directory.
