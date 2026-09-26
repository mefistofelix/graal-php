# Attività aperte GraalPHP

Aggiornato: 26 settembre 2026. Leggere prima [AGENTS.md](AGENTS.md) e
[handoff](docs/handoff.md). Questa lista è operativa; la copertura completa
rimane in [design-coverage.md](docs/design-coverage.md). Le caselle completate
certificano soltanto il risultato descritto, non un'intera milestone del design.

## Punto di ripresa

**L'utente ha rinviato le verifiche prestazionali.** Riprendere lo sviluppo
funzionale, non PERF-01. Il blocco corrente di **LANG-01** implementa
[argument unpacking e array spread sospendibili](docs/unpacking.md), sopra i
precedenti contratti di iterazione, classi e valori. Il blocco corrente chiude **Reflection e semantica runtime degli attributi** entro i limiti documentati; il seguito immediato è **generatori/Fiber**, poi lifetime. Il loader SPL predefinito, include_path, binding completo fra unità
e il typing delle restanti API native rimangono aperti.
Linguaggio/TrueAsync/threading/FFI precedono Composer applicativo, Compose e mobile.

## Concluso e da non rifare da zero

- [x] Backend di esecuzione Truffle Bytecode DSL, JVM e Native Image Windows/Linux.
- [x] Reactor PHP/libuv sullo stesso thread e integrazione host event-driven con
  patch Electron; cURL multi/socket senza un worker per trasferimento.
- [x] Provider cURL standard parallelo a curl-impersonate, senza alterare la
  versione curl prevista dal secondo.
- [x] Bridge `FFI::cdef` con callback sospendibili e stack C separati, entro i
  limiti documentati; pool async nominati con min/max/coda.
- [x] Diagnostica e ottimizzazioni dei costi runtime cURL, collector, dispatch e
  copie del body: [rapporto](docs/curl-runtime-costs.md).
- [x] Default CLI `CompilerIdleDelay=500`, campagna cURL da freddo con RAM,
  throughput/CPU e p20/p50/p90/p95/p99; niente warmup scartato:
  [risultati del 25 settembre](docs/curl-memory.md).
