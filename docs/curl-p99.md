# cURL: indagine p99 sullo stesso eseguibile

Campagna del **26 settembre 2026**, ora locale Europe/Rome; i timestamp grezzi
sono UTC del 25 settembre. Seguito di [curl-memory.md](curl-memory.md).

**Risultato:** il vantaggio RAM è confermato. L'aumento del p99 si ripresenta
con 10.000 coroutine sospese, non con 1.000. Non è ancora dimostrato quale
meccanismo produca la differenza, né è stata introdotta una correzione runtime.
`PERF-01` resta aperto; il default CLI rimane `CompilerIdleDelay=500`, con JIT.

## Confronto controllato

Entrambi i gruppi avviano **lo stesso `build/graalphp.exe`**, SHA-256
`911359acdf1069bdd382f1749da3ad33000a2dfbc7c2ca7cf57d54b47e95a712`.
Varia soltanto `-Dpolyglot.engine.CompilerIdleDelay`: 10.000 ms nel gruppo
`graalphp-before`, 500 ms nel gruppo `graalphp`. Il primo nome è una convenzione
del driver, **non** un altro binario o il vecchio eseguibile before-memory.

32/32 prove, quattro ripetizioni con ordine invertito a ogni ripetizione,
1.000/10.000 coroutine sospese, 64 client e 2.048 richieste per client.
HTTP/1.1 locale, provider cURL standard, risposta verificata di 1.024 byte,
nessun ritardo artificiale. Processi PHP e istanze del peer nuovi per prova;
**la JVM del driver/peer è condivisa fra le prove della campagna**.
Nessun warmup scartato e nessuna build o altra suite durante le misure.

RAM/CPU/throughput provengono dai controlli senza cronometro; le latenze da
prove distinte, una chiamata campionata ogni 16 per client. Ogni riga di latenza
raggruppa 32.768 campioni. RSS è il working set, non private commit; i quantili
RAM sono calcolati sui campioni temporali, non sulle singole richieste.

| Sospese | Metrica | Idle 10.000 ms | Idle 500 ms | Variazione |
| ---: | --- | ---: | ---: | ---: |
| 1.000 | RSS p50, MiB | 147,18 | 77,74 | −47,18% |
| 1.000 | Picco RSS campionato, MiB | 168,06 | 153,86 | −8,45% |
| 1.000 | Throughput mediano, richieste/s | 26.461 | 26.504 | +0,17% |
| 1.000 | CPU mediana, µs/richiesta | 39,399 | 38,743 | −1,67% |
| 1.000 | Latenza p99, ms | 4,4363 | 4,3295 | −2,41% |
| 10.000 | RSS p50, MiB | 175,53 | 102,04 | −41,86% |
| 10.000 | Picco RSS campionato, MiB | 185,76 | 183,06 | −1,45% |
| 10.000 | Throughput mediano, richieste/s | 25.222 | 25.199 | −0,09% |
| 10.000 | CPU mediana, µs/richiesta | 40,472 | 40,889 | +1,03% |
| 10.000 | Latenza p99, ms | 4,1578 | 4,5911 | +10,42% |

Il calo della mediana RSS non elimina i picchi. Quattro prove per gruppo non
certificano equivalenza del throughput o significatività della differenza p99.
Gli intervalli min–max del p99 per prova si sovrappongono: con 10.000 sospese
4,0086–4,6439 ms contro 3,7606–4,9086 ms; con 1.000, 3,4676–4,8282 contro
3,8433–4,5089 ms. Si conservano tutte le prove, compresa la prima untimed
idle 10.000/1.000 sospese, più lenta delle successive. Nessun outlier rimosso.

Il campionamento delle latenze riduce il throughput mediano rispetto ai
controlli del 3,47%/3,39% con 1.000 sospese e del 2,96%/2,23% con 10.000
(rispettivamente idle 10.000/500). Non si correggono matematicamente i quantili.
Le misure di oggi non sono aggregate con quelle storiche: il workload e il
confine temporale del driver sono stati aggiornati.

Dati: [ambiente e comandi](benchmarks/curl-p99-windows-2026-09-26/cold-ab/environment.txt),
[risultati](benchmarks/curl-p99-windows-2026-09-26/cold-ab/results.csv),
[riepilogo ricalcolabile](benchmarks/curl-p99-windows-2026-09-26/cold-ab-summary.json).

