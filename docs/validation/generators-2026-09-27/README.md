# Generator validation

Functional evidence for [PHP generators](../../generators.md), 27 September 2026.

The six language reports execute the same 37-program `GeneratorTest` corpus on
Windows and Linux: JVM, Native Image, and the same Native Image with
`--interpreter`. Thirty-five programs compare output with PHP 8.6 / the pinned
TrueAsync 0.10.0 runtime; two require semantic rejection without asserting the
full diagnostic byte-for-byte.

The corpus covers lazy start, keys, rewind, send/throw/getReturn, return-type
rules, closures/methods/traits, `yield from` delegation and forwarding,
by-reference generators, destruction/finally, temporary receivers, TrueAsync
suspension/cancellation, Iterator builtins and Reflection metadata.

`windows-verification.txt` and `linux-verification.txt` retain the full JVM
regression suites. `windows-native-trueasync.txt` and
`linux-native-trueasync.txt` verify all 82 TrueAsync differential scenarios on
the final Native Image products.

`windows-native-build-and-test.txt` and `linux-native-build.txt` retain Native
Image build evidence. Build resource/timing figures are not performance
measurements. `products.json` identifies the products and `hashes.json` the
maintained source/test snapshot plus the Generator example and root build inputs.

The clean Linux copy initially invoked `build.sh trueasync` before the native
library bundle existed; its six cURL bridge cases therefore could not load
`libgraalphp-native.so`. That setup-order probe is retained separately as
`linux-bootstrap-before-native-libs.txt`. After `native-libs`, Linux `verify`,
Generator JVM/native/interpreter and Native TrueAsync all pass.

Text logs are normalized to UTF-8/LF, ANSI sequences are removed and private
workspace prefixes are replaced. No executable is committed in this evidence
directory.
