# Native build policy and source manifest

The native targets in root `xmake.lua` adapt php-xmake commit
`f6526d592bdcedf8b43fb2b5f2d834df058ef713`. Build policy was checked against
the local php-xmake `AGENTS.md`, `README.md`, `TODO.md`, `XMAKE_README.md` and
the zlib/libuv/SQLite entries in `unitybuild.md` at commit
`8c00ce25562f6dcdbffa42a1c335e3d71dc5d58b`.
The user's explicit Linux request supersedes its earlier Windows-only policy
for these selected native targets. It does not port PHP's Zend build or all
of php-xmake's dependencies.

Keep Xmake as the only native build graph. Each target fetches its own pinned
inputs in `before_sources`. Copies and generated headers belong to the
input's `add_files(..., {source = ...})` callback, inside the downloaded tree.
No manual edits to downloaded sources, project-wide preparation barrier,
alternate dependency build system, or full-Unity workaround. Use relative
paths, normal `add_deps`, independent static dependency targets and minimal
declarative platform branches. Windows uses `/MD`; static archives do not
imply a static CRT. This runtime keeps artifacts and generated state under
`build/`, following its existing disposable-directory policy.

## Upstream findings for the Linux adaptation

- **zlib 1.3.2:** `Makefile.in` and the Windows manifest select the same 15
  root C files. The committed `zconf.h` supports `HAVE_UNISTD_H` and
  `_LARGEFILE64_SOURCE` on Linux. `NO_FSEEKO` and MSVC CRT defines stay on
  Windows. No assembly or generated source is required. Leave it non-Unity.
- **PCRE2 10.44:** preserve the selected 8-bit implementation, Unicode and
  JIT. Exclude test programs, generators and POSIX wrapper. Materialize
  `pcre2.h`, `config.h` and default character tables beside their upstream
  templates. `HAVE_WINDOWS_H` applies only to Windows; Linux exposes
  `HAVE_UNISTD_H`. SLJIT supplies its platform-specific executable allocator.
- **SQLite 3.53.2:** use the official `3530200` amalgamation directly, one
  `sqlite3.c`, with FTS3/4/5 and column metadata. Keep the upstream default
  serialized threading mode. Linux links pthread, dl and m. No SQLite
  generator or external build step is needed.
- **libuv 1.52.1:** `Makefile.am` selects 12 common, 18 Unix-common and 5
  Linux-specific C files. The explicit Unix list is shorter and safer than
  excluding every other OS backend. Use `_GNU_SOURCE`, large-file support,
  the Unix private include directory and pthread/dl/rt. Windows retains its
  25 backend files and SDK library closure. Neither side enables DLL import
  decoration for the static archive. Keep independent translation units.
  The [pinned Electron embedding patch](../src/native/patches/README.md) is
  applied by the patch input's source callback, with every modified output
  tracked as a dependency. The same callback copies the patched public header
  to `libuv/uv.h`. The native shim drives callbacks on the PHP owner and uses
  the patch's interrupt/suspend API for the optional host readiness waiter.
- All Linux archives use PIC so the same objects can link into the JVM bridge
  and Native Image. The bridge owns NFI callbacks and libuv handles, without
  embedding Zend structures or managed PHP pointers in C.

## Patched Xmake

The companion source is available at `mefistofelix/xmake`, pinned to
`953d954e0042448abfa3f84f7d184b47d6aa2912`; the older `xmake-patched` URL in
php-xmake currently returns 404. Linux setup fetches that source and its
pinned submodules through HX, then uses an official Xmake bootstrap binary
to build the patched standalone bundle with `--embed=y`. No system Xmake
installation or shell-profile change is needed. Both bootstrap and final
build use Xmake. Downloaded tools and source are disposable under `tools/`;
the commands to recreate them live in root `build.sh`. The Linux extraction
cache is isolated under a directory named for the pinned source commit,
because an archive checkout has no Git metadata for Xmake's version suffix.

## HTTP client closure