- [x] Istruzioni, priorità e stato persistente per riprendere da un'altra sessione.
- [x] Git locale su `main` e repository GitHub pubblico
  [mefistofelix/graal-php](https://github.com/mefistofelix/graal-php).

- [x] Tooling PERF-01: opzioni A/B separate, timeline e clock bounds, confine
  wall-time corretto, CPU temporale e report ricalcolabile; 26 controlli Java,
  7 test Python e 44/44 prove A/B/diagnostiche. Nessun fix runtime implicito.

- [x] Nucleo autoload personalizzato: registro SPL per richiesta, callback con
  ownership, sospensioni/cancellazione, costruzione dinamica e dichiarazioni
  condizionali; 38 programmi differenziali e test di isolamento/reload.
  Non comprende tutta la SPL né chiude LANG-01.

- [x] Interfacce/trait, contratti astratti/finali, varianza, costanti e query
  di classe: 93 programmi differenziali/di rifiuto semantico, reload fra
  generazioni e fatal di dichiarazione. [Contratto](docs/class-contracts.md).
  Drenaggio di callback C fatali separato dal cleanup guest; default statici dei
  trait risolti sulla classe utilizzatrice e completamenti host pronti su post,
  non allarmi a zero. LANG-01 resta aperto.

- [x] Enum senza backing e int/string, singleton per richiesta, match/throw
  expression e clone sospendibile; offset di stringa byte-oriented e chiavi
  binarie; strict_types per sorgente con propagazione attraverso trait,
  closure/include/eval. Corpus di 155 programmi, di cui 29 rifiuti semantici.
  [Contratti e limiti](docs/language-values.md); non chiude LANG-01 o VALUE-01.

- [x] Foreach sospendibile su Iterator/IteratorAggregate/oggetti, Countable e
  builtin iterator/count/object-vars; metadati degli attributi e tipi di ritorno
  provvisori con ReturnTypeWillChange. 74 programmi in sei modalità, 56 controlli
  diretti dei campi e 52 dei metadati. [Contratto](docs/iteration.md).

- [x] Argument unpacking e array spread da array/Traversable, compresi named
  arguments, duplicati/ordine delle chiavi, reference, strict_types, autoload,
  costanti di classe e iteratori sospendibili. 46 programmi differenziali,
  con percorsi separati per chiamate e merge array. [Contratto](docs/unpacking.md).

- [x] ReflectionClass/Object/Function/Method/Property/ClassConstant/Parameter/Attribute,
  filtri attributi lazy e `newInstance()` sospendibile con target/repeatability.
  25 programmi in sei modalità Windows/Linux JVM/native/interpreter.
  [Contratto](docs/reflection.md).

## Prestazioni e memoria: rinviate per richiesta dell'utente

- [ ] **PERF-01 — Spiegare e ridurre il costo del p99.** L'A/B sullo stesso
  eseguibile del 26 settembre conferma RSS p50 −47,18%/−41,86%, con p99
  −2,41%/+10,42% per 1.000/10.000 sospese; quattro ripetizioni con intervalli
  sovrapposti. Le 12 prove diagnostiche mostrano GC e invalidazioni, oltre a
  pause della JVM del peer. [Evidenze e limiti](docs/curl-p99.md).
  Ancora da isolare allocazioni/GC e riprese guest senza gli array aggiuntivi
  della timeline, controllare il peer e misurare creazione/distruzione dei
  compiler worker. TraceCompilationDetails non misura quel lifetime; NMT non
  eseguito. Completamento: causa e modifica verificate da freddo con RAM,
  latenza, CPU e compromessi insieme; non disabilitare il JIT.
- [ ] **PERF-02 — Coprire durate e carichi reali diversi.** Aggiungere scenari
  brevi, lunghi e intermittenti interamente misurati, senza warmup nascosto;
  distinguere startup, prime richieste, lavoro e shutdown. Oggi il confronto
  principale è HTTP locale, 64 client, 131.072 richieste, 1.000/10.000 sospese.
  Linux non ha ancora il campionatore esterno equivalente. Non attribuire al
  cronometro le attese TrueAsync pre-payload finché la causa non è isolata.
- [ ] **PERF-03 — Valutare il caricamento iniziale del codice.** `CodeRepository`
  precompila tutta la root anche senza watch. Impatto RAM/startup non quantificato;
  non è stato modificato per la riduzione RAM. Un eventuale caricamento differito
  deve preservare include dinamici, autoload e snapshot della generazione durante
  reload, senza scan/stat nel normale percorso di richiesta.

## Fondamenta prioritarie

- [ ] **VALUE-01 — Lifetime PHP completo.** Distruttori, weak reference,
  resurrection, errori/shutdown e rilascio dei receiver temporanei; return-by-ref,
  proprietà tipizzate e stringhe binarie nelle restanti coercizioni. Chiavi
  binarie e offset byte-oriented sono ora coperti dal blocco valori. Verificare
  ordine osservabile contro PHP 8.6 e invarianti dei cicli anche nelle sospensioni.
  Un miglior RSS della VM non chiude queste semantiche.
- [ ] **ASYNC-01 — Lifetime dei future e policy degli scope.** I risultati dei
  future sono ancora trattenuti fino a fine richiesta. Completare ultimo
  riferimento guest, recvAsync perdenti, errori non osservati/disposal, scope
  annidati e staccati, poi API mancanti quali Traversable, TaskGroup/TaskSet e
  diagnostica. Criterio: stesso programma contro la release TrueAsync fissata,
  nessun adattamento del test per dichiarare una compatibilità inesistente.
- [ ] **THREAD-01 — Grafi LOCAL→SHARED e spawn pubblico.** Promuovere array,
  oggetti, closure e reference nello stesso heap; integrare visibilità, ownership
  e collector mantenendo veloce il caso locale. Poi esporre API vicina a TrueAsync.
  Criterio: identità condivisa e COW corretti, test con thread reali e
  sincronizzazione esplicita, race che non corrompono il runtime; nessuna copia
  in un'altra istanza PHP. Oggi mutex/ThreadChannel accettano scalari e capsule.
- [ ] **THREAD-02 — Primitive e contesti condivisi.** Estendere ThreadChannel ai
  grafi promossi; valutare semaphore/RWLock/condition e sintassi synchronized
  solo se utili. Completare ProcessContext, stato di processo e policy delle
  static properties; `FFI::definePool` oggi ha lifetime di richiesta.
  Dipende dalla barriera e dal lifetime, non soltanto dall'API dei lock.
- [ ] **LANG-01 — Linguaggio e autoload.** Il nucleo con callback esplicite è
  implementato: [autoload](docs/autoload.md). Completare loader SPL predefinito,
  estensioni/include_path, callback builtin e diagnostica, binding tra unità e
  regole complete di ereditarietà. Il nucleo interfacce/trait, classi astratte,
  finalità, costanti e varianza è implementato in questo blocco, non tutto il
  modello classi PHP. Enum, match/clone e strict_types delle chiamate utente
  sono implementati nel blocco valori. Iteratori utente, Countable e metadati
  degli attributi sono nel blocco iterazione; argument/array unpacking è nel
  blocco successivo. Restano generatori/Fiber, iteratori SPL concreti/ArrayAccess, ReflectionType/Enum avanzata, named arguments e typing nei restanti builtin,
  readonly/hooks e diagnostica completa.
  Pianificare blocchi coerenti con valori, sospensioni e reload; le feature
  usate da Composer sono prioritarie, eseguire Composer non lo è.
- [ ] **FFI-01 — Modello C e callback generiche.** CData, puntatori/buffer con
  lifetime, strutture/union, variadiche e zero-copy; registrazione/unregistrazione
  persistente delle callback e callback da thread C esterni nel nuovo bridge.
  Criterio: ABI, radici guest, affinità, GC, errore, cancellazione e drain
  verificati su Windows/Linux, JVM/Native Image; nessun frame managed su stack C.
- [ ] **NATIVE-01 — Completare API delle librerie e I/O.** Callback PHP cURL,
  riconfigurazione easy associati, socket/DNS/streams, cURL/SQLite3, preg/PDO e
  catalogo builtin. Verificare interazione con reactor e cancellazione generica C.
  HTTP/2, body compressi e TLS restano distinti dal benchmark HTTP corrente.
- [ ] **RELOAD-01 — Rafforzare generazioni e isolamento.** Indice dipendenze/simboli,
  rename/delete/overflow, include fuori root, errori PHP esatti, request context
  e superglobali. Verificare richieste vive durante pubblicazione atomica e
  directory nuove; il watcher su `/mnt/c` non è una piattaforma validata.

## Distribuzione e lavoro successivo

- [ ] **DIST-01 — Packaging e riproducibilità.** Mantenere eseguibili/JAR coerenti,
  dipendenze documentate e verifica da setup pulito. Misurare profilo compatto
  senza promuovere a default le opzioni GC della sola diagnostica storica.
- [ ] **DIST-02 — Linux full-static e FFI.** Valutare il profilo musl senza load
  dinamico e quello libc con load dinamico. SoLo è un candidato, non una
  dipendenza implementata: occorre dimostrare NFI, callback, TLS nativo,
  allocator, thread e shutdown. [Vincoli](docs/static-distributions.md).
- [ ] **UI-01 — Compose, macOS e mobile, per ultimi.** Conservare il contratto
  `EmbeddedPhp.Driver`; Compose/Skia/frame clock/windowing e Android/iOS sono
  ancora da implementare. La proposta UI non prova la fattibilità dei target.

## Come aggiornare questa lista

Conservare gli ID quando cambia l'implementazione. Spuntare solo ciò che ha
un risultato e un'evidenza collegabile. Per attività parziali annotare il
risultato raggiunto e lasciare aperto ciò che manca. A ogni consegna aggiornare
il punto di ripresa e [handoff](docs/handoff.md), senza copiare tutti i rapporti
storici qui e senza trasformare ipotesi di ottimizzazione in decisioni approvate.
