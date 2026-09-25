# Ordinary cURL and curl-impersonate

GraalPHP links both providers into the executable. No curl or TLS DLL/SO is
needed at runtime. Windows still uses the existing MSVC runtime DLLs.

| Provider | libcurl | TLS | Source changes |
| --- | --- | --- | --- |
| `standard` | 8.22.0 release archive | pristine BoringSSL `156c7b75…` | No impersonation patches |
| `impersonate` | 8.21.0 from curl-impersonate 2.2.2 | its pinned patched BoringSSL | Existing bundle retained |

The standard version is the [upstream 8.22.0 release](https://curl.se/changes.html#8_22_0).
Its own TLS archive uses the same upstream BoringSSL revision as the other
provider, with none of the curl-impersonate patch. HTTP/2 and the compression
libraries are shared unmodified dependencies. HTTP/3 remains outside this build.

Set `GRAALPHP_CURL_PROVIDER=standard` before starting GraalPHP to use ordinary
cURL for unchanged PHP scripts. The previous default remains `impersonate`.
An invalid setting raises `ValueError` when a default provider is requested.

GraalPHP also accepts optional provider arguments, including named arguments:

```php
$normal = curl_init('https://example.com', provider: 'standard');
$browser = curl_init('https://example.com', provider: 'impersonate');
curl_impersonate($browser, 'chrome136');
$multi = curl_multi_init(provider: 'standard');
$version = curl_version(provider: 'standard');
```

These provider arguments are GraalPHP extensions. The normal PHP cURL calls
remain unchanged when selection comes from the environment. Each handle keeps
its provider for its complete lifetime. `curl_impersonate` on a standard handle
returns false / `CURLE_NOT_BUILT_IN` (4). Adding a handle to another provider's
multi returns `CURLM_BAD_EASY_HANDLE` (2) without attaching it.

Both providers use cURL multi/socket callbacks and one-shot timers on the same
PHP/libuv owner thread. They own separate multi handles and connection caches;
there is no additional event-loop thread or per-request worker pool. Native
cleanup, cancellation and easy-handle reuse retain the existing ownership rules.

Xmake builds and namespaces the standard archives; its release archive already
contains the generated release version header. `BORINGSSL_PREFIX` isolates the
TLS functions and C++ namespace; a private type prefix also isolates the global
`ssl_session_st` destructor. The libcurl archive symbol map isolates public and
internal symbols. Public cURL handle types stay opaque at the provider table.
See [native build details](native-build.md).

## Focused validation

```text
build.bat curl-test
build.bat curl-test native
build.bat curl-test trueasync
bash build.sh curl-test
bash build.sh curl-test native
build.bat curl-benchmark 3 512 64
build.bat curl-benchmark 3 512 64 0
```

The functional suite runs a PHP client against independent HTTP and HTTPS peers:
CA validation, redirects, gzip, POST, header lists, connection reuse, timeout,
protocol failure, in-flight cancellation/reuse, 16 concurrent held requests,
multi completion and removal. A small GraalPHP-specific portion checks provider
selection, mixed-provider rejection and simultaneous TLS calls using both
providers. There is no SQLite or FFI workload.

The Windows benchmark uses identical PHP source for both products, only the cURL
extension in TrueAsync, and forces GraalPHP's standard provider. It measures direct
HTTP/1.1 requests with a validated 1024-byte body and a 5 ms peer delay, after
16,384 warmup requests. Each measured coroutine reuses its easy handle. Three
alternating repetitions use fresh processes and peers, with 1,000 and 10,000
additional parked coroutines. External process counters measure CPU, resident
working set and private committed memory separately. It does not run a PHP HTTP
server, WebSocket, SQLite or FFI benchmark. HTTP performance does not establish
TLS parity: the TrueAsync binary uses a different TLS backend.

The fourth benchmark argument sets the peer delay in milliseconds; the optional
fifth sets comma-separated parked populations. The [initial baseline](curl-performance.md)
covers both the default 5 ms delay and no artificial delay. The
[optimized runtime comparison](curl-optimization.md) includes a controlled A/B,
CPU and memory with 10,000 parked tasks, and the limits of the GC/native profiles.
The [current runtime cost investigation](curl-runtime-costs.md) adds a C-only
control, separate user/kernel CPU counters, longer JIT warmup, and a further
controlled improvement from direct cURL suspension and native response copying.
The suite also checks temporary handles, empty/large bodies, cancellation before
suspension with guest cleanup, and concurrent-handle guards.
The [latency and memory report](curl-latency-memory.md) measures sampled client
p50/p90/p95/p99, external RSS distributions and paired trials without timing.
