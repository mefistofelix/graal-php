# Distruttori e resurrezione PHP

Il blocco confronta rilascio per conteggio zero, uscita dai frame,
eccezione dal distruttore e dal costruttore, cicli, riferimenti deboli
durante e dopo il callback, visibilità e shutdown ordinario. Un caso
verifica la sospensione di un distruttore nella coroutine che rilascia
l'oggetto. [Contratto e limiti](../../object-lifetime.md).

| Host | JVM | Native Image | Native Image `--interpreter` |
| --- | --- | --- | --- |
| Windows x64 | [99/99](windows-jvm.txt) | [99/99](windows-native.txt) | [99/99](windows-interpreter.txt) |
| Linux x64 (WSL) | [99/99](linux-jvm.txt) | [99/99](linux-native.txt) | [99/99](linux-interpreter.txt) |

Sono 594 esecuzioni di 11 casi distruttori, 16 WeakReference e GC, 35
ArrayAccess e 37 generatori. Passano le [regressioni Windows](windows-verify.txt)
e [Linux](linux-verify.txt). Gli [hash](products.txt) distinguono gli
eseguibili conservati e le build finali.

`build.bat destructor-test` e `build.sh destructor-test` usano gli ingressi
del corpus PHP con le stesse opzioni `native` e `--interpreter` dei test di
linguaggio. Windows usa PHP 8.6 RC2 e TrueAsync 0.10.0. Linux usa PHP 8.6-dev
del binario TrueAsync 0.10.0.

## cURL standard Windows senza distruttori applicativi

A/B Native Image rispetto a `d347711`. Si verifica il body da 1.024 byte
ricevuto dal peer HTTP/1.1 locale. 64 client eseguono 512 richieste
ciascuno. 24 processi nuovi, ordine alternato, 786.432 body controllati.
La durata include avvio, JIT e prime richieste fino alla notifica di
completamento. Throughput e CPU sono mediane dei processi senza cronometro
per richiesta. Per ogni riga il p50 RSS proviene dai campioni esterni,
il picco è il massimo e le latenze sono calcolate su 6.144 campioni.

| Sospese | Build | Req/s | CPU µs/req | RSS p50 MiB | Picco RSS MiB | p20 ms | p50 ms | p90 ms | p95 ms | p99 ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | Prima | 23814.885 | 48.637 | 111.371 | 152.887 | 2.412 | 2.554 | 3.109 | 3.412 | 5.494 |
| 1000 | Dopo | 23516.847 | 49.114 | 119.324 | 159.891 | 2.440 | 2.599 | 3.318 | 4.306 | 8.073 |
| 10000 | Prima | 21384.929 | 55.313 | 155.164 | 178.660 | 2.411 | 2.528 | 2.964 | 3.212 | 4.033 |
| 10000 | Dopo | 21033.970 | 53.883 | 160.156 | 181.266 | 2.386 | 2.498 | 2.877 | 3.071 | 3.890 |

Nella configurazione da 1.000 sospese il p99 cresce da 5,49 a 8,07 ms.
A 10.000 il p99 scende da 4,03 a 3,89 ms e il picco RSS aumenta.
Entrambe mostrano una piccola riduzione del throughput mediano. Questa
serie misura l'effetto del nuovo lowering su un carico senza distruttori.

[Ambiente](curl/environment.txt), [prove](curl/results.csv),
[campioni grezzi](curl/samples.zip).

## Carico PHP Linux senza distruttori applicativi

Cinque processi freschi per riga e build. Ogni processo esegue 524.288
chiamate e aggiornamenti. `call` usa una variabile scalare e `index` un array
annidato. Tutti i checksum coincidono. Si riusa il
[runner del confronto precedente](../reference-return-2026-11-18/run-linux.sh)
con `REFERENCE_ITERATIONS=524288`.

| Carico | Wall s prima → dopo | CPU s prima → dopo | Picco RSS MiB prima → dopo |
| --- | ---: | ---: | ---: |
| call | 0.81 → 0.77 | 0.90 → 0.85 | 174.24 → 179.00 |
| index | 0.99 → 0.99 | 1.12 → 1.13 | 193.08 → 195.00 |

[Misure per processo](linux-cold.csv).
