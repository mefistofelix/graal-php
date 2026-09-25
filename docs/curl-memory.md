# cURL: memoria e avvio a freddo

Campagna Windows del 25 settembre 2026. La CLI usa ora
`engine.CompilerIdleDelay=500`: dopo 500 ms senza lavoro, Graal può terminare
i worker del compilatore e liberare i relativi isolati di compilazione.
Il valore precedente era 10.000 ms. Il JIT e il codice guest compilato restano
attivi; nuovi lavori possono riavviare i compilatori.

**Nessun warmup separato o scartato nelle misure correnti.** La politica è
registrata in `DEV_PREF.md` e applicata ai benchmark cURL, rete e laboratorio.
I rapporti precedenti conservano il loro metodo storico e non costituiscono
il confronto prima/dopo di questa modifica.

## RAM misurata

64 client cURL concorrenti, 131.072 richieste per processo, HTTP/1.1 locale,
1.024 byte verificati per risposta, nessun ritardo artificiale. Tre prove per
configurazione; processi e peer nuovi, ordine alternato. RAM e throughput
provengono dai controlli **senza raccolta delle latenze**.

| Runtime | Coroutine sospese | RSS p50, MiB | RSS p95, MiB | RSS p99, MiB | Picco campionato, MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| GraalPHP precedente | 1,000 | 150.98 | 169.52 | 169.53 | 171.35 |
| GraalPHP nuovo | 1,000 | 81.16 | 150.54 | 153.29 | 153.40 |
| TrueAsync 0.10.0 | 1,000 | 38.53 | 38.55 | 38.55 | 38.55 |
| GraalPHP precedente | 10,000 | 175.84 | 186.66 | 200.97 | 201.41 |
| GraalPHP nuovo | 10,000 | 108.15 | 158.12 | 184.05 | 184.08 |
| TrueAsync 0.10.0 | 10,000 | 162.14 | 162.20 | 162.20 | 162.20 |

La riduzione del p50 RSS è **46,2% con 1.000 sospese e 38,5% con 10.000**.
Il picco scende del 10,5% e dell'8,6%: la memoria necessaria mentre il
compilatore lavora rimane. Non è una soluzione che elimina tutti i picchi.
Con 1.000 sospese TrueAsync conserva un vantaggio netto nella RAM; con
10.000 GraalPHP ha una mediana inferiore ma un picco superiore.

La base prima del carico è circa **20,2 MiB** per entrambe le immagini
GraalPHP, contro circa 22 MiB per TrueAsync. È una lettura alla prima chiamata
cURL di controllo, dopo il bootstrap; non il primo istante del processo.
I circa 144 MiB del vecchio rapporto erano già successivi a 16.384 richieste.
Questa modifica riduce soprattutto la memoria trattenuta durante l'esecuzione,
non la base iniziale.

I quantili RSS raggruppano i campioni temporali grezzi delle tre prove, dal
lancio fino al completamento del lavoro; non sono memoria per richiesta.
Il picco è il massimo campionato, non la mediana dei picchi. L'intervallo
richiesto è 25 ms, effettivamente spesso circa 31 ms; picchi più brevi possono
sfuggire. Il working set comprende processo, JIT e librerie. Il private commit
è conservato nei dati grezzi ma non viene presentato come RAM fisica: in
TrueAsync raggiunge circa 20 GiB con 10.000 coroutine mentre RSS è circa 162 MiB.

## Tempi e CPU

Il tempo complessivo comincia **prima di avviare il processo** e termina alla
ricezione di `/end`. Include bootstrap, creazione delle coroutine, tutte le
richieste e rilascio delle coroutine sospese. Nessuna attesa artificiale viene
inserita prima del traffico. Sono esclusi soltanto stampa dei campioni e
shutdown finale del runtime. La CPU parte da zero e comprende anche il JIT.
Queste richieste/s descrivono quindi l'intero lavoro, non una finestra a regime.

Mediane delle tre prove senza cronometro, con intervalli min–max osservati:

