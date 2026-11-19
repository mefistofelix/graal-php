# Errori degli scope TrueAsync — 19 novembre 2026

I programmi confrontano handler locale e del genitore, sostituzione del
callback, attese di drenaggio con e senza handler, errore dentro l'handler,
accesso tardivo al future originale e sospensione nel task sorgente.
Il corpus aggiunge due programmi mantenuti e sette PHPT upstream intatti.

| Piattaforma | JVM | Native Image | Native Image in modalità interprete |
| --- | --- | --- | --- |
| Windows | [106](windows-jvm.txt) | [106](windows-native.txt) | [106](windows-interpreter.txt) |
| Linux x64 WSL | [106](linux-jvm.txt) | [106](linux-native.txt) | [106](linux-interpreter.txt) |

636 esecuzioni differenziali, con [verifica integrata Windows](windows-verify.txt)
e [Linux](linux-verify.txt). [Prodotti SHA-256](products.txt).
Restano da completare finalizer, distruzione implicita, warning e tutte le
combinazioni di eccezioni multiple durante la chiusura.

## cURL standard Windows da processo fresco

A/B Native Image contro `5b49a05`, include chiusura degli scope e policy degli
errori in un carico che conclude le richieste normalmente. 24 esecuzioni,
ordine alternato, 786.432 body da 1.024 byte verificati. 64 client HTTP/1.1
keep-alive sul peer locale, 512 richieste per client. Il tempo fino al
completamento include startup, JIT e prime richieste. Il tempo di avvio termina
al primo messaggio di controllo. Throughput e CPU sono mediane delle prove
senza campionamento di latenza. RSS è working set, aggregato da quei processi
a intervalli di 25 ms. Ogni riga di latenza usa 6.144 campioni, presi ogni 16
richieste a partire dalla prima finestra.

| Sospese | Build | Avvio ms | Req/s | CPU µs/req | RSS p50 MiB | Picco RSS MiB | p20 ms | p50 ms | p90 ms | p95 ms | p99 ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | Prima | 31.766 | 23395.830 | 50.068 | 112.438 | 196.605 | 2.488 | 2.661 | 3.218 | 3.515 | 5.417 |
| 1000 | Dopo | 33.747 | 22855.228 | 47.207 | 114.438 | 156.645 | 2.473 | 2.616 | 3.117 | 3.439 | 5.051 |
| 10000 | Prima | 31.776 | 20602.364 | 56.744 | 160.164 | 221.492 | 2.460 | 2.592 | 3.110 | 3.373 | 5.073 |
| 10000 | Dopo | 34.103 | 20808.101 | 54.836 | 160.070 | 186.422 | 2.438 | 2.569 | 3.043 | 3.263 | 4.459 |

Il p99 passa da 5,42 a 5,05 ms con 1.000 sospese e da 5,07 a 4,46 ms
con 10.000. Il throughput mediano scende del 2,3% a 1.000 e cresce
dell'1,0% a 10.000. Il p50 RSS varia al massimo di 2 MiB, mentre
in questa serie si osservano picchi inferiori in entrambe le configurazioni.

Confronto locale di breve durata. Le code lunghe e i picchi RSS sono
sensibili anche al momento di compilazione e raccolta. Per riprodurre
la serie usare i binari degli hash, impostare `GRAALPHP_BENCH_BASELINE`,
`GRAALPHP_BENCH_LATENCY_EVERY=16`, `GRAALPHP_BENCH_LATENCY_CONTROL=1`
e invocare `build.bat curl-benchmark 3 512 64 0 1000,10000`.

[Ambiente e hash](curl/environment.txt), [prove](curl/results.csv),
[campioni per processo](curl/samples.zip).