## Cosa aggiunge la diagnostica

Il vecchio CSV conservava soltanto le durate: non permetteva di collocare le
richieste lente rispetto a compilazioni e campioni RAM. Il driver ora supporta:

- Opzioni separate per baseline e candidato: A/B sul medesimo eseguibile.
- `GRAALPHP_BENCH_TIMELINE=1`: identificativo client/iterazione e istante iniziale
  oltre alla durata. Array aggiuntivi solo in diagnostica; stampa dopo `/end`.
- Ricezione monotona/UTC dei controlli, tempo di osservazione e CPU cumulativa
  nei campioni di memoria. Durata totale fino alla ricezione di `/end` sul peer,
  non fino alla successiva lettura del sampler come nel vecchio codice.

La durata complessiva include lancio PHP, inizializzazione, prime richieste e
rilascio delle coroutine sospese; esclude stampa campioni e shutdown finale.
Le letture CPU/RSS sono successive alla ricezione: i loro timestamp sono
conservati, senza farli passare per misure istantanee sullo stesso confine.
Le latenze includono la ripresa guest di `curl_exec`, non il bootstrap.

Gli orologi guest e driver non sono assunti avere la stessa origine. Due
`hrtime` racchiudono il controllo già esistente `/suspended`; la sua ricezione
sul peer determina un intervallo per l'offset. Non è aggiunto un controllo
né un'attesa di calibrazione. Nelle otto prove diagnostiche l'incertezza è
2,12–7,72 ms: sufficiente per localizzare una fase, **non** per assegnare
univocamente una richiesta a una pausa GC di pochi millisecondi. I bucket di
mezzo secondo usano il punto medio dell'intervallo, con i limiti grezzi conservati.

## Evidenze e limiti dell'attribuzione

Otto prove separate con timeline, `PrintGC`, `TraceCompilation` e
`TraceCompilationDetails`: **1.048.576 richieste**, 65.536 campioni.
Entrambi i gruppi compilano Tier 1 e Tier 2. I log mostrano anche invalidazioni
e ricompilazioni prima della fine del lavoro. Per esempio, nella seconda prova
idle 500/10.000 sospese, una invalidazione avviene intorno a 1,60 secondi dal
lancio; seguono una compilazione Tier 1 di 53 ms e una Tier 2 di 89 ms.
Questo non significa che il guest sia fermo per la somma di quei tempi.
Diverse altre invalidazioni sono vicine alla fine del lavoro e non vanno
scambiate automaticamente per churn durante il traffico.

Il log Native Image registra 17 raccolte per prova con 1.000 sospese e 16 con
10.000. I totali delle pause del processo sono 95,6–98,7 ms e 217,2–220,2 ms.
Questi totali comprendono startup e fine processo, e le array della timeline
alterano le allocazioni: **non sono una misura del costo GC del workload
ordinario**. Non è stata calibrata l'origine dell'uptime del GC Native Image.
La sola somma delle pause non spiega il delta p99 osservato nell'A/B principale.

Quattro ulteriori prove, solo con 10.000 sospese, registrano anche il GC della
JVM del driver/peer. Tra `/suspended` e `/end` il peer mostra 11–12 pause per
prova, da 0,708 a 2,934 ms, per un totale di 8,793–27,594 ms per prova.
È quindi una sorgente di interferenza osservata, non un peer privo di pause.
Le quattro prove hanno anch'esse tracing/timeline e **non** sono aggregate con
l'A/B principale. Non dimostrano che il peer causi tutto il divario fra i gruppi.

Le trace Truffle espongono attività di compilazione, non una misura diretta
della creazione/distruzione dei worker o dei loro isolati. NMT, attribuzione
delle allocazioni e tracing del lifetime dei compiler worker restano da fare.
Non è ancora giustificata l'affermazione «idle 500 peggiora il p99 perché
riavvia il compilatore». Non sono modificati GC, JIT, ownership, scheduler o
librerie per ottenere un miglior numero.

