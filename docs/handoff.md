# Stato di consegna GraalPHP

Aggiornato: **26 settembre 2026**. Punto d'ingresso: [AGENTS.md](../AGENTS.md).
Questo documento permette di riprendere senza la chat; [TODO.md](../TODO.md)
contiene il lavoro aperto. Gli hash e i risultati sono quelli dell'ultima
campagna indicata, non una garanzia che ogni futuro checkout vi corrisponda.

## Dove siamo

Runtime sperimentale PHP 8.6 direttamente su Truffle Bytecode DSL, con JVM e
Native Image Windows/Linux x64 funzionanti. Non ancora un sostituto generale
di PHP; non sono completate tutte le milestone del design.

**L'utente ha chiesto di continuare lo sviluppo funzionale e riverificare le
performance più avanti.** Non riprendere automaticamente PERF-01. Il blocco
corrente è [enum, match, clone, offset di stringa e strict_types](language-values.md),
con singleton per richiesta, valutazione lazy e hook sospendibili. I contratti
delle classi precedenti rimangono verificati; errori, ownership e generazioni
continuano a usare la stessa pipeline Bytecode DSL.

Il seguito funzionale riguarda iteratori, attributi e unpacking, insieme a
valori, firme dei builtin e TrueAsync. Il
loader SPL predefinito, include_path e il binding completo tra unità restano
aperti: non dichiarare Composer funzionante. Shared-memory threading e FFI
restano priorità successive; Composer applicativo, Compose e mobile sono rinviati.
Procedere nel lavoro già autorizzato senza riconfermare queste decisioni.

Il default CLI `engine.CompilerIdleDelay=500` rimane invariato e il JIT attivo.
La diagnostica precedente [cURL p99](curl-p99.md) resta storica: nessuna nuova
misura RAM/CPU/throughput/p99 è stata eseguita per i blocchi autoload/classi/valori. Quando
si tornerà alle performance, mantenere il vincolo di nessun warmup escluso e
separare GC/invalidazioni, peer e lifetime reale dei compiler worker.

## Blocco funzionale: enum e valori, 26 settembre

[Contratti e limiti](language-values.md),
[evidenze](validation/language-values-2026-09-26/README.md),
[esiti](validation/language-values-2026-09-26/results.txt),
[hash](validation/language-values-2026-09-26/hashes.json).

155 programmi nuovi: 90 enum/match/clone, 27 offset di stringa e 38 typing.
Passano su Windows/Linux JVM, Native Image e Native Image `--interpreter`:
930 esecuzioni, 756 confronti di output e 174 rifiuti semantici. Per i rifiuti
non sono asseriti identici codici numerici di uscita o interi messaggi fatal.
Gli input sono gli stessi 163 file PHP/fixture, con hash in tutti i 18 report.
Windows usa PHP 8.6.0RC2 e TrueAsync secondo il caso; Linux usa TrueAsync/PHP 8.6
anche per i casi sincroni, non un PHP stock separato.

Il typing degli argomenti usa il chiamante, quello dei ritorni la funzione;
trait, alias, closure, include/eval e autoload differito conservano il contesto
corretto. Non significa che tutte le firme dei builtin siano già implementate.
Gli enum sono singleton di richiesta e i dati Java non introducono root PHP
nascosti. Match/clone e assegnazioni a offset possono sospendersi. Gli offset
sono byte-oriented e conservano stringhe non UTF-8, warning e readonly.

Le regressioni finali passano: 93 contratti classi in tutte e sei le modalità;
38 autoload e 82 TrueAsync su entrambe le piattaforme JVM/native; 88/82 scenari
integrati Windows/Linux (sei sono specifici della DLL Windows), 53 scenari
semantici e 256 grafi/1.280 fasi collector per piattaforma. Passano 50 asserzioni
rete per prodotto, le suite cURL JVM/native e i quattro casi fatal-C in ogni
combinazione piattaforma/modalità. I contatori C e il riuso del contesto sono
verificati su JVM, oltre a 80 checkpoint full-GC per piattaforma; non è stato
ricostruito un runner native separato che forzi il GC.

Il test di reload cambia casi enum e strict_types durante una richiesta:
quella già avviata mantiene entrambi i vecchi contratti, la successiva ottiene
i nuovi. Le fixture restano su filesystem temporaneo nativo, non su /mnt/c.
L'esempio `examples/enums.php` passa anche nei prodotti copiati e con interpreter.

