# cURL: percentili di latenza e RAM

Rapporto storico con warmup escluso. Le misure correnti includono avvio e
prime richieste: [memoria e prestazioni da freddo](curl-memory.md).

Confronto Windows del 25 settembre 2026 fra GraalPHP Native Image e TrueAsync
0.10.0. Rispetto al [rapporto sui costi del runtime](curl-runtime-costs.md),
questa campagna misura le durate delle singole chiamate `curl_exec` e la
distribuzione della memoria residente durante il traffico.

## Latenza per richiesta

64 client concorrenti, HTTP/1.1 locale, body verificato di 1.024 byte, nessun
ritardo artificiale. Ogni prova esclude 16.384 richieste di riscaldamento e
misura 131.072 richieste. Tre ripetizioni per configurazione, processi e peer
nuovi, ordine alternato. I percentili seguenti sono calcolati sui **24.576
campioni per riga**, raggruppando le tre prove; non sono percentili delle
medie delle prove.

Tutti i tempi sono millisecondi. Il massimo è quello dei campioni, non di
tutte le richieste eseguite.

| Runtime | Coroutine sospese | Media | p20 | p50 | p90 | p95 | p99 | Massimo campionato |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| GraalPHP | 1.000 | 2,914 | 2,674 | 2,849 | 3,210 | 3,465 | 5,365 | 18,644 |
| TrueAsync | 1.000 | 2,606 | 2,473 | 2,608 | 2,812 | 2,898 | 3,854 | 9,186 |
| GraalPHP | 10.000 | 2,969 | 2,698 | 2,847 | 3,136 | 3,284 | 5,463 | 63,902 |
| TrueAsync | 10.000 | 2,573 | 2,473 | 2,596 | 2,778 | 2,834 | 3,083 | 11,511 |

p50 è la mediana; p99 è il valore entro cui cade il 99% dei campioni.
GraalPHP presenta qui un p50 circa il 9–10% più alto, mentre il divario
osservato al p99 è circa il 39% con 1.000 sospese e il 77% con 10.000.
La differenza in coda è quindi maggiore di quella al centro della distribuzione.
Non è stata attribuita una causa specifica ai massimi o ai singoli campioni lenti.

## RAM durante il traffico

Queste misure provengono dalle prove abbinate **senza cronometraggio delle
richieste**. Si evita così di attribuire al prodotto la memoria aggiuntiva
necessaria per raccogliere le latenze. RSS è il working set residente del
processo intero, inclusi runtime, compilatore guest e librerie.

| Runtime | Coroutine sospese | RSS p50, MiB | RSS p95, MiB | RSS p99, MiB | Picco RSS campionato, MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| GraalPHP | 1.000 | 204,13 | 211,35 | 211,37 | 211,67 |
| TrueAsync | 1.000 | 38,58 | 38,59 | 38,59 | 38,59 |
| GraalPHP | 10.000 | 222,93 | 230,55 | 230,75 | 230,75 |
| TrueAsync | 10.000 | 162,36 | 162,36 | 162,36 | 162,36 |

Sono quantili dei campioni temporali di memoria, non memoria per richiesta.
I gruppi contengono rispettivamente 555, 518, 569 e 515 campioni. Il picco è
il massimo di tutti i campioni delle tre prove, non la mediana dei tre picchi;
può non catturare picchi più brevi dell'intervallo di campionamento.

Per separare il costo del processo dalle coroutine sospese, ecco la mediana
delle tre rilevazioni nelle fasi ferme, sempre senza cronometraggio:

| Runtime | Sospese previste | RSS dopo warmup, MiB | RSS dopo sospensione, MiB |
| --- | ---: | ---: | ---: |
| GraalPHP | 1.000 | 143,57 | 158,82 |
| TrueAsync | 1.000 | 23,84 | 36,72 |
| GraalPHP | 10.000 | 143,59 | 161,13 |
| TrueAsync | 10.000 | 23,80 | 160,59 |

Il private commit riportato da `GetProcessMemoryInfo` è conservato separatamente:
il p50 durante il traffico è 221,71/2.150,68 MiB per GraalPHP/TrueAsync con
1.000 sospese e 240,31/20.240,40 MiB con 10.000. Questi contatori non sono
RAM residente e non vengono usati come tali nelle tabelle RSS. Non si deduce
un costo fisso per coroutine dai delta RSS: allocazione e riuso degli heap
rendono questi delta dipendenti dalla storia del processo.

## Costo della misurazione

Ogni configurazione è eseguita con e senza campionamento della latenza, sullo
stesso binario. Mediane delle tre prove:

| Runtime | Sospese | Req/s senza timer | Req/s con timer | Variazione | CPU senza timer, µs/req | CPU con timer, µs/req |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| GraalPHP | 1.000 | 22.834 | 22.067 | −3,36% | 45,30 | 48,88 |
| TrueAsync | 1.000 | 24.525 | 24.543 | +0,07% | 40,65 | 40,65 |
| GraalPHP | 10.000 | 22.163 | 21.376 | −3,55% | 46,61 | 48,28 |
| TrueAsync | 10.000 | 24.323 | 24.707 | +1,58% | 41,13 | 40,29 |

La variazione positiva di TrueAsync è compatibile con variabilità fra processi;
non si interpreta come un vantaggio del timer. Il costo osservato in GraalPHP
è misurabile. Non viene sottratto matematicamente dai percentili: la tabella
di latenza descrive il percorso strumentato e non dimostra quale sarebbe
esattamente il p99 senza strumentazione.

