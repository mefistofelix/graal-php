# cURL standard: confronto Windows con TrueAsync

Questo documento conserva la baseline iniziale `-O1`. Il successivo
[profilo e confronto dopo le ottimizzazioni](curl-optimization.md) include
l'A/B con questo binario e le misure del prodotto aggiornato.

Misure del 25 settembre 2026. GraalPHP incorpora **cURL standard 8.22.0** in
parallelo a curl-impersonate 2.2.2; quest'ultimo conserva curl 8.21.0 e le proprie
patch. Il nuovo confronto usa esclusivamente il provider standard.

La suite funzionale cURL passa su Windows e Linux, sia JVM sia Native Image,
e sul binario Windows TrueAsync 0.10.0. Copre HTTPS con verifica CA, redirect,
gzip, POST, header, riuso delle connessioni, errori, timeout, cancellazione,
16 richieste bloccate contemporaneamente sul peer, multi e isolamento dei
provider. [Esiti della verifica](validation/curl-standard-2026-09-25/results.txt).

## Metodo

- Medesimo [programma PHP](../tests/php/curl-benchmark.php) per entrambi:
  **client cURL diretto**, senza server PHP, WebSocket, SQLite o FFI.
- GraalPHP Native Image, GraalVM 25.4.4.1.1, build `-O1 -march=compatibility`;
  TrueAsync Windows ufficiale 0.10.0, PHP 8.6, solo estensione cURL caricata
  oltre alle estensioni incorporate nel binario, `memory_limit=-1`.
- Entrambi riportano libcurl 8.22.0. GraalPHP usa BoringSSL non modificata;
  TrueAsync usa OpenSSL 4.0.2. **Il benchmark è HTTP/1.1 senza TLS**; HTTPS viene
  verificato funzionalmente. Questi dati non misurano la velocità crittografica.
- Peer HTTP indipendente Java 25, risposta di 1.024 byte verificata a ogni
  richiesta, keep-alive, 64 coroutine client con un easy handle ciascuna.
- 16.384 richieste di riscaldamento escluse. Poi 1.000 oppure 10.000 coroutine
  aggiuntive sospese su una future, e **32.768 richieste misurate per prova**.
- Tre ripetizioni con ordine dei runtime alternato, processi e peer nuovi.
  Due carichi: attesa imposta dal peer di 5 ms e nessuna attesa artificiale.
  Nessuna compilazione o altra suite eseguita durante le misure.
- CPU del processo campionata dall'esterno con `GetProcessTimes`; memoria con
  `GetProcessMemoryInfo`. Il tempo misurato include creazione e completamento
  delle 64 coroutine attive. RSS e commit sono letti alla fine della fase;
  il CSV include anche il picco RSS campionato ogni 25 ms.
- Intel i7-11800H, 8 core / 16 processori logici, circa 16 GiB RAM, Windows 11.

**24 prove su 24 completate:** 786.432 risposte misurate più 393.216 risposte
di riscaldamento, tutte validate. Nessuna prova esclusa. Tre campioni per punto
non costituiscono una caratterizzazione completa: sotto sono riportati mediana
e intervallo osservato, senza attribuire significatività statistica ai piccoli scarti.

## Throughput e CPU

Mediane delle tre ripetizioni. CPU in microsecondi consumati dal processo per
richiesta completata, così da confrontare il costo anche a throughput differenti.

| Attesa peer | Coroutine sospese | GraalPHP req/s | TrueAsync req/s | GraalPHP CPU µs/req | TrueAsync CPU µs/req |
| --- | ---: | ---: | ---: | ---: | ---: |
| 5 ms | 1.000 | 10.457 | 10.125 | 89,6 | 56,7 |
| 5 ms | 10.000 | 10.172 | 10.136 | 92,0 | 56,3 |
| Nessuna | 1.000 | 13.330 | 25.926 | 76,8 | 38,6 |
| Nessuna | 10.000 | 13.112 | 21.081 | 78,2 | 47,2 |

Intervalli con 10.000 coroutine sospese:

| Attesa peer | GraalPHP req/s min–max | TrueAsync req/s min–max |
| --- | ---: | ---: |
| 5 ms | 10.148–10.275 | 10.125–10.168 |
| Nessuna | 12.139–13.136 | 21.080–21.644 |

Con 5 ms di attesa il throughput è simile e il costo CPU di GraalPHP rimane
circa **1,64 volte** quello di TrueAsync. Senza attesa artificiale, con 10.000
coroutine sospese, TrueAsync completa **1,61 volte** le richieste al secondo;
GraalPHP consuma **1,66 volte** la CPU per richiesta. Con 1.000 sospese il
vantaggio di throughput TrueAsync è circa 1,95 volte.

Il peer osserva 64 endpoint TCP distinti per 32.768 richieste in tutte le prove,
eccetto una GraalPHP senza ritardo con 66 endpoint. Il riuso delle connessioni
funziona per entrambi. Entrambi i provider GraalPHP usano già il thread proprietario
PHP/libuv. Il divario residuo richiede un profilo CPU per distinguere dispatch
PHP, scheduler, gestione dei valori e passaggi NFI; questo benchmark misura il
costo complessivo e non assegna percentuali ai singoli componenti.

## Memoria

Mediane durante il carico senza ritardo artificiale:

| Coroutine sospese | GraalPHP RSS MiB | TrueAsync RSS MiB | GraalPHP commit privato MiB | TrueAsync commit privato MiB |
| ---: | ---: | ---: | ---: | ---: |
| 1.000 | 190,9 | 36,4 | 209,1 | 2.086,4 |
| 10.000 | 210,5 | 160,2 | 227,9 | 20.176,2 |

GraalPHP ha un costo residente di base maggiore. Con 10.000 sospese il suo RSS
è circa il 31% maggiore; il commit privato è invece circa **0,22 GiB contro
19,70 GiB**. Il commit privato rappresenta memoria impegnata, **non RAM fisica
residente**. Le due misure descrivono aspetti diversi e vanno mantenute separate.
I significati dei contatori sono definiti da
[PROCESS_MEMORY_COUNTERS_EX](https://learn.microsoft.com/en-us/windows/win32/api/psapi/ns-psapi-process_memory_counters_ex).

## Riproduzione e artefatti

```text
build.bat curl-test native
build.bat curl-test trueasync
build.bat curl-benchmark 3 512 64 5
build.bat curl-benchmark 3 512 64 0
```

Eseguire i benchmark in sequenza, senza altre compilazioni o suite.
Le opzioni del benchmark sono ripetizioni, richieste per coroutine, coroutine
attive e millisecondi di ritardo del peer. Ogni esecuzione crea CSV e log in
una nuova directory `build/curl-benchmark-*`.

- [CSV con 5 ms](benchmarks/curl-standard-windows-2026-09-25/delayed/results.csv)
  e [metadati](benchmarks/curl-standard-windows-2026-09-25/delayed/environment.txt).
- [CSV senza ritardo](benchmarks/curl-standard-windows-2026-09-25/zero-delay/results.csv)
  e [metadati](benchmarks/curl-standard-windows-2026-09-25/zero-delay/environment.txt).
- [Statistiche aggregate](benchmarks/curl-standard-windows-2026-09-25/summary.json),
  [prove completate](benchmarks/curl-standard-windows-2026-09-25/completed-trials.json),
  [hardware](benchmarks/curl-standard-windows-2026-09-25/hardware.json).
- [Hash sorgenti, binari e oracle](validation/curl-standard-2026-09-25/hashes.json).

Ogni cartella dei risultati conserva il driver Java usato: la seconda versione
aggiunge il parametro del ritardo, lasciando 5 ms come valore predefinito.
I risultati precedenti WebSocket/impersonate/SQLite restano storici: il carico
è diverso e non vanno interpretati come un confronto prima/dopo isolato di cURL.

I prodotti aggiornati sono `build/graalphp.exe` e `build/graalphp-linux-x64`.
Entrambi incorporano i due provider cURL/TLS. Windows mantiene le tre DLL runtime
MSVC già previste; Linux dipende da libc, libm e loader. [Selezione del provider](curl-providers.md).