I due Native Image sono ricostruiti in sequenza; 98 file src/tests sono identici
nelle due copie, 108 hash complessivi. Sono conservati i tentativi di build
falliti prima delle correzioni al template enum e al boundary dell'errore match.
Una chiamata FFI Linux aveva il nome errato della fixture: corretto soltanto
il percorso, poi passati i controlli rimanenti. Nessun lavoro/build lasciato
interrotto. Le baseline precedenti sono in `build/before-language-values-2026-09-26/`.

Il progetto e LANG-01 rimangono aperti; non riprendere le performance per
inerzia dopo questo blocco. Seguire il prossimo contratto funzionale insieme
alle dipendenze di valori, lifetime e coroutine.

## Blocco precedente: contratti delle classi, 26 settembre

[Contratto e limiti](class-contracts.md),
[evidenze](validation/class-contracts-2026-09-26/README.md),
[esiti](validation/class-contracts-2026-09-26/results.txt),
[hash](validation/class-contracts-2026-09-26/hashes.json).

Interfacce, trait annidati, precedenze/alias, classi e metodi astratti/finali,
varianza, proprietà/costanti ereditate e tipi composti condividono la pipeline
Bytecode DSL. Il linker conserva definizioni immutabili e viste per richiesta;
le dipendenze si caricano sulla coroutine corrente prima della pubblicazione.
La cancellazione durante la composizione e il reload dei trait hanno test
espliciti. `instanceof` non forza autoload; query e diagnostiche dichiarano i
limiti del catalogo builtin invece di simulare reflection completa.

La nuova suite contiene 93 programmi: 61 con output identico e 32 con rifiuto
semantico richiesto. Questi ultimi non affermano identità delle diagnostiche o
del codice numerico di uscita. Gli errori fatali non eseguono catch/finally guest;
la richiesta conserva invece il cleanup Java/native. Il test nativo dedicato
verifica quattro combinazioni di callback sincrona/async, immediata/sospesa.
Il rapporto distingue i contatori C verificati su JVM dalle uscite CLI del
prodotto. Il test FFI con 80 richieste esplicite di full GC rimane separato.

La prima build Native Image ha rilevato un confine mancante nel percorso di
risoluzione di `::class`; la risoluzione generica è ora un TruffleBoundary.
Il wrapper Windows dei nuovi test usa chiamate a subroutine fuori da blocchi
parentetici, affinché un fallimento nativo non restituisca successo. Il rifiuto
è stato riprodotto: codice nonzero, binario precedente invariato e nessun test
eseguito sul prodotto vecchio. Le prove fallite restano archiviate.

I default statici dei trait ora vengono risolti nella classe utilizzatrice,
non durante la sola dichiarazione del trait. L'accesso diretto al trait ha
storage separato e diagnostica di deprecazione. Un'ulteriore regressione del
driver host è stata riprodotta: completion pronta fra pump/deadline → allarme
a zero. `EmbeddedPhp` riposta ora il lavoro immediato; il test controlla una
vera attesa di rete e usa libuv esplicito in entrambi i contesti. Passano 12
ripetizioni Windows del test mirato, senza rimuovere le precedenti fallite.

Windows e Linux passano tutti i 93 programmi su JVM, Native Image e sullo
stesso Native Image con `--interpreter`: 558 esecuzioni, non casi diversi.
Passano inoltre 38 autoload e 82 TrueAsync in entrambe le modalità/piattaforme,
83/77 integrazioni, 53 scenari dei valori, 256 grafi/1.280 fasi collector,
50 asserzioni rete per prodotto e le suite cURL dedicate. Il driver host passa
12 ripetizioni Windows e 6 Linux. Le quattro combinazioni fatal/C passano su
JVM e prodotto CLI; 80 richieste di full GC per piattaforma nella suite JVM
FFI ordinaria. Il runner Native Image dedicato al forced-GC non è stato
ricostruito in questo blocco.

I 92 file src/tests sono identici fra root e copia Linux; gli eseguibili sono
ricostruiti in sequenza e il Linux finale è copiato e avviato dalla root.
Il manifest ha 105 voci fra sorgenti, test, build script, esempi e prodotti.
I conteggi dettagliati e i controlli non eseguiti sono nel registro del blocco. Le baseline pre-classi sono conservate localmente in
`build/before-class-contracts-2026-09-26/`; non sono nuove misure prestazionali.

