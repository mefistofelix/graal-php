# Stato di consegna GraalPHP

Aggiornato: **26 settembre 2026**. Punto d'ingresso: [AGENTS.md](../AGENTS.md).
Questo documento permette di riprendere senza la chat; [TODO.md](../TODO.md)
contiene il lavoro aperto. Gli hash e i risultati sono quelli dell'ultima
campagna indicata, non una garanzia che ogni futuro checkout vi corrisponda.

## Dove siamo

Runtime sperimentale PHP 8.6 direttamente su Truffle Bytecode DSL, con JVM e
Native Image Windows/Linux x64 funzionanti. Non ancora un sostituto generale
di PHP; non sono completate tutte le milestone del design.

L'ultimo intervento runtime è **concluso**: default CLI
`engine.CompilerIdleDelay=500`, rispetto ai 10.000 ms precedenti, con JIT attivo.
L'utente ha poi richiesto che **nessun benchmark escluda il warmup**: i driver
sono stati aggiornati e il confronto cURL è stato rifatto da processi nuovi.
La consegna documentale ha aggiunto AGENTS/TODO/handoff. Il 26 settembre è
stato inizializzato Git e creato il repository GitHub pubblico; queste attività
non modificano runtime o eseguibili. Le verifiche runtime sotto restano quelle
del 25 settembre. Non ci sono lavori parziali da far passare per completati.

La prossima indagine proposta è `PERF-01`: capire l'aumento del p99 senza
perdere il risparmio RAM. Per le funzionalità, restano prioritari i contratti
linguaggio/valori, TrueAsync, shared-memory threading e FFI; Composer applicativo,
Compose e mobile sono rinviati. Procedere nel lavoro già autorizzato senza
riconfermare queste decisioni; chiarire soltanto ambiguità che cambiano obiettivo
o vincoli, usando giudizio sulle normali scelte implementative.

## Ultima verifica e metodo

[Rapporto corrente](curl-memory.md), [esiti](validation/curl-memory-2026-09-25/results.txt),
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
| `build/graalphp.exe` | 90.210.304 | `911359acdf1069bdd382f1749da3ad33000a2dfbc7c2ca7cf57d54b47e95a712` |
| `build/graalphp-linux-x64` | 92.932.904 | `efa0d5740382d7fcfa90d4c89a8bd271119d9a18f25f9cb5743c73ca53f4de9f` |
| `build/graalphp-before-memory.exe` | 90.161.152 | `f84539cde1b2976077f4b29c68d3737f1ab096add2e8662f78d0a5d328c36c53` |

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
./build.bat ffi-bridge-test native  # callback e stack C; quando pertinenti
```

Per ripetere soltanto le misure cURL dopo aver costruito e verificato i binari:

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