| Runtime | Sospese | Richieste/s | Min–max richieste/s | Tempo totale, s | CPU µs/richiesta |
| --- | ---: | ---: | ---: | ---: | ---: |
| GraalPHP precedente | 1,000 | 26438 | 22957–26624 | 4.958 | 39.94 |
| GraalPHP nuovo | 1,000 | 25880 | 25092–26163 | 5.065 | 40.65 |
| TrueAsync 0.10.0 | 1,000 | 28601 | 28119–29138 | 4.583 | 34.69 |
| GraalPHP precedente | 10,000 | 25010 | 24734–25217 | 5.241 | 41.48 |
| GraalPHP nuovo | 10,000 | 25178 | 24666–25293 | 5.206 | 41.37 |
| TrueAsync 0.10.0 | 10,000 | 27498 | 16634–27974 | 4.767 | 36.00 |

Nell'A/B GraalPHP il throughput mediano cambia di −2,1% e +0,7%; la CPU per
richiesta di +1,8% e −0,3%. Tre ripetizioni non dimostrano equivalenza statistica.
TrueAsync rimane più rapido nelle mediane. Con 10.000 coroutine mostra però
forte variabilità prima del payload: `/suspended` arriva fra 0,12 e 2,59 secondi
nelle prove senza cronometro. Anche due prove con cronometro mostrano attese
prima del payload. I dati sono tutti inclusi; la causa non è stata attribuita.

## Latenze delle richieste

Millisecondi, 24.576 campioni per riga, quantili nearest-rank sui campioni
raggruppati. Si cronometra una chiamata `curl_exec` ogni 16 per client, con
offset distribuito fra i client. La raccolta parte dalle prime richieste;
non si scartano campioni. Il massimo riguarda le chiamate campionate.
Bootstrap e preparazione delle coroutine sono compresi nel tempo totale
sopra, non nella durata della singola chiamata.

| Runtime | Sospese | Media | p20 | p50 | p90 | p95 | p99 | Massimo |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| GraalPHP precedente | 1,000 | 2.526 | 2.157 | 2.291 | 3.071 | 3.250 | 4.457 | 15.948 |
| GraalPHP nuovo | 1,000 | 2.513 | 2.159 | 2.291 | 3.025 | 3.142 | 4.634 | 15.811 |
| TrueAsync 0.10.0 | 1,000 | 2.219 | 1.957 | 2.062 | 2.725 | 2.811 | 2.946 | 15.320 |
| GraalPHP precedente | 10,000 | 2.536 | 2.144 | 2.275 | 3.022 | 3.105 | 4.605 | 59.261 |
| GraalPHP nuovo | 10,000 | 2.592 | 2.156 | 2.284 | 3.065 | 3.251 | 5.120 | 60.302 |
| TrueAsync 0.10.0 | 10,000 | 2.341 | 1.952 | 2.296 | 2.794 | 2.850 | 3.089 | 15.053 |

Il p50 GraalPHP cambia poco, ma il p99 cresce del **4,0% e dell'11,2%**:
è il compromesso osservato, non una riduzione della memoria gratuita su ogni
metrica. Le tre prove non isolano la causa di ogni campione lento. La raccolta
delle latenze abbassa il throughput mediano del nuovo GraalPHP dell'1,6% e del
4,3% rispetto ai controlli; non si correggono matematicamente i percentili.
La variabilità di startup TrueAsync con 10.000 sospese impedisce di attribuire
la differenza fra le sue mediane al solo cronometro.

## Scelta e configurazione

La [documentazione Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/Options/)
descrive `CompilerIdleDelay`, il default 10.000 ms e il riavvio dei compilatori.
I flag dell'immagine verificata confermano compilazione in isolati riusati
per thread. Un'A/B diagnostica sulla stessa immagine, cambiando soltanto
questa opzione, ha mostrato un forte calo RSS; la campagna da freddo sopra
conferma il beneficio sul nuovo eseguibile.