## Blocco precedente: autoload, 26 settembre

[Contratti e limiti](autoload.md),
[esiti e prodotti](validation/autoload-2026-09-26/results.txt),
[hash](validation/autoload-2026-09-26/hashes.json).

Il registro implementa class_exists e le operazioni SPL con callback esplicite.
Le callback conservano receiver e ambiente anche durante auto-rimozione,
sospensione o cancellazione; le chiamate differite mantengono locazioni per
riferimento e argomenti nominati. La ricorsione implicita è protetta per nome,
mentre spl_autoload_call rimane esplicita. I programmi differenziali coprono
anche modifica della coda mentre è in esecuzione, namespace, costruzione
con nome/oggetto, caricamento di padre/figlio e cleanup del guard dopo errore.

Un nuovo test verifica che un autoload iniziato dopo la pubblicazione di una
nuova generazione legga ancora il vecchio snapshot della richiesta. La richiesta
successiva riceve il nuovo codice e un registro vuoto. Le fixture WatchService
usano il filesystem temporaneo nativo: la build può stare su /mnt/c, ma non
viene dichiarato funzionante il watcher sulla directory Windows montata.

Windows: 38/38 programmi autoload su JVM e Native Image; 79 scenari integrati,
53 scenari semantici e 256 grafi di ownership/1.280 fasi del collector passati.
82 programmi differenziali TrueAsync passati su JVM e Native Image; 50
asserzioni di rete/TLS/WebSocket/cURL/SQLite sul nuovo eseguibile. L'esempio
`examples/autoload.php` passa su JVM, Native Image e con `--interpreter`.
Linux: 38/38 programmi autoload su JVM e Native Image, usando l'oracolo
TrueAsync/PHP 8.6 anche per i casi sincroni (non un PHP stock separato).
73 scenari integrati, gli stessi 53 scenari/256 grafi e 82 confronti TrueAsync
su entrambe le modalità passati; 50 asserzioni di rete nel nuovo binario.
I sei scenari nativeBundle del runner di integrazione sono condizionati alla
DLL Windows: il conteggio Linux inferiore non è una dichiarazione di parità
per quei sei casi. L'esempio passa anche su Linux JVM/native/interpreter.

I due Native Image sono stati ricostruiti in sequenza e il prodotto Linux è
stato copiato in `build/graalphp-linux-x64`, quindi avviato anche da lì.
87 file sotto src/tests sono identici fra workspace e copia Linux; gli hash
includono inoltre build script, esempi e prodotti. Nessuna build o verifica
interrotta da riprendere. Le suite FFI-bridge/cURL dedicate non sono state
rieseguite integralmente in questo blocco; i percorsi coperti dall'integrazione
restano distinti nel registro.

La pipeline è la stessa Bytecode DSL: ClassLoading fornisce lo stato per
richiesta e gli helper PHP riprendibili, non un altro interprete o un worker.
`LANG-01` resta aperto; limiti del loader predefinito, class catalog, binding
completo e diagnostica sono espliciti. Le vecchie baseline pre-autoload sono
conservate localmente in `build/before-autoload-2026-09-26/` per il futuro
confronto prestazionale, con gli hash precedenti, non come nuovi risultati.

## Blocco precedente: PERF-01, 26 settembre

[Rapporto](curl-p99.md), [esiti](validation/curl-p99-2026-09-26/results.txt),
[hash](validation/curl-p99-2026-09-26/hashes.json),
[dati e riepiloghi](benchmarks/curl-p99-windows-2026-09-26/README.md).

32/32 prove da freddo, quattro ripetizioni alternate, stesso eseguibile Windows
con idle 10.000/500 ms, controlli untimed separati dalle latenze. RSS p50
147,18→77,74 MiB con 1.000 sospese e 175,53→102,04 MiB con 10.000; throughput
mediano +0,17%/−0,09%, CPU per richiesta −1,67%/+1,03%. P99 campionato
4,4363→4,3295 ms e 4,1578→4,5911 ms: −2,41%/+10,42%. I min–max per prova
si sovrappongono; non sono dimostrate equivalenza o significatività statistica.