Evidenze: [diagnostica](benchmarks/curl-p99-windows-2026-09-26/diagnostic-summary.json),
[diagnostica con peer](benchmarks/curl-p99-windows-2026-09-26/peer-diagnostic-summary.json),
[log GC del peer](benchmarks/curl-p99-windows-2026-09-26/peer-diagnostic/peer-gc.log).
Le descrizioni delle opzioni sono nella
[documentazione Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/Options/),
[Native Image](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)
e [logging JDK 25](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html).
Le versioni effettivamente eseguite sono registrate negli esiti, non dedotte
da quale documentazione sia oggi pubblicata.

## Riproduzione e verifica

Dopo la normale preparazione del progetto con `build.bat`, compilare solo il
driver, fuori da qualsiasi misura:

```powershell
$java = 'tools/graalvm-25.4.4.1.1+1.1/bin/java.exe'
$javac = 'tools/graalvm-25.4.4.1.1+1.1/bin/javac.exe'
& $javac --release 25 -proc:none -d build/test-classes tests/graalphp/NetworkBenchmark.java tests/graalphp/CurlBenchmark.java tests/graalphp/CurlBenchmarkTest.java
& $java --enable-native-access=ALL-UNNAMED -cp build/test-classes graalphp.CurlBenchmarkTest
uv run tests/test_curl_benchmark_report.py

# Usare una shell senza altre variabili GRAALPHP_BENCH_* ereditate.
$env:GRAALPHP_BENCH_BASELINE = 'build/graalphp.exe'
$env:GRAALPHP_BENCH_BASELINE_VM_OPTIONS = '-Dpolyglot.engine.CompilerIdleDelay=10000'
$env:GRAALPHP_BENCH_VM_OPTIONS = '-Dpolyglot.engine.CompilerIdleDelay=500'
$env:GRAALPHP_BENCH_LATENCY_EVERY = '16'
$env:GRAALPHP_BENCH_LATENCY_CONTROL = '1'
& $java --enable-native-access=ALL-UNNAMED -cp build/test-classes graalphp.CurlBenchmark 4 2048 64 0
```

Per la diagnostica togliere `GRAALPHP_BENCH_LATENCY_CONTROL`, impostare
`GRAALPHP_BENCH_TIMELINE=1` e `GRAALPHP_BENCH_TRACE_COMPILATION=1`; aggiungere
`-XX:+PrintGC` e `-Dpolyglot.engine.TraceCompilationDetails=true`, ciascuno su
una nuova riga, a **entrambe** le variabili di opzioni VM. Usare due ripetizioni.
La prova del peer aggiunge alla JVM del driver, prima del classpath:

```text
-Xlog:gc*:file=build/perf01-peer-gc.log:utctime,uptimemillis,level,tags
```

e seleziona `CurlBenchmark 2 2048 64 0 10000`. Conservare quel log come
`peer-gc.log` nella cartella dei dati affinché il report lo includa.
Ricalcolo senza eseguire richieste:

```text
uv run tests/curl_benchmark_report.py docs/benchmarks/curl-p99-windows-2026-09-26/cold-ab --output build/perf01-recomputed.json
```

26 controlli Java e 7 test Python passati. Ricalcolati e verificati contro i
CSV tutti i percentili per prova, i conteggi, la monotonicità dei contatori e i
confini temporali delle **44/44 prove principali/diagnostiche**, per complessive
5.767.168 richieste e 229.376 durate campionate. Sei prove brevi di protocollo
sono archiviate a parte, comprese le due iniziali con l'ancoraggio poi spostato
da `/begin` a `/suspended`; non entrano nei risultati prestazionali.

[Esiti e limiti](validation/curl-p99-2026-09-26/results.txt),
[hash di sorgenti/tooling/prodotti](validation/curl-p99-2026-09-26/hashes.json).
Solo benchmark e analisi modificati: eseguibili Windows/Linux invariati.
Nessuna nuova build Native Image, suite funzionale completa o misura Linux.

**Prossimo passo:** separare il costo di allocazione/GC e le invalidazioni guest
senza aggiungere array alla misura principale; controllare anche la JVM del
peer e osservare il lifetime effettivo dei compiler worker. Valutare una
modifica runtime solo con nuova verifica da freddo RAM/CPU/p99 insieme.