Non è stata cambiata la policy di ownership PHP, né disabilitato il GC o il
JIT. Una traccia separata da freddo conferma compilazioni guest Tier 1 e Tier 2.
Non è stata misurata con NMT una ripartizione completa fra heap, codice e
allocazioni C: non si attribuisce ogni byte al compilatore.

La CLI consente di ripristinare il valore Graal precedente:

```powershell
build/graalphp.exe -Dpolyglot.engine.CompilerIdleDelay=10000 script.php
```

Gli host che usano `EmbeddedPhp` mantengono le proprie opzioni del
`Context.Builder`; possono impostare `.option("engine.CompilerIdleDelay", "500")`.
Il default della CLI si applica sia a JVM sia a Native Image.

Le prove diagnostiche iniziali hanno anche usato `-Xmn16m -XX:MaxHeapFree=1m`:
assieme a idle 500 ms arrivavano a circa 37–57 MiB alla fine del lavoro,
con costo CPU maggiore. Quelle prove avevano warmup e sono conservate come
**diagnostica storica**, non come risultato del metodo attuale né come profilo
consigliato. Questi limiti GC non sono stati introdotti nel prodotto.
La [documentazione Native Image](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)
spiega i compromessi fra heap, raccolte e memoria; `-Xmx` non limita da solo
l'RSS dell'intero processo.

## Riproduzione ed evidenze

```powershell
# Preservare l'eseguibile precedente prima della nuova build.
Copy-Item build/graalphp.exe build/graalphp-before-memory.exe
./build.bat curl-test native
$env:GRAALPHP_BENCH_BASELINE='build/graalphp-before-memory.exe'
$env:GRAALPHP_BENCH_WITH_TRUEASYNC='1'
$env:GRAALPHP_BENCH_LATENCY_EVERY='16'
$env:GRAALPHP_BENCH_LATENCY_CONTROL='1'
& tools/graalvm-25.4.4.1.1+1.1/bin/javac.exe --release 25 -proc:none -d build/test-classes tests/graalphp/NetworkBenchmark.java tests/graalphp/CurlBenchmark.java
& tools/graalvm-25.4.4.1.1+1.1/bin/java.exe --enable-native-access=ALL-UNNAMED -cp build/test-classes graalphp.CurlBenchmark 3 2048 64 0
```

La baseline storica è identificata dall'hash negli artefatti; copiarne una
versione diversa misura un confronto diverso. Il driver accetta inoltre
`GRAALPHP_BENCH_VM_OPTIONS` (un argomento per riga, solo sul candidato) e
`GRAALPHP_BENCH_SCRIPT` per diagnostica mirata, senza introdurre warmup.

36/36 prove complete: **4.718.592 richieste**, 147.456 durate campionate.
Tutti i quantili per prova sono stati ricalcolati indipendentemente dai CSV.
Nessuna build o altra suite è stata eseguita durante la campagna prestazionale.
Entrambi i prodotti usano cURL standard 8.22.0; curl-impersonate è invariato.
Il carico HTTP non misura le differenze dei backend TLS. La versione libuv
esatta del binario TrueAsync non è certificata da questa campagna.

- [Dati da freddo e ambiente](benchmarks/curl-memory-windows-2026-09-25/cold-start/environment.txt), [risultati](benchmarks/curl-memory-windows-2026-09-25/cold-start/results.csv), [aggregati](benchmarks/curl-memory-windows-2026-09-25/summary.json).
- [Esiti funzionali e limiti](validation/curl-memory-2026-09-25/results.txt), [hash dei prodotti e sorgenti](validation/curl-memory-2026-09-25/hashes.json).
- Diagnostica precedente separata nelle cartelle `diagnostic-*`, con copie del vecchio driver e workload in `diagnostic-snapshots`; nessuna nuova prova con warmup dopo il cambio di metodo.

Le misure prestazionali sono Windows. Linux è verificato funzionalmente e
ricostruito dagli stessi sorgenti; non vengono dichiarate prestazioni Linux.
Non sono state rieseguite le suite complete di linguaggio, WebSocket, SQLite o FFI.