Altre 8 prove con timeline/JIT/GC e 4 con GC della JVM del peer: 44/44 prove
complessive, 5.767.168 richieste e 229.376 campioni. Le diagnostiche restano
separate: gli array della timeline alterano le allocazioni. Osservate
compilazioni Tier 1/2, invalidazioni, pause Native Image e pause del peer;
nessuna attribuzione causale del delta p99, nessun NMT. Driver e peer condividono
una JVM fra le prove; ogni processo PHP e istanza HttpServer sono nuovi.

Il driver conserva timestamp e limiti dell'allineamento guest/host, CPU
cumulativa nei campioni RAM e ricezione dei controlli. Il confine wall-time
ora è realmente la ricezione di `/end`, distinta dalla successiva lettura dei
contatori. Nessuna fase di warmup esclusa. 26 controlli Java e 7 test Python
passati; 6 prove brevi GraalPHP/TrueAsync archiviate separatamente.

I sorgenti prodotto e gli hash dei due eseguibili sono invariati. Nessuna nuova
build Native Image, esecuzione Linux o suite funzionale completa. Le verifiche
funzionali e la parità Windows/Linux del 25 settembre restano storiche.
Ricalcolo offline: `uv run tests/curl_benchmark_report.py <cartella-dati>`;
comandi completi e nuovi flag nel rapporto. Il blocco tooling è concluso,
`PERF-01` resta aperto e non c'è un processo/build interrotto da riprendere.

## Precedente verifica runtime e metodo, 25 settembre

[Rapporto precedente](curl-memory.md), [esiti](validation/curl-memory-2026-09-25/results.txt),
[hash](validation/curl-memory-2026-09-25/hashes.json),
[dati da freddo](benchmarks/curl-memory-windows-2026-09-25/cold-start/environment.txt).

36/36 prove Windows: 4.718.592 richieste, 147.456 durate campionate,
64 client, 1.000/10.000 coroutine sospese, tre ripetizioni per configurazione.
Stesso PHP su GraalPHP precedente, nuovo e TrueAsync; ordine alternato,
controlli senza cronometro per RSS/CPU/throughput, prove con campionamento
1/16 per p20/p50/p90/p95/p99. Nessuna build o altra suite durante le misure.

| Sospese | RSS p50 prima → ora, MiB | Picco prima → ora, MiB | Throughput mediano | p99 prima → ora, ms |
| --- | ---: | ---: | ---: | ---: |
| 1.000 | 150,98 → 81,16 | 171,35 → 153,40 | −2,1% | 4,457 → 4,634 |
| 10.000 | 175,84 → 108,15 | 201,41 → 184,08 | +0,7% | 4,605 → 5,120 |

La base alla prima chiamata di controllo è circa 20,2 MiB, non 144 MiB:
quest'ultimo numero dei vecchi rapporti era dopo 16.384 richieste scartate.
La crescita del p99 è osservata, ma non ancora attribuita a una causa isolata.
Tre ripetizioni non dimostrano equivalenza statistica. TrueAsync con 10.000
sospese mostra attese pre-payload variabili: sono incluse, senza causa accertata.

Il tempo totale corrente parte prima del lancio e termina a lavoro completato,
compreso il rilascio delle coroutine sospese; esclude stampa dei campioni e
shutdown finale del runtime. Le latenze riguardano `curl_exec`, non il bootstrap.
RSS è il working set del processo, distinto da private commit e dimensione file.
I quantili RAM sono temporali; i picchi sono campionati, non massimi istantanei.

Sono passate le suite cURL Windows Native/JVM (110/100 asserzioni) e Linux
Native/JVM (112/100); i conteggi dipendono anche dal polling. Traccia JIT
separata: 8 compilazioni Tier 1, 4 Tier 2. Parità di 82 file sorgente/test fra
workspace Windows e copia Linux. Nessuna nuova suite completa linguaggio,
WebSocket, SQLite o FFI nell'ultima campagna; le verifiche precedenti restano
collegate in [validation.md](validation.md). Nessun risultato prestazionale Linux.

## Toolchain e artefatti identificati

Versioni usate, non affermazioni su quale sia l'ultima release disponibile:

| Componente | Riferimento fissato |
| --- | --- |
| GraalVM Windows | 25.4.4.1.1+1.1, Java 25.0.4.1.1 |
| GraalVM Linux | SDKMAN `25.4.4+1-graal` |
| Truffle/NFI | 25.4.4.1.1, `dependencies.lock` |
| Oracoli | PHP 8.6.0RC2; TrueAsync 0.10.0 / PHP 8.6.0-dev / ABI v0.26.0 |
| TrueAsync sorgenti | `6acdd07ff500f5799ea83bbf333d686b5dacabbf` |
| libuv GraalPHP | 1.52.1 + patch Electron fissata |
| cURL standard | 8.22.0, BoringSSL separata |
| curl-impersonate | 2.2.2, curl 8.21.0: aggiornare insieme, non separatamente |

Non è stata certificata l'esatta versione libuv incorporata nel binario
TrueAsync. Il confronto corrente è HTTP e non prova equivalenza dei backend TLS.

| File locale ricreabile | Byte | SHA-256 |
| --- | ---: | --- |
| `build/graalphp.exe` | 90.918.912 | `ff54657630d2318f28a047d8173ffc3ac66026bc358a222e3c50f5bb6c2aef43` |
| `build/graalphp-linux-x64` | 93.653.800 | `eb259141b03e7028d101b8d108f8cc5571a0ad1588673af2aa0a9651feefac51` |
| `build/graalphp-before-memory.exe` | 90.161.152 | `f84539cde1b2976077f4b29c68d3737f1ab096add2e8662f78d0a5d328c36c53` |

Le copie `build/before-autoload-2026-09-26/graalphp.exe` e
`build/before-autoload-2026-09-26/graalphp-linux-x64` mantengono rispettivamente
90.210.304 e 92.932.904 byte, con gli hash della precedente campagna PERF-01.
Non scambiare le nuove dimensioni dei file per misure di RAM del runtime.

