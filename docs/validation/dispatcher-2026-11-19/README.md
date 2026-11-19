# Dispatcher, libuv e deadline — 19 novembre 2026

Il task identifica il dispatcher delle sue continuation. Timer, generatori,
canali cooperativi, notifiche dei pool e callback NFI usano quel dispatcher.
Libuv appartiene allo stesso scheduler e mantiene l'ordine di chiusura
dopo il drenaggio dei pool C. La deadline host è unica per dispatcher.
Il percorso normale confronta un solo valore e le deadline specifiche
restano nella coda dei timer. La cancellazione percorre i figli dello scope.
La prova con uno scope staccato in drenaggio verifica che il limite host
possa ancora interromperlo e far eseguire il finally.

| Piattaforma | JVM | Native Image | Native Image in modalità interprete |
| --- | --- | --- | --- |
| Windows | [106](windows-jvm.txt) | [106](windows-native.txt) | [106](windows-interpreter.txt) |
| Linux x64 WSL | [106](linux-jvm.txt) | [106](linux-native.txt) | [106](linux-interpreter.txt) |

636 confronti TrueAsync e verifiche integrate [Windows](windows-verify.txt)
e [Linux](linux-verify.txt). Le build native hanno eseguito i flussi HTTP,
WebSocket e SQLite ([Windows](windows-network.txt), [Linux](linux-network.txt)),
cURL indipendente ([Windows](windows-curl.txt), [Linux](linux-curl.txt)) e
le callback FFI CLI ([Windows](windows-ffi.txt), [Linux](linux-ffi.txt)).
Il passaggio prepara dispatcher multipli nello stesso runtime. La barriera
sui grafi e lo spawn pubblico su heap condiviso restano aperti.
[SHA-256 dei prodotti](products.txt).

## Scadenza host con molti scope

A/B Linux Native Image contro `330deff`. Il programma crea zero o 2.048
scope inattivi e poi esegue 32.768 sospensioni cooperative sul task root.
Cinque processi nuovi per riga, ordine alternato, output verificato.
Wall time, CPU e picco RSS includono l'avvio e l'uscita.

| Scope inattivi | Build | Wall mediano ms | CPU mediana ms | Mediana del picco RSS MiB |
| ---: | --- | ---: | ---: | ---: |
| 0 | Prima | 38.05 | 50 | 81.62 |
| 0 | Dopo | 37.82 | 50 | 83.50 |
| 2048 | Prima | 339.70 | 400 | 116.38 |
| 2048 | Dopo | 41.64 | 60 | 85.50 |

[Runner](run-linux.sh) e [20 misure](linux-cold.csv).

## cURL standard Windows da processo fresco

A/B contro la stessa baseline. 24 processi nuovi, 786.432 body verificati.
64 client per prova, 512 richieste ciascuno, peer HTTP/1.1 locale
indipendente. L'avvio termina al primo controllo e il wall time al
controllo di completamento, prima della chiusura del processo. Req/s, CPU
e RSS provengono dalle prove senza misurazione di latenza. Ogni riga contiene
6.144 campioni di latenza dalle prove separate avviate da freddo.

| Sospese | Build | Avvio ms | Req/s | CPU µs/req | RSS p50 MiB | Picco RSS MiB | p20 ms | p50 ms | p90 ms | p95 ms | p99 ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | Prima | 27.772 | 23937.319 | 47.684 | 111.129 | 154.895 | 2.436 | 2.587 | 3.163 | 3.571 | 5.562 |
| 1000 | Dopo | 29.091 | 23345.596 | 50.068 | 114.445 | 192.586 | 2.443 | 2.587 | 3.054 | 3.323 | 5.172 |
| 10000 | Prima | 28.109 | 21239.532 | 55.790 | 160.254 | 222.430 | 2.398 | 2.514 | 2.946 | 3.172 | 4.172 |
| 10000 | Dopo | 28.762 | 21088.381 | 55.313 | 160.109 | 194.734 | 2.392 | 2.518 | 2.901 | 3.115 | 4.240 |

Il confronto cURL riguarda un carico con molte coroutine, mentre il
confronto CLI isola i molti scope. Le variazioni della coda e dei picchi
su tre ripetizioni meritano ulteriori misure sui carichi lunghi.

[Ambiente e hash](curl/environment.txt), [prove](curl/results.csv) e
[campioni grezzi](curl/samples.zip).
