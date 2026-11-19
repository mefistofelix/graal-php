# Riferimenti deboli e raccolta dei cicli

Il blocco verifica l'invalidazione secondo gli owner PHP, l'identità di
WeakReference, il conteggio dei cicli, la raccolta automatica e la relazione
tra location e receiver attraverso sospensioni ed errori. [Contratto](../../object-lifetime.md).

| Host | JVM | Native Image | Native Image `--interpreter` |
| --- | --- | --- | --- |
| Windows x64 | [115/115](windows-jvm.txt) | [115/115](windows-native.txt) | [115/115](windows-interpreter.txt) |
| Linux x64 (WSL) | [115/115](linux-jvm.txt) | [115/115](linux-native.txt) | [115/115](linux-interpreter.txt) |

Sono 690 esecuzioni di 16 casi WeakReference e GC, 35 ArrayAccess, 27 offset
di stringa e 37 generatori. Le regressioni integrate includono il caso di
recupero di un easy cURL dopo la raccolta esplicita del multi ciclico.
[Windows](windows-verify.txt), [Linux](linux-verify.txt), [hash](products.txt).

Si può richiamare il caso mirato con `build.bat weak-reference-test` o
`build.sh weak-reference-test`, mantenendo la configurazione degli oracoli
dei test del linguaggio. Su Windows si usano PHP 8.6 RC2 e TrueAsync 0.10.0.
Su Linux TrueAsync 0.10.0 fornisce l'oracolo PHP 8.6-dev.

## cURL standard prima e dopo

Native Image Windows A/B rispetto a `5aa60e0`. HTTP/1.1 keep-alive locale,
64 client, 512 richieste per client, 1.024 byte verificati per risposta.
Sono 24 processi distinti e 786.432 risposte. Tre ripetizioni alternate
per build e popolazione. Si contano avvio, JIT, prime richieste e
drenaggio delle coroutine fino alla notifica di completamento. CPU e
throughput sono mediane dalle prove non cronometrate per singola
richiesta. RSS p50 è calcolato sui campioni esterni. Il picco è il
massimo osservato. I percentili di latenza su 6.144 campioni per riga
usano nearest rank.

| Sospese | Build | Req/s | CPU µs/req | RSS p50 MiB | Picco RSS MiB | p20 ms | p50 ms | p90 ms | p95 ms | p99 ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | Prima | 23287.388 | 50.068 | 107.961 | 178.109 | 2.459 | 2.608 | 3.292 | 3.900 | 8.066 |
| 1000 | Dopo | 23721.744 | 47.207 | 111.406 | 152.832 | 2.413 | 2.562 | 3.082 | 3.377 | 4.847 |
| 10000 | Prima | 21402.039 | 52.452 | 154.934 | 175.621 | 2.434 | 2.554 | 2.978 | 3.186 | 4.080 |
| 10000 | Dopo | 21387.345 | 52.452 | 157.762 | 181.602 | 2.398 | 2.534 | 3.021 | 3.231 | 4.088 |

A 1.000 sospese scendono il p99 e il picco RSS. L'RSS mediano sale di circa
3,4 MiB. A 10.000 sospese throughput e p99 sono simili, con un picco RSS
maggiore nella build candidata. Il confronto include l'intero blocco e
descrive host e carico della prova.

[Ambiente e hash](curl/environment.txt), [prove](curl/results.csv) e
[campioni grezzi](curl/samples.zip).

La prima build nativa ha raggiunto il contatore della mappa dei
candidati dal codice compilato. Il controllo nel loop legge ora uno
stato scalare. L'accesso alla mappa avviene quando il rilascio aggiunge
candidati e nel collector dietro il confine Truffle.