Anche la RAM viene perturbata: nelle prove GraalPHP con timer la mediana
della RSS finale è circa 256/276 MiB, contro 203/223 MiB senza timer.
Per questo i numeri RAM principali sono presi dai controlli senza timer.
La sola differenza di throughput non quantifica tutti gli effetti del
campionamento sulle code della distribuzione.

## Metodo e riproduzione

Il medesimo script PHP usa `hrtime(true)` subito prima e dopo `curl_exec`:
si includono copia della risposta, sospensione e ripresa guest. Verifica del
body e registrazione del campione vengono dopo il secondo timestamp.
L'assegnazione del risultato e il dispatch del cronometro fanno parte
dell'intervallo. È latenza trascorsa, non tempo CPU individuale.

Si campiona una richiesta ogni 16 per coroutine, con offset iniziale diverso
in base all'indice del client. Si raccolgono 8.192 durate per prova; i campioni
del warmup sono scartati. La stampa dei valori grezzi e il calcolo dei quantili
avvengono dopo la finestra di traffico. Il campionamento sistematico può
risentire di periodicità del carico; non misura ogni richiesta.

I quantili usano nearest rank: dopo ordinamento, posizione `ceil(p * N)`.
Il carico è a concorrenza fissa: ciascun client invia la richiesta successiva
dopo il completamento della precedente. Non è un test a tasso di arrivo
indipendente e non quantifica una coda di richieste esterna sotto sovraccarico.

La memoria è letta esternamente con una cadenza nominale di 25 ms; i timestamp
effettivi sono conservati. Sono inclusi i campioni agli estremi della finestra.
CPU e throughput coprono anche creazione e drenaggio delle coroutine attive,
ma escludono warmup, stampa e ordinamento dei campioni. Le connessioni sono
riusate: nella campagna finale il peer osserva 64–88 endpoint su GraalPHP e
64 su TrueAsync. Non si assume un numero identico di riconnessioni.

Per consentire questa misura è stata aggiunta la funzione PHP
[`hrtime`](https://www.php.net/manual/en/function.hrtime.php) al runtime,
basata sul cronometro monotono del sistema tramite `System.nanoTime`.
Sono verificati forma numerica, coppia secondi/nanosecondi, argomento nominato
e avanzamento attraverso una sospensione. Le librerie cURL, il reactor,
il modello di sospensione e le opzioni Native Image restano quelli della
versione precedente. Il prodotto Windows misurato ha SHA-256
`f84539cde1b2976077f4b29c68d3737f1ab096add2e8662f78d0a5d328c36c53`.

La suite cURL con i nuovi controlli del cronometro passa su Windows e Linux,
JVM e Native Image, e su TrueAsync Windows. Gli eseguibili aggiornati sono
`build/graalphp.exe` (85,98 MiB) e `build/graalphp-linux-x64` (88,82 MiB).
Le prestazioni di questo rapporto sono misurate su Windows; Linux ha una
verifica funzionale e di compilazione.

Il confronto resta HTTP: GraalPHP e TrueAsync dichiarano cURL 8.22.0 ma usano
backend TLS diversi. Non stabilisce prestazioni HTTPS equivalenti.

In PowerShell, dopo la compilazione:

```powershell
$env:GRAALPHP_BENCH_LATENCY_EVERY = '16'
$env:GRAALPHP_BENCH_LATENCY_CONTROL = '1'
& tools/graalvm-25.4.4.1.1+1.1/bin/java.exe --enable-native-access=ALL-UNNAMED -cp build/test-classes graalphp.CurlBenchmark 3 2048 64 0
Remove-Item Env:GRAALPHP_BENCH_LATENCY_EVERY, Env:GRAALPHP_BENCH_LATENCY_CONTROL
```

`build.bat curl-benchmark 3 2048 64 0` compila prodotto e driver e usa gli
stessi override. Senza `GRAALPHP_BENCH_LATENCY_EVERY`, il cronometro è disattivato;
il campionamento esterno della memoria resta attivo. Non eseguire build o altre
suite insieme alle prove prestazionali.

La campagna finale passa **24/24 prove**, 3.145.728 richieste misurate,
393.216 richieste di warmup e 98.304 durate campionate. Un tentativo precedente
si era fermato dopo 13 prove riuscite: TrueAsync era terminato durante la
creazione delle 10.000 coroutine, prima della fase misurata. Il vecchio driver
non registrava il codice d'uscita e non è stata determinata la causa. Il
fallimento è conservato; la campagna completa è stata rieseguita dopo aver
migliorato i diagnostici e non lo ha riprodotto. I numeri di questo rapporto
provengono soltanto dalla campagna finale.

[Dati finali e aggregati](benchmarks/curl-latency-memory-windows-2026-09-25/final/summary.json),
[percentili per prova](benchmarks/curl-latency-memory-windows-2026-09-25/final/latency.csv),
[RAM per prova](benchmarks/curl-latency-memory-windows-2026-09-25/final/memory.csv),
[tentativo incompleto](benchmarks/curl-latency-memory-windows-2026-09-25/incomplete/failure.txt),
[sorgenti del driver](benchmarks/curl-latency-memory-windows-2026-09-25/snapshots),
[verifica funzionale e build](validation/curl-latency-memory-2026-09-25/results.txt).