`build/graalphp-before-latency.exe` e `build/graalphp-before-runtime-costs.exe`
sono baseline più vecchie: non scambiarle per quella della riduzione RAM.
Gli archivi di evidenze non contengono i binari. `build/` e `tools/` possono
sparire: le loro copie non sostituiscono sorgenti, pin ed evidenze mantenuti.
Git è inizializzato nella root, con branch principale `main` e remote `origin`:
[mefistofelix/graal-php](https://github.com/mefistofelix/graal-php), **pubblico**
per richiesta esplicita dell'utente. URL Git:
`https://github.com/mefistofelix/graal-php.git`. Sorgenti, documenti ed evidenze
sono inclusi; `build/`, `tools/` e `.xmake/` restano esclusi. Ricontrollare
`git status`, `git remote -v` e l'allineamento con `origin/main` quando si riprende.
Il primo import normalizza soltanto a LF i vecchi report CRLF, senza cambiare
valori misurati o hash registrati, né configurazioni Git globali.

## Comandi utili

Da root, Windows PowerShell; ogni comando va scelto per lo scopo, non eseguito
come checklist obbligatoria a ogni modifica:

```powershell
./build.bat                         # compila JAR, nessuna suite
./build.bat curl-test               # suite cURL JVM
./build.bat curl-test native        # ricostruisce Native Image e verifica cURL
./build.bat curl-test trueasync     # verifica sull'oracolo
./build.bat verify                  # integrazione/valori/differenziale TrueAsync
./build.bat autoload-test           # 38 programmi contro PHP/TrueAsync
./build.bat autoload-test native    # ricostruisce e verifica il prodotto
./build.bat class-test              # 93 contratti classi/output/rifiuti
./build.bat class-test native       # stessa suite sulla CLI ricostruita
./build.bat enum-test               # 90 programmi enum/match/clone
./build.bat string-test             # 27 programmi offset di stringa
./build.bat strict-test             # 38 programmi strict_types
./build.bat enum-test native --interpreter # stesso prodotto, JIT guest disabilitato
./build.bat ffi-fatal-test          # stack C drenati anche dopo un fatal
./build.bat ffi-bridge-test native  # callback e stack C; quando pertinenti
```

Le performance sono rinviate. Quando l'utente le riprenderà, per ripetere
soltanto le misure cURL dopo aver costruito e verificato i binari:

```powershell
& tools/graalvm-25.4.4.1.1+1.1/bin/javac.exe --release 25 -proc:none -d build/test-classes tests/graalphp/NetworkBenchmark.java tests/graalphp/CurlBenchmark.java
$env:GRAALPHP_BENCH_BASELINE='build/graalphp-before-memory.exe'
$env:GRAALPHP_BENCH_WITH_TRUEASYNC='1'
$env:GRAALPHP_BENCH_LATENCY_EVERY='16'
$env:GRAALPHP_BENCH_LATENCY_CONTROL='1'
& tools/graalvm-25.4.4.1.1+1.1/bin/java.exe --enable-native-access=ALL-UNNAMED -cp build/test-classes graalphp.CurlBenchmark 3 2048 64 0
```

Verificare prima hash e variabili `GRAALPHP_BENCH_*` della shell. In particolare
`ONLY`, `EXECUTABLE`, `VM_OPTIONS`, `SCRIPT`, `JVM_CLASSES`, `INTERPRETER`,
`PROFILE` e `TRACE_COMPILATION` cambiano il confronto. I pin e i comandi effettivi
sono registrati in `environment.txt`; i risultati sono sotto un nuovo
`build/curl-benchmark-*`. Archiviare dati e log in `docs/` prima di concludere.

Su Linux: `bash build.sh curl-test native` e `bash build.sh curl-test`.
L'ultima build è stata fatta nella copia `build/linux-workspace/` con toolchain
SDKMAN locale; aggiornare la copia dai sorgenti autorevoli prima di riusarla,
controllare la parità e copiare il prodotto in `build/graalphp-linux-x64`.
Non sviluppare solo nella copia. Per il watcher usare una root PHP sul filesystem
Linux nativo, non `/mnt/c`. Nessuna build Native Image Windows/Linux in parallelo.

Per Python usare `uv` (preferenza del progetto). I build script preparano i tool
portabili; non aggiungere un secondo setup né installare toolchain globali.

## Mappa del codice e contratti

| Area | Entrata nel codice | Contratto / evidenze |
| --- | --- | --- |
| Parsing, IR, lowering | [Parser](../src/graalphp/frontend/Parser.java), [PhpCompiler](../src/graalphp/truffle/PhpCompiler.java), [PhpRoot](../src/graalphp/truffle/PhpRoot.java) | [copertura](design-coverage.md) |
| Valori e lifetime | [PhpValues](../src/graalphp/runtime/PhpValues.java), [Execution](../src/graalphp/runtime/Execution.java), [Operations](../src/graalphp/runtime/Operations.java) | design §§5–8, 40; [test valori](../tests/graalphp/lab/ValueModelTest.java) |
| Scheduler e TrueAsync | [Scheduler](../src/graalphp/runtime/Scheduler.java), [AsyncApi](../src/graalphp/runtime/AsyncApi.java), [AsyncBuiltins](../src/graalphp/runtime/AsyncBuiltins.java) | [TrueAsync](trueasync-compatibility.md), [allineamento](trueasync-alignment.md) |
| Reactor e host | [LibuvReactor](../src/graalphp/runtime/LibuvReactor.java), [reactor.c](../src/native/reactor.c), [EmbeddedPhp](../src/graalphp/EmbeddedPhp.java) | [reactor](reactor.md), [patch](../src/native/patches/README.md) |
| cURL | [CurlApi](../src/graalphp/runtime/CurlApi.java), [CurlMultiApi](../src/graalphp/runtime/CurlMultiApi.java), [curl_reactor.c](../src/native/curl_reactor.c) | [provider](curl-providers.md), [costi runtime](curl-runtime-costs.md), [RAM corrente](curl-memory.md) |
| FFI, stack e pool | [FfiApi](../src/graalphp/runtime/FfiApi.java), [NativeInvocation](../src/graalphp/runtime/NativeInvocation.java), [ffi_bridge.c](../src/native/ffi_bridge.c), [WorkerPool](../src/graalphp/runtime/WorkerPool.java) | [threading/FFI](threading-ffi.md), [stack C](native-stack-bridge.md) |
| Codice dinamico/reload | [CodeRepository](../src/graalphp/runtime/CodeRepository.java), [PhpContext](../src/graalphp/truffle/PhpContext.java) | design §§9–10, 56; [integrazione](../tests/graalphp/IntegrationTest.java) |
| Build nativa | [xmake.lua](../xmake.lua), [build.bat](../build.bat), [build.sh](../build.sh) | [native-build](native-build.md), [distribuzioni](static-distributions.md) |

## Decisioni e limiti facili da perdere

- `spawn_thread` pubblico con grafi condivisi **non è ancora implementato**;
  worker reali/capsule condivisibili non equivalgono a heap PHP condiviso completo.
- Risultati dei future trattenuti fino a fine richiesta, distruttori/weakrefs e
  resurrection incompleti. Non descrivere l'attuale collector come piena
  semantica PHP; PHP ha anche raccolta dei cicli oltre al reference counting.
- Callback FFI sospendibili limitate alla durata della chiamata e al suo thread
  C; persistenti/esterne aperte. Il percorso legacy ha un contratto diverso.
- Pool di richiesta; max=1 può creare deadlock se una callback aspetta un altro
  lavoro nello stesso pool saturo. Nessuna interruzione forzata universale del C.
- Il vecchio collo di bottiglia non va attribuito automaticamente a un thread
  libuv: nel server CLI le callback sono già sul thread PHP. L'helper host
  attende readiness, non esegue il reactor o PHP.
- `CompilerIdleDelay=500` è default **della CLI**; `EmbeddedPhp` conserva le opzioni
  del builder host. Override: `-Dpolyglot.engine.CompilerIdleDelay=10000`.
- `-Xmn16m -XX:MaxHeapFree=1m` era una prova diagnostica con warmup e maggiore
  costo CPU, non un default adottato. NMT non è stato ancora usato per attribuire
  tutta la memoria. Non ripresentare quelle prove come scenario reale corrente.
- `CodeRepository` compila tutta la root anche senza watch; file non supportati
  possono bloccare l'indice. Impatto RAM non misurato; nessuna modifica lazy fatta.
- I report `curl-latency-memory.md`, `curl-runtime-costs.md` e precedenti sono
  utili per l'analisi storica ma hanno metodo/build diversi: non mescolare i numeri.

## Riferimenti esterni da conservare

Usare le versioni fissate e le prove locali; verificare upstream prima di un
aggiornamento. Questi link documentano il contesto, non richiedono una migrazione:

- [php-xmake](https://github.com/mefistofelix/php-xmake): base delle ricette; commit
  `f6526d592bdcedf8b43fb2b5f2d834df058ef713` e, per curl-impersonate,
  `8c00ce25562f6dcdbffa42a1c335e3d71dc5d58b`. Il checkout di riferimento può essere
  `../php-xmake`; leggere AGENTS/README/TODO/XMAKE_README/unitybuild quando si
  modificano quelle ricette. I risultati della lettura sono in `native-build.md`.
- [TrueAsync 0.10.0](https://github.com/true-async/releases/releases/tag/v0.10.0),
  [sorgenti fissati](https://github.com/true-async/php-async/tree/6acdd07ff500f5799ea83bbf333d686b5dacabbf),
  [confronto dei reactor](trueasync-reactor-comparison.md).
- [SDKMAN](https://sdkman.io/), `sdk install java 25.4.4+1-graal`; Windows usa
  l'archivio GraalVM con versione e checksum nei build script. Il
  [post GraalVM 25.3](https://medium.com/graalvm/graalvm-25-3-is-here-41641acebfaf)
  citato inizialmente è superato dalla toolchain già fissata qui.
- [Slint e integrazione dei loop](https://slint.dev/blog/slint-and-the-nodejs-event-loop):
  motivazione della patch Electron e del wakeup senza polling periodico.
- [SoLo](https://github.com/pg83/solo): candidato futuro, con limiti e commit
  documentati in `static-distributions.md`; non incorporato nel prodotto.
- [Opzioni Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/Options/)
  e [memoria Native Image](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/):
  riferimenti della modifica RAM, da confrontare con la versione effettiva.
- [Proposta Compose](../compose_multiplatform_graal_native_proposal.md): target
  successivo alla base runtime, non parte già completata.

## Manutenzione della consegna

A ogni risultato significativo aggiornare data, stato e punto di ripresa,
TODO, contratti cambiati e link alle evidenze. Conservare i report storici.
Non aggiornare retroattivamente gli hash di una vecchia verifica: una nuova
campagna crea un nuovo manifest. Per modifiche solo documentali controllare
link, coerenza, inclusione in `.gitignore`, UTF-8/LF; non rigenerare il prodotto.