The WebSocket/SQLite integration adds the curl-impersonate recipes from
php-xmake `8c00ce25562f6dcdbffa42a1c335e3d71dc5d58b`: impersonate 2.2.2 patches,
curl 8.21.0, BoringSSL `156c7b75ae9b8c3b3f847acf264f17594c3859fb`,
nghttp2 1.63.0, Brotli 1.2.0 and zstd 1.5.7. Each has its own Xmake target.
The curl version belongs to the curl-impersonate release: update the patched
closure together, never move its curl pin independently to upstream latest.
The parallel standard provider builds unpatched curl 8.22.0 and an independent,
unpatched BoringSSL tree at the same pinned revision. Its upstream `lib/Makefile.inc`
selects the root and subdirectory C closure, excluding DLL initialization;
TLS backends are selected by feature defines. Its TLS source list comes from
BoringSSL's authoritative `gen/sources.json` bcm/crypto/ssl components.
The two providers share only unmodified nghttp2 and compression archives.
Standard curl uses the official release archive, which contains release metadata
generated by upstream `scripts/maketgz`. Its 8.22 Windows manual config is now
restricted to old IDE projects, so Xmake materializes the maintained x64/MSVC
`src/native/curl_config_windows.h`; Linux uses the existing glibc/x64 config.
The BoringSSL generic source closure comes from its committed
`gen/sources.json`, with `OPENSSL_NO_ASM`; no NASM/Perl toolchain is required
for this implementation. This choice needs performance assessment later.

The upstream patches are applied by the owning source callbacks and tracked
as materialized inputs. The MSVC constant-bound compatibility copy for the
patched curl OpenSSL backend follows php-xmake. Linux's maintained glibc/x64
configuration is `src/native/curl_config_linux.h`; it is copied by a source
callback into curl's expected directory. All source compilation remains Xmake.
Linux enables the POSIX threaded resolver, so DNS cannot block the libuv
owner thread during a multi/socket transfer.

Standard BoringSSL uses its upstream `BORINGSSL_PREFIX` support, including the
committed generated C/C++ prefix headers. The global `ssl_session_st` type gets
a separate prefix too, isolating its C++ destructor. Standard curl's public and internal symbols
are renamed in its static archive after linking, following php-xmake's approach.
Only its provider table uses declarations prefixed from `libcurl.def`.
Windows setup supplies LLVM 22.1.8 nm/objcopy locally under `tools/`; Linux uses
the GCC toolchain's binutils. The provider table is compiled within its curl target. Each easy,
slist and multi uses its creator's functions; a cross-provider multi add is
rejected. Both multi/socket reactors run on the same PHP/libuv owner thread.
HTTP/3/ngtcp2/nghttp3 and their patches are outside this selected closure.
Brotli and zstd build their decoder closures for HTTP content decoding.
The Windows CRT remains /MD. `build.bat` copies MSVCP140 and both VCRUNTIME140
DLLs next to the native artifacts for the BoringSSL C++ dependency.
The Linux JVM bridge may use the system C++
runtime; Native Image requests the static libstdc++ and libgcc_eh archives.

## Validation status

The production bridge now includes `src/native/ffi_bridge.c`. Xmake compiles
it against the libffi 3.4.8 headers/archive shipped with the pinned GraalVM:
`windows-amd64/default` or `linux-amd64/glibc` under the Truffle SVM builder.
Native Image already links that archive for NFI; the JVM bridge links it
statically too. This deliberately avoids colliding symbols from a second
libffi build. No system libffi package or CMake step is required. The five
bridge objects are shim, reactor, services, curl_reactor and ffi_bridge.

`ffi-bridge-fixture` is a non-default Xmake DLL/SO target used by
`build.bat ffi-bridge-test [native]` and `bash build.sh ffi-bridge-test [native]`.
It has no managed runtime dependency and can also be used by TrueAsync's FFI
for comparable native callback workloads.

`native-stack-probe` is a non-default Xmake helper target for
`tests/native/native_stack_probe.c`. It builds a separate DLL/SO with OS stack
switching primitives and no downloaded dependency. The root build scripts can
run its PHP/Truffle driver on JVM or in a separate Native Image test executable;
see [native-stack-bridge.md](native-stack-bridge.md). It is not linked into the product.

Both platforms build the native dependency archives, the dynamic bridge and
Native Image. Windows passes 73 integration scenarios and Linux 67 (the six
Windows-DLL-specific cases are excluded). Both pass 53 value-model scenarios
and 82 TrueAsync comparisons on JVM and Native Image. Linux archive member
counts for the original closure are 15 zlib, 28 PCRE2, 1 SQLite and 35 libuv;
the bridge now has 5 objects including the service adapters, curl reactor and FFI bridge. Both
native executables run the built-in SQLite/pool/reactor example without the
bridge alongside them. See `validation.md` for logs and packaging limits;
this does not imply that all php-xmake targets have been ported. The network
suite passes 50 assertions on JVM and Native Image on both platforms;
the selected curl closure is documented in `network-integration.md`.
