# Lifetime dei risultati asincroni

La baseline è `82d6275`. La nuova build lega il rilascio del risultato di
Future, FutureState, coroutine TrueAsync, combinatori e recvAsync
all'ownership PHP. Il test del collector conta il rilascio del risultato
all'ultimo proprietario e la raccolta di un ciclo array/future. I casi
differenziali coprono attese su temporanei, stato condiviso e cancellazione,
due lettori, ricevitori abbandonati e ricevitori perdenti nei combinatori.

La prima prova del ricevitore abbandonato mostrava un warning TrueAsync per un
future mai usato. Il caso usa `ignore()` e confronta il rilascio della
ricezione. La diagnostica per future mai usati rimane aperta. Una prima
build Native Image ha bloccato la raggiungibilità della gestione del grafo
dal codice compilato. Il cleanup di `Await` passa ora per un confine Truffle.

## Compatibilità TrueAsync 0.10.0

| Host | JVM | Native Image | Native Image `--interpreter` |
| --- | --- | --- | --- |
| Windows x64 | [88/88](windows-jvm.txt) | [88/88](windows-native.txt) | [88/88](windows-interpreter.txt) |
| Linux x64 (WSL) | [88/88](linux-jvm.txt) | [88/88](linux-native.txt) | [88/88](linux-interpreter.txt) |

Il corpus esegue 528 confronti complessivi. Le regressioni integrate del
blocco e il test di ownership sono nei log [Windows](windows-verify.txt)
e [Linux](linux-verify.txt). Gli [hash](products.txt) identificano le build.

## cURL standard Windows

A/B su Native Image, stesso cURL 8.22.0 e libuv, HTTP/1.1 keep-alive su peer
locale, 1.024 byte per risposta, 64 client, 1.024 richieste per client,
1.000 o 10.000 coroutine sospese. Sono 24 processi nuovi con ordine alternato
e 1.572.864 payload verificati. La finestra va dal lancio alla notifica di
completamento del lavoro e include startup, JIT e prime richieste.
Le prove senza cronometro danno le mediane per richieste/s e CPU per richiesta,
la mediana del campione a coroutine sospese, il p50 dei campioni RSS e il
picco massimo. Le prove con cronometro danno 12.288 campioni di latenza
per riga. I percentili raggruppati usano nearest rank.

| Sospese | Build | Req/s | CPU µs/req | RSS a sospese MiB | RSS p50 MiB | Picco RSS MiB | p20 ms | p50 ms | p90 ms | p95 ms | p99 ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | Prima | 21788.005 | 48.637 | 35.500 | 84.762 | 155.520 | 2.308 | 2.427 | 2.837 | 3.192 | 5.180 |
| 1000 | Dopo | 23958.265 | 44.346 | 31.754 | 84.535 | 179.922 | 2.335 | 2.456 | 2.977 | 3.386 | 5.856 |
| 10000 | Prima | 22954.776 | 46.730 | 131.059 | 115.234 | 186.230 | 2.348 | 2.539 | 3.831 | 4.467 | 6.538 |
| 10000 | Dopo | 21621.870 | 50.068 | 108.594 | 116.387 | 174.539 | 2.322 | 2.434 | 2.865 | 3.263 | 5.635 |

A 10.000 sospese, il campione RSS a sospensione scende da 131,1 a 108,6 MiB
e il p99 da 6,54 a 5,64 ms. Il throughput mediano scende da 22.955 a 21.622
richieste/s. A 1.000 sospese aumentano il throughput e il p99. Tre
ripetizioni per configurazione descrivono questo host e questo carico.

Per riprodurre, conservare l'eseguibile precedente come
`build/nov-packed-baseline.exe`, compilare l'eseguibile corrente e usare il
runner già compilato. I log di ambiente includono opzioni e hash dei prodotti.

```powershell
$env:GRAALPHP_BENCH_BASELINE = 'build/nov-packed-baseline.exe'
$env:GRAALPHP_BENCH_LATENCY_EVERY = '16'
$env:GRAALPHP_BENCH_LATENCY_CONTROL = '1'
tools/graalvm-25.4.4.1.1+1.1/bin/java.exe --enable-native-access=ALL-UNNAMED -cp build/test-classes graalphp.CurlBenchmark 3 1024 64 0 1000,10000
```

[Ambiente](curl/environment.txt), [risultati per prova](curl/results.csv),
[percentili per prova](curl/latency.csv) e [campioni grezzi](curl/samples.zip).

## Carico di creazione e rilascio su Linux

Il programma esegue 4.096 operazioni in sequenza con un array diverso
di 128 elementi per risultato. Legge due valori, rilascia future e risultato
e verifica il checksum finale. Le configurazioni misurano Future::completed,
Channel::recvAsync su buffer e spawn con await. Entrambe le build hanno
toolchain e librerie identiche. La finestra comprende lancio e fine processo.
Le colonne mostrano le mediane dei tre processi per configurazione e build.

| Configurazione | Wall s prima → dopo | CPU s prima → dopo | Picco RSS MiB prima → dopo |
| --- | ---: | ---: | ---: |
| `completed` | 0.73 → 0.42 | 0.87 → 0.56 | 226.5 → 183.2 |
| `channel` | 0.71 → 0.44 | 0.80 → 0.60 | 226.4 → 183.2 |
| `spawn` | 0.79 → 0.47 | 1.02 → 0.74 | 301.5 → 254.9 |

Nella prima campagna GNU time ha riportato una durata negativa. La campagna
è conservata e tutte le 18 prove sono state ripetute con il tempo monotono
da `/proc/uptime`. CPU user+system e massimo RSS provengono da GNU time.

```bash
bash docs/validation/future-lifetime-2026-11-18/run-linux.sh build/graalphp-before build/graalphp
```

[Serie monotona](linux-monotonic.csv) e [prima serie](linux-wall-clock.csv).
