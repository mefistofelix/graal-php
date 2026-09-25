# GraalPHP / TrueAsync su GraalVM Truffle
## Requisiti, architettura e scelte implementative — bozza di progetto v0.2

**Data:** 14 settembre 2026  
**Stato:** documento di design iniziale / requisiti architetturali  
**Obiettivo:** definire il runtime PHP che vogliamo costruire sopra GraalVM/Truffle, preservando le semantiche peculiari di PHP, integrando un modello TrueAsync, FFI dinamiche, hot reload e distribuzione come runtime nativo standalone.

---

## 1. Visione del progetto

L'obiettivo non è “eseguire PHP da Java”, né integrare un interprete PHP esterno.

L'obiettivo è **implementare PHP come vero guest language Truffle**, in modo che PHP diventi un linguaggio di prima classe sopra GraalVM:

- parser e frontend PHP propri;
- semantica PHP implementata nel runtime Truffle;
- specializzazione e JIT del guest code tramite Graal/Truffle;
- supporto alla deottimizzazione e ricompilazione quando decadono assunzioni speculative;
- interoperabilità Polyglot;
- FFI native;
- modello asincrono/cooperativo ispirato e compatibile, dove possibile, con TrueAsync;
- vero parallelismo tramite thread;
- hot reload dei sorgenti per workload server;
- distribuzione finale come **runtime PHP nativo standalone**.

Il prodotto finale deve comportarsi, dal punto di vista dell'utente, come un normale runtime PHP:

```text
php my-script.php
```

oppure come processo server long-running, senza richiedere all'utente:

- JDK;
- GraalVM SDK;
- compiler Java;
- `native-image`;
- toolchain C;
- ricompilazione dell'applicazione PHP.

Il codice PHP rimane **dinamico a runtime**.

---

# 2. Requisiti fondamentali

## 2.1 Compatibilità PHP

**Versione target: PHP 8.6** (decisione del 24 settembre 2026). I test differenziali
devono usare una build PHP 8.6 identificata e riproducibile; durante il ciclo di
sviluppo sono ammesse le prerelease ufficiali. La versione dell'oracolo va distinta
dalla copertura effettivamente implementata dal runtime.

Il runtime deve implementare le semantiche PHP, non una variante “PHP-like”.

Le aree che richiedono particolare attenzione includono:

- scalari e coercizioni;
- stringhe;
- array PHP ordinati con chiavi `int|string`;
- copy-on-write;
- reference PHP (`&`);
- l-value e scritture indirette;
- reference a elementi di array e proprietà;
- reference annidate;
- `foreach` normale e by-reference;
- funzioni e return-by-reference;
- oggetti e proprietà dinamiche dove previste dalla versione target;
- static properties;
- closures;
- generators;
- fibers, se richiesti dalla versione PHP target;
- autoload;
- include / require;
- include dinamici;
- `include_once` / `require_once`;
- reflection;
- `eval`;
- variable variables;
- callable dinamici;
- errori, warning, exception e shutdown;
- destructors;
- weak references / weak maps;
- output buffering;
- stream abstraction;
- superglobali;
- semantica per-request.

**Principio:** quando un'ottimizzazione entra in conflitto con la semantica PHP, deve vincere la semantica PHP.

---

# 3. Scelta del framework: Truffle

## 3.1 `TruffleLanguage`

PHP viene implementato come una sottoclasse di `TruffleLanguage`.

Il runtime avrà almeno:

```text
PHPTruffleLanguage
PHPContext
PHP parser/frontend
PHP bytecode/AST representation
PHP value model
PHP symbol registry
PHP scheduler/reactor
PHP FFI layer
PHP standard library layer
```

Truffle fornisce l'infrastruttura per:

- parsing e `CallTarget`;
- specializzazione;
- profiling;
- compilation del guest code;
- invalidazione;
- deoptimization;
- recompilation;
- source information;
- instrumentation;
- polyglot interop;
- multi-threaded contexts, se il linguaggio lo abilita esplicitamente.

---

# 4. AST classico vs Truffle Bytecode DSL

## 4.1 Scelta proposta

Per questo progetto la base consigliata è:

> **parser PHP → IR/frontend → Truffle Bytecode DSL**

piuttosto che modellare l'intero PHP esclusivamente come un enorme albero AST manuale.

Motivi:

1. la Bytecode DSL è pensata per interpreti complessi;
2. ha tier uncached/cached;
3. supporta quickening e specializzazioni;
4. consente boxing elimination;
5. integra il modello di runtime compilation di Truffle;
6. soprattutto, supporta direttamente **continuation/yield**, estremamente utile per TrueAsync;
7. può ridurre il footprint quando esistono molti file/funzioni PHP caricati ma non tutti hot.

Non è obbligatorio che il parser produca direttamente bytecode. È preferibile avere una piccola IR semantica intermedia per separare:

- grammatica;
- semantic lowering;
- bytecode generation.

---

# 5. Modello dei valori PHP

Questa è una delle parti centrali del progetto.

## 5.1 Evitare il “boxed object per tutto”

Il runtime non deve trasformare ogni `int`, `double`, `bool` in un oggetto heap PHP generico.

I nodi/bytecode Truffle devono poter specializzarsi su:

- `long`;
- `double`;
- `boolean`;
- `null`;
- string representation;
- object;
- array;
- reference cell.

Il JIT deve poter propagare e tenere valori primitivi non boxed quando il codice lo permette.

---

# 6. Array PHP e Copy-On-Write

## 6.1 Semantica richiesta

Esempio:

```php
$a = [1, 2, 3];
$b = $a;
$b[0] = 99;
```

L'assegnazione non deve copiare immediatamente l'intero array.

`$a` e `$b` condividono inizialmente lo storage; la copia reale avviene alla prima mutazione che deve separare i due valori.

## 6.2 Modello proposto

```text
PHPArray
   └── ArrayStorage
       ├── strategy
       ├── entries
       ├── COW state
       └── sharing state
```

Possibili strategie specializzate:

```text
PackedIntArrayStorage
PackedArrayStorage
OrderedHashArrayStorage
GenericArrayStorage
SharedArrayStorage
```

Transizioni di esempio:

```text
packed<int>
    ↓ inserimento string key
ordered-hash
    ↓ reference / casi complessi
generic
```

La transizione non deve necessariamente rendere lento tutto il runtime: Truffle può specializzare il codice sui casi osservati.

## 6.3 COW e concorrenza

Lo stato deve distinguere almeno:

```text
LOCAL
SHARED
```

Finché lo storage è thread-confined:

- contatori/stato possono essere non atomici;
- niente lock;
- niente volatile/atomic operation sul fast path.

Se lo storage attraversa realmente un confine di thread:

```text
LOCAL → SHARED
```

la transizione viene effettuata una volta e da quel momento vengono applicate le regole concorrenti necessarie a preservare l'integrità interna del runtime.

L'utente può comunque creare race logiche sui suoi dati. Il runtime deve impedire **corruzione della VM**, non trasformare automaticamente ogni operazione PHP in un'operazione thread-safe.

---

# 7. Reference PHP

Le reference PHP non devono essere implementate come “un path da rinavigare ogni volta”.

Esempio concettuale:

```php
$r =& $a['x']['y'];
$r = 10;
```

Il modello deve separare:

```text
VALUE
LOCATION / SLOT
REFERENCE CELL
```

## 7.1 Modello

Un l-value produce una location:

```text
LocalSlot
ArrayElementSlot
ObjectPropertySlot
StaticPropertySlot
...
```

Quando viene usato `&`, il runtime crea o recupera una `PHPReferenceCell`.

Gli alias successivi puntano alla stessa cella.

```text
slot A ─┐
        ├── PHPReferenceCell ── value
slot B ─┘
```

In questo modo una reference profonda non deve percorrere ogni volta:

```text
array → array → array → key
```

Il lookup profondo serve per **risolvere l'l-value**; una volta stabilita la reference, gli alias condividono la cella corretta.

## 7.2 Interazione con COW

Questa è una zona da testare in modo estremamente rigoroso.

La copia di un array contenente reference deve rispettare esattamente la semantica PHP:

- lo storage strutturale può essere COW;
- una reference interna può continuare a rappresentare una cella condivisa;
- una modifica strutturale dell'array può richiedere detach;
- una modifica della cella referenziata non è equivalente a una normale mutazione del container.

Queste regole vanno definite prima di ottimizzare il runtime.

---

# 8. L-value come concetto di prima classe

Conviene che il frontend distingua esplicitamente:

```text
read value
write location
bind reference
unset location
isset location
```

e non implementi tutte queste operazioni come varianti ad hoc di “leggi e poi scrivi”.

Questo rende più chiari:

- assignment;
- compound assignment;
- increment/decrement;
- reference;
- nested reference;
- `isset`;
- `empty`;
- `unset`;
- write su array annidato;
- autovivification;
- property hooks eventualmente previsti dalla versione target.

---

# 9. Hot reload del codice PHP

## 9.1 Requisito

In modalità server long-running:

1. il processo rimane attivo;
2. un file PHP cambia su disco;
3. la richiesta successiva deve poter usare il codice nuovo;
4. non si riavvia il runtime;
5. non si ricompila il runtime nativo.

## 9.2 Non basta una cache per file

PHP consente:

- `include`;
- `require`;
- include condizionali;
- include con path calcolati;
- definizioni globali;
- funzioni;
- classi;
- traits;
- constants;
- autoload.

Perciò il sistema deve separare:

### Source cache

```text
path + content hash
      ↓
parsed source / bytecode roots
```

### Semantic registry

```text
functions
classes
traits
constants
autoload state
```

### Dependency information

```text
file A includes B
class C defined by A
function F defined by B
autoload resolved X to C
```

## 9.3 Versioni e invalidazione

Il codice già compilato può assumere, per esempio:

```text
"la funzione foo risolve al CallTarget X"
```

Questa assunzione deve essere invalidabile.

Per simboli ridefinibili/hot-reload si può usare il modello:

```text
CyclicAssumption / versioned symbol
```

Quando il simbolo cambia:

```text
invalidate old assumption
→ compiled code dipendente viene deottimizzato
→ lookup viene rifatto
→ nuova compilazione quando torna hot
```

È preferibile una granularità per simbolo o per piccolo gruppo di simboli, non invalidare indiscriminatamente l'intero runtime.

---

# 10. Code generations per richiesta

Per un server è utile introdurre:

```text
CodeGeneration / CodeEpoch
```

Una richiesta parte con una generazione coerente.

Se durante la richiesta cambiano dei file:

- la richiesta in corso può terminare con la generazione precedente;
- le nuove richieste usano la generazione nuova.

Questo evita di avere una singola request che esegue metà applicazione vecchia e metà nuova.

## 10.1 Principio fondamentale: nessuno scan a richiesta

Il runtime **non deve controllare mtimes, hash o tutto l'albero degli include all'arrivo di ogni request**.

Il modello desiderato è l'opposto:

```text
filesystem event
    ↓
watcher
    ↓
coalescing / debounce
    ↓
reload pipeline
    ↓
parse/compile dei soli source unit coinvolti
    ↓
invalidazione dei soli simboli/cache/assumption dipendenti
    ↓
publish atomico della nuova CodeGeneration
    ↓
la request successiva parte già sul codice nuovo
```

La request normale deve quindi restare sul fast path e non pagare il costo del filesystem watching.

## 10.2 Watcher ricorsivo ed event-driven

Il watcher deve osservare in modo ricorsivo le source root note al runtime.

Deve gestire almeno:

- modifica;
- creazione;
- cancellazione;
- rename/move;
- sostituzione atomica di file tipica degli editor;
- creazione di nuove directory;
- rimozione di directory;
- overflow/perdita eventi del backend;
- symlink secondo una policy esplicita;
- canonicalizzazione/case-sensitivity specifica della piattaforma.

Non conviene registrare logicamente “un watcher per file”.

L'unità pratica è la directory/source root, con una mappa interna:

```text
canonical path → SourceUnit / dependency metadata
```

Quando a runtime viene risolto un include fuori dalle root già osservate, il sistema può registrare dinamicamente anche la nuova directory/root secondo la policy configurata.

## 10.3 Nuovi file

Il watcher deve vedere anche file che prima non esistevano.

Caso tipico:

```text
1. viene creato NewService.php
2. viene modificato bootstrap.php
3. bootstrap.php inizia a includere NewService.php
```

Il sistema deve poter ricevere entrambi gli eventi e costruire una nuova generazione coerente senza aspettare che una request faccia una scansione del disco.

Per questo il watching deve essere per directory/root e ricorsivo, non limitato ai soli pathname dei file già inclusi.

## 10.4 Debounce e coalescing

Un singolo “Save” di un editor può generare sequenze come:

```text
CREATE temp
WRITE temp
DELETE old
RENAME temp → file.php
MODIFY metadata
```

Questi eventi non devono provocare cinque reload.

Serve un piccolo `SourceChangeCoalescer`:

```text
raw FS events
    ↓
normalize path
    ↓
coalesce by path / directory
    ↓
short debounce window
    ↓
SourceChangeBatch
```

La finestra deve essere configurabile e abbastanza piccola da non rendere percepibile il reload in sviluppo.

Il debounce appartiene al watcher/reload service, non al request path.

## 10.5 Rename

Il backend può esporre il rename:

- come evento nativo;
- oppure come `DELETE old + CREATE new`.

La semantica interna del runtime non deve dipendere da questa differenza.

Il normalizzatore può produrre:

```text
CREATED
MODIFIED
DELETED
MOVED
```

quando il move è ricostruibile con sufficiente affidabilità, oppure mantenere `DELETE + CREATE` se non serve conoscere l'identità del file.

Per il language runtime ciò che conta è aggiornare correttamente:

- `SourceUnit`;
- dependency graph;
- symbol provenance;
- include resolution cache.

## 10.6 Pipeline di reload

Per ogni batch stabilizzato:

```text
SourceChangeBatch
    ↓
resolve affected SourceUnits
    ↓
read changed content
    ↓
parse
    ↓
semantic lowering / bytecode generation
    ↓
build candidate updates
    ↓
update dependency metadata
    ↓
invalidate affected Assumptions
    ↓
publish new CodeGeneration atomically
```

Se il parse/compile di un file modificato fallisce:

- non si deve pubblicare una generazione internamente incoerente;
- si conserva la generazione precedente come corrente;
- si registra il nuovo errore di source;
- la policy può decidere se la request successiva deve vedere il vecchio codice oppure fallire quando prova ad usare proprio il source diventato invalido.

Questa policy deve essere configurabile fra sviluppo e produzione.

## 10.7 Aggiornamento selettivo

Un cambiamento a `foo.php` non deve invalidare indiscriminatamente tutto il runtime.

Il sistema mantiene metadati del tipo:

```text
SourceUnit
    ├── definitions
    ├── referenced symbols
    ├── include edges
    ├── autoload edges
    └── cached callsites/resolvers
```

Il reload aggiorna o invalida solo:

- il `SourceUnit` cambiato;
- i symbol resolver che dipendono da esso;
- le `Assumption` relative ai simboli cambiati;
- gli include/autoload cache coinvolti;
- le eventuali inline cache che dipendono dalla vecchia versione.

Il resto del codice rimane valido e compilato.

## 10.8 Attenzione alla semantica PHP degli include

Un file PHP non equivale sempre ad un modulo dichiarativo.

Può contenere:

- top-level code;
- definizioni condizionali;
- side effects;
- include dipendenti dal runtime;
- definizioni effettuate soltanto quando il file viene eseguito.

Perciò il watcher non deve “eseguire” il file al momento del reload.

Il watcher prepara una nuova rappresentazione del source:

```text
file → parsed/compiled SourceUnit
```

e aggiorna le informazioni staticamente sicure.

Le definizioni che, per semantica PHP, nascono solo durante l'esecuzione dell'include continuano a nascere al momento corretto nella request.

Le cache che assumevano il vecchio source vengono comunque versionate/invalidated.

## 10.9 Backend filesystem watcher

Il runtime deve nascondere il backend dietro una piccola interfaccia:

```text
SourceWatchBackend
    start(root)
    stop(root)
    events()
```

Prima scelta per il PoC:

```text
io.methvin:directory-watcher
```

perché è focalizzata sul problema, ha un'API piccola e copre il watching ricorsivo; resta però dietro alla nostra astrazione e deve superare i test Native Image su tutte le piattaforme desktop.

Il runtime **non** deve dipendere semanticamente da questa libreria.

Se il backend si dimostra inadeguato su una piattaforma, deve essere sostituibile senza toccare:

- dependency graph;
- reload pipeline;
- code generations;
- parser;
- symbol invalidation.

Fallback/base disponibile nel JDK:

```text
java.nio.file.WatchService
```

su cui è possibile implementare direttamente il backend se il wrapper esterno non porta sufficiente valore.

## 10.10 Criteri per dipendenze third-party

La scelta non viene fatta in base alla popolarità da sola.

Ordine dei criteri:

1. correttezza;
2. minimalismo;
3. nessuna o pochissime dipendenze transitive;
4. API piccola e chiara;
5. performance;
6. comportamento prevedibile cross-platform;
7. compatibilità Native Image;
8. manutenzione attiva;
9. qualità del codice/test;
10. soltanto a parità sostanziale, preferenza per la libreria più diffusa e più testata nell'ecosistema.

Questa regola vale per tutto il progetto, non soltanto per il file watcher.

## 10.11 Overflow e reconciliation

Qualunque filesystem watcher può perdere eventi o segnalare overflow.

In quel caso è ammesso un **reconciliation scan eccezionale** della root interessata:

```text
OVERFLOW
    ↓
scan affected root
    ↓
compare known path/version/hash metadata
    ↓
emit synthetic SourceChangeBatch
```

Questo non viola il requisito “no scan per request”, perché avviene solo come recovery del watcher.

## 10.12 Modalità development e production

```text
development:
    event watcher attivo
    debounce breve
    automatic code generation publish

production:
    watcher opzionale
    oppure deploy signal / explicit reload
    nessun controllo filesystem nel request path
```

Anche quando il watcher è disabilitato, la pipeline di invalidazione/versioning rimane la stessa: cambia soltanto chi produce il `SourceChangeBatch`.

---

# 11. TrueAsync — obiettivo semantico

Il runtime deve integrare il paradigma:

> **Write sync. Run async.**

Il codice applicativo non deve essere costretto a colorare l'intero call graph con `async`.

Una normale operazione I/O, eseguita dentro una coroutine, deve poter sospendere soltanto la coroutine corrente.

---

# 12. Coroutine e continuation

## 12.1 Truffle Bytecode DSL

La Bytecode DSL moderna supporta continuation/yield.

Il modello proposto è:

```text
PHP code
   ↓
Coroutine
   ↓
operation reaches awaitable I/O
   ↓
yield Continuation
   ↓
Scheduler
   ↓
Reactor
   ↓ event ready
resume Continuation
```

Non occorre implementare un secondo interprete per il codice asincrono.

---

# 13. Scheduler e Reactor

Componenti separati:

```text
Scheduler
    gestisce runnable coroutine,
    priorities,
    cancellation,
    scope,
    futures,
    continuations

Reactor
    gestisce file descriptor / socket / timer / wakeup
```

La semantica PHP non deve dipendere da uno specifico event-loop backend.

Possibili backend:

- libuv;
- Java/NIO dove appropriato;
- backend platform-specific.

La scelta può essere fatta tramite benchmark e portabilità.

---

# 14. Structured concurrency

Il modello deve includere gli equivalenti TrueAsync di:

- Coroutine;
- Future;
- Scope;
- TaskGroup;
- cancellation;
- timeout;
- channels;
- pools;
- structured ownership dei task.

Una coroutine figlia appartiene ad uno scope e non diventa implicitamente un “fire-and-forget” incontrollato.

---

# 15. Context gerarchici e branch di coroutine

Il runtime deve modellare esplicitamente un albero di contesti logici, senza creare un intero `TruffleContext` per ogni coroutine.

Struttura concettuale:

```text
Process / Root Context
        │
        ├── Request Context A
        │      │
        │      ├── Coroutine/Scope Branch A1
        │      │       ├── Coroutine A1.1
        │      │       └── Coroutine A1.2
        │      │
        │      └── Coroutine/Scope Branch A2
        │              └── Coroutine A2.1
        │
        └── Request Context B
               └── ...
```

Il concetto importante non è soltanto “un context per coroutine”, ma **ereditarietà per branch**.

Una coroutine figlia vede inizialmente il contesto ereditato dal proprio parent/branch.

Le modifiche a valori contestuali devono poter rimanere confinate al branch discendente senza contaminare:

- sibling coroutine;
- altri scope;
- altre request;
- root/process context;

a meno che l'API non richieda esplicitamente uno stato condiviso.

## 15.1 Livelli logici

Un singolo `PHPContext` Truffle contiene strutture logiche nostre:

```text
ProcessContext
RequestContext
ScopeContext
CoroutineContext
```

Questi non sono necessariamente `TruffleContext` separati.

L'environment corrente è determinato dal task/coroutine attualmente schedulato.

Lookup concettuale:

```text
current coroutine overlay
        ↓
parent coroutine/scope overlay
        ↓
request context
        ↓
root/process context
```

## 15.2 Inheritance senza copia completa

La creazione di una coroutine figlia **non deve copiare l'intero contesto**.

Modello consigliato:

```text
ContextNode
    parent
    localOverlay
    version/shape
```

Lookup:

```text
find key in localOverlay
else parent
```

Alla prima scrittura locale:

```text
write into child overlay
```

quindi il branch diverge in modalità copy-on-write/logical-overlay senza duplicare tutto il parent context.

Per i context hot o molto profondi sono possibili ottimizzazioni:

- flattening selettivo;
- cached lookup;
- context shape/version;
- inline cache per chiavi frequenti;
- promotion di chiavi;
- compaction di catene troppo profonde.

## 15.3 Semantica di branch

Esempio:

```text
Request R
  context: locale=en

  Coroutine A
    set locale=it

    Coroutine A1
      reads locale=it

  Coroutine B
    reads locale=en
```

La modifica fatta in `A` è visibile ai discendenti di `A`, ma non automaticamente a `B`.

Questo è il comportamento desiderato per valori contestuali che seguono la coroutine tree.

## 15.4 Stato globale PHP vs context-local state

Bisogna distinguere almeno tre categorie:

### Process shared state

Esplicitamente condiviso da tutte le request/coroutine:

```text
code metadata
immutable interned data
explicit shared application state
global runtime services
```

### Request state

Valido per l'intera request:

```text
$_GET
$_POST
$_SERVER
$_COOKIE
headers
output state
request-global table
included-files state secondo semantica scelta
```

### Coroutine/branch context

Valori che devono seguire l'albero di coroutine:

```text
current request bindings
logical execution context
cancellation/scope state
tracing metadata
user context variables
async-local values
```

La categoria di ogni dato deve essere esplicita.

Non dobbiamo nascondere tutto indiscriminatamente dentro `ThreadLocal`, perché una coroutine può sospendersi, migrare o essere ripresa in un momento diverso.

## 15.5 Context propagation nello scheduler

Ogni runnable coroutine porta un riferimento al proprio `CoroutineContext`.

Lo scheduler esegue concettualmente:

```text
resume(task):
    currentContext = task.context
    execute continuation
```

Una child coroutine nasce con:

```text
child.context.parent = current.context
```

e un overlay inizialmente vuoto.

La semantica rimane quindi indipendente dal thread fisico.

## 15.6 Migrazione fra thread

Se una coroutine viene ripresa su un thread differente:

- il suo context tree rimane lo stesso;
- non viene serializzato;
- non viene ricostruito;
- le regole local→shared descritte nella sezione threading si applicano agli oggetti effettivamente condivisi.

Il context logico appartiene alla coroutine/branch, non al worker thread.

## 15.7 Request completion

Quando termina una request:

- il suo `RequestContext` viene chiuso;
- tutti i branch discendenti devono essere terminati/cancellati secondo la structured concurrency policy;
- le risorse request-local vengono rilasciate;
- nessuna coroutine può mantenere accidentalmente vivo l'intero request tree salvo esplicita promozione ad uno scope process-persistent.

Questo è necessario sia per la correttezza sia per evitare retention/memory leak.

## 15.8 Ottimizzazione con Truffle

Il fatto che un context lookup sia dinamico non significa che ogni read debba percorrere sempre l'intero albero.

Callsite stabili possono specializzarsi su:

```text
context shape
key
depth
version
```

e invalidarsi quando la struttura osservata cambia.

L'obiettivo è ottenere:

```text
common async-local lookup
≈ small specialized fast path
```

mantenendo però la semantica gerarchica completa.

---

# 16. Globals e superglobali

Per preservare il comportamento del PHP tradizionale dentro un server long-running:

- `$_GET`, `$_POST`, `$_SERVER`, `$_COOKIE`, ecc. devono essere per-request;
- il global variable table applicativo deve essere isolabile per request;
- il codice e i metadati di classe/funzione possono essere condivisi;
- lo stato che deve veramente vivere oltre una request deve avere una sede esplicita nel process/root context.

Bisogna definire in modo preciso anche la semantica delle static properties in server mode.

**Scelta proposta:** default compatibile con il modello “fresh PHP request”, con meccanismi espliciti per process-persistent state.

---

# 17. Threading reale

## 17.1 Requisito del progetto

A differenza del TrueAsync attuale, che usa ambienti PHP separati e copia i valori tra thread, questo progetto vuole poter offrire anche:

> **heap PHP condiviso tra thread**

senza serializzazione automatica obbligatoria.

Questa è quindi una deliberata estensione/divergenza rispetto alla semantica di `spawn_thread` corrente di TrueAsync.

## 17.2 Truffle context condiviso

Truffle permette a un linguaggio di dichiarare l'accesso multi-thread allo stesso context.

Il runtime deve quindi implementare:

```text
isThreadAccessAllowed(...)
initializeMultiThreading(...)
initializeThread(...)
disposeThread(...)
```

e rendere sicure le proprie strutture interne.

---

# 18. Thread confinement e “pay only when shared”

Principio fondamentale:

> **un oggetto che non è mai condiviso fra thread non deve pagare il costo della concorrenza.**

## 18.1 Stato

```text
THREAD_LOCAL(owner)
        ↓ escape
SHARED
```

Finché è local:

- plain field;
- plain counter;
- nessun CAS sul percorso normale.

Al primo escape reale:

1. publish con barriera corretta;
2. transizione a storage strategy condivisa;
3. invalidazione delle speculazioni che assumevano thread confinement;
4. i successivi accessi usano il percorso shared.

---

# 19. Assumption e rejitting condizionale

Truffle `Assumption` è adatto a modellare condizioni speculative invalidabili.

Esempi:

```text
symbol foo has version 17
this execution path is single-threaded
this storage strategy has never escaped
this call target is still current
```

Quando una assumption viene invalidata:

```text
compiled code
   ↓
deopt
   ↓
interpreter
   ↓
new specialization / state
   ↓
runtime compilation again if hot
```

## 19.1 Granularità

Da evitare:

```text
un'Assumption pesante per ogni singolo piccolo valore PHP
```

Preferire:

- assumptions sui lookup hot;
- symbol versions;
- storage strategy;
- shape;
- call-site;
- context state;
- transitioni rare.

Le decisioni finali vanno prese con benchmark reali.

---

# 20. Data race

Il progetto non deve promettere che il codice PHP condiviso fra thread sia automaticamente race-free.

Politica:

```text
runtime memory safety       = responsabilità del runtime
business/data synchronization = responsabilità dell'utente
```

Se due thread scrivono contemporaneamente la stessa variabile senza lock, il risultato applicativo può essere racy.

Non deve però accadere:

- heap corruption;
- use-after-free;
- corruzione della VM;
- corruzione del COW metadata;
- refcount/sharing metadata incoerente.

---

# 21. Lock e primitive di sincronizzazione

Se si espone shared-memory threading, il runtime deve offrire primitive adeguate:

- Mutex;
- RWLock;
- Atomic;
- Semaphore;
- Condition;
- thread-safe channel;
- Future;
- ThreadPool.

Non si devono inserire lock impliciti attorno ad ogni oggetto PHP.

La sincronizzazione della logica applicativa è esplicita e resta responsabilità
dell'utente. Il mutex deve poter attraversare sospensioni senza confondere la
coroutine proprietaria con il thread fisico. L'API deve prevedere acquisizione,
tentativo senza attesa e rilascio, con una forma `synchronized(closure)` o a
blocco che garantisca il rilascio in `finally` anche su errore e cancellazione.

Channel per coroutine e ThreadChannel per thread reali devono offrire
backpressure, cancellazione delle attese e chiusura con drenaggio. I nomi e i
contratti pubblici si rifanno a TrueAsync; il trasferimento tra thread segue
però il nostro modello di pubblicazione nello stesso heap, non la copia in una
diversa istanza PHP. Le primitive non sostituiscono le garanzie di integrità
del runtime richieste dalle sezioni precedenti.

---

# 22. FFI PHP completamente runtime

## 22.1 Requisito

L'utente deve poter fare una dichiarazione FFI a runtime, da PHP, senza rebuild.

Concettualmente:

```php
$ffi = FFI::cdef($declaration, $library);
$ffi->some_function(...);
```

Il runtime nativo PHP viene compilato **una volta**.

Le dichiarazioni C rimangono dinamiche.

---

# 23. Backend FFI

Non conviene accoppiare tutta la semantica PHP FFI direttamente a una singola API Graal.

Creare un'astrazione:

```text
PHP FFI API
    ↓
C type system
    ↓
FFI Backend
       ├── Truffle NFI
       └── optional/custom libffi bridge
```

## 23.1 Truffle NFI

NFI fornisce già:

- lookup di libreria;
- lookup di simbolo;
- signature binding;
- tipi primitivi;
- pointer;
- function pointer;
- callback managed ← native;
- supporto in Native Image.

È ottimo per un primo backend.

## 23.2 Perché mantenere il backend astratto

La FFI PHP completa include un vero C type system:

- struct;
- union;
- array;
- pointer;
- typedef;
- function pointers;
- variadic functions;
- alignment;
- layout;
- ownership;
- CData;
- cast.

NFI è un'interfaccia interna di Truffle e non va trattata come ABI eterna.

Per piena compatibilità può risultare più robusto implementare un piccolo bridge diretto a `libffi`, mantenendo invariata l'API PHP.

---

# 24. Callback C → PHP

Requisito:

```text
C function
   ↓ invokes function pointer
PHP callable
```

Se il callback arriva sul thread che sta già eseguendo il context corretto, può essere trattato direttamente quando sicuro.

Se arriva da un thread nativo esterno:

```text
native thread
   ↓
callback trampoline
   ↓
Scheduler / wake queue
   ↓
correct Request/Coroutine Context
   ↓
PHP continuation/callable
```

Non deve essere eseguito arbitrariamente dentro il runtime PHP senza corretta attach/enter semantics.

---

# 25. FFI sincrona vs asincrona

Una funzione C arbitraria non diventa magicamente non-blocking.

La stessa funzione deve poter essere invocata:

```text
sync
```

oppure tramite wrapper:

```text
run_blocking / offload
```

Esempio concettuale:

```text
PHP coroutine
   ↓
submit native call to blocking executor
   ↓
yield
   ↓
native worker executes call
   ↓
completion posted to reactor
   ↓
resume coroutine
```

La proprietà async appartiene alla **modalità d'invocazione**, non necessariamente alla funzione C per sempre.

La FFI PHP deve offrire un flag `async` opzionale per la singola chiamata e un
nome di pool. Per esempio:

```php
$value = $ffi->some_function($argument, async: true, pool: 'blocking');
```

Il flag è false per default. Con true il runtime inoltra automaticamente la
chiamata al pool nominato e sospende la coroutine fino al risultato normale C.
I worker vengono creati on demand, riutilizzati e limitati insieme alla coda;
non deve essere necessario preparare un thread o una diversa istanza PHP.

L'API deve permettere di definire i pool nominati con numero minimo e massimo
di worker e capacità della coda. `FFI::definePool(name, min: 0, max: 4,
queueCapacity: 256)` è la forma iniziale. `min: 0` conserva lo spawn on demand;
un minimo positivo prepara i worker. `max: 1` serializza le chiamate inviate
allo stesso pool, necessario per librerie C che non ammettono più chiamate
contemporanee. Le chiamate sincrone e gli altri pool non partecipano a questa
serializzazione: l'utente deve indirizzare nello stesso pool tutto il lavoro
che richiede quella garanzia.

Il caricamento deve supportare sia DLL/SO esterne sia librerie statiche
collegate nel runtime tramite le ricette php-xmake. Il catalogo built-in
espone indirizzi espliciti senza presumere che ogni simbolo dell'eseguibile
sia visibile tramite il loader dinamico. Le API PHP delle estensioni sono
un ulteriore livello, non una conseguenza automatica del collegamento C.

La richiesta di cancellazione non equivale al ritorno fisico della funzione C:
callback, buffer e radici necessarie alla native call devono rimanere validi
fino al completamento effettivo. La chiusura deve rispettare questo ordine.

---

# 26. Zero-copy / low-copy FFI

Obiettivo:

> evitare serializzazione e marshalling inutili.

Non è però corretto esporre indiscriminatamente un raw pointer ad un oggetto managed che il GC può spostare.

Strategie:

- native/off-heap buffers;
- pinned memory dove disponibile e appropriato;
- managed object handle opachi;
- shared native arena;
- primitive array bridge;
- ownership esplicita.

Politica:

```text
zero-copy dove semanticamente e tecnicamente sicuro
copy dove necessario per lifetime / ABI / GC correctness
```

---

# 27. Standard library PHP e librerie C

Non vogliamo reimplementare SQLite, PCRE2 o cURL in Java.

La standard library viene divisa in tre categorie.

## A. Implementazione managed

Funzioni per cui Java/Truffle è naturale e non introduce incompatibilità.

## B. Native bundled library

Esempi:

- PCRE2;
- SQLite;
- cURL;
- eventualmente zlib;
- TLS backend;
- altre librerie fondamentali.

Queste vengono compilate per il target e integrate nel prodotto.

## C. Runtime user FFI

Librerie scelte dall'utente, caricate tramite FFI quando la piattaforma lo permette.

---

# 28. Native shim

Per librerie incorporate conviene non far dipendere il runtime da dettagli instabili del loro ABI in ogni punto.

Struttura:

```text
PHP stdlib implementation
      ↓
small stable native shim
      ↓
SQLite / PCRE2 / cURL / ...
```

Vantaggi:

- binding più piccolo;
- API controllata;
- async wrapping più semplice;
- ownership chiara;
- migliore portabilità.

---

# 29. Async delle librerie C

Non tutte vanno trattate allo stesso modo.

### PCRE2

CPU-bound, sincrona; normalmente si esegue direttamente.

### SQLite

Può bloccare. Possibile integrazione con blocking executor / worker pool.

### cURL

Meglio integrare il modello multi/socket con il reactor anziché spostare ogni richiesta HTTP su un thread.

### Database network clients

Quando esiste API nonblocking, preferirla; fallback a blocking executor quando necessario.

---

# 30. Zend extensions

**Esplicitamente fuori requisito.**

Non è obiettivo:

- implementare Zend Engine ABI;
- caricare estensioni PHP compilate per Zend;
- mantenere compatibilità binaria con `.so/.dll` PHP extension tradizionali.

Questo riduce enormemente la complessità.

Conseguenza importante:

molte funzioni che nell'implementazione ufficiale PHP arrivano da estensioni “standard” dovranno comunque essere:

- reimplementate;
- oppure collegate direttamente alle librerie C sottostanti.

---

# 31. Interoperabilità Polyglot

I valori PHP importanti devono implementare correttamente `InteropLibrary`.

Esempi:

```text
PHP object    ↔ members
PHP array     ↔ hash entries / array elements secondo semantica scelta
PHP closure   ↔ executable
PHP iterator  ↔ iterator interop
PHP scalar    ↔ primitive interop
```

Questo abilita un unico protocollo d'interoperabilità.

## 31.1 Importante: non significa “tutti i linguaggi inclusi gratis”

Il runtime PHP minimale non deve incorporare automaticamente:

- GraalPy;
- JavaScript;
- Ruby;
- altri runtime.

L'interoperabilità è disponibile quando il relativo guest language è incluso nel build/distribution profile.

Questo è essenziale per il target di dimensione.

---

# 32. Interop Java e Native Image closed world

Su HotSpot è possibile avere una relazione molto dinamica con classi Java.

In Native Image esiste invece una **closed-world assumption** per il codice Java host.

Quindi:

- codice Java noto/incluso nel build può essere interoperabile;
- non va promesso il caricamento arbitrario di qualunque JAR sconosciuto dopo la compilazione del Native Image;
- l'interoperabilità con guest language Truffle e l'interoperabilità con “arbitrary Java classloading” sono due problemi diversi.

Se in futuro serve un ecosistema Java plug-in totalmente runtime, va progettato separatamente.

---

# 33. Native Image standalone

Requisito primario:

```text
single distributable PHP runtime
no JDK
no Graal SDK
no compile step for user PHP
```

Native Image può contenere un language runtime Truffle.

Per i language runtime Truffle, Native Image può includere anche supporto per **runtime guest compilation**.

Quindi il modello desiderato è:

```text
host/interpreter implementation
    AOT nel native executable

PHP guest program
    parse a runtime
    interpret
    hot paths → runtime compilation
```

La closed-world analysis riguarda l'implementazione Java/Truffle del runtime, non l'elenco dei futuri script PHP.

---

# 34. JIT on/off con la stessa codebase

Il linguaggio viene implementato una volta sola.

Configurazioni:

```text
JIT enabled
    interpreter → runtime compiled hot code

JIT disabled
    interpreter only
```

La semantica PHP non deve cambiare.

Questo è importante soprattutto per target nei quali la generazione dinamica di executable memory non sia disponibile o desiderabile.

---

# 35. iOS

Requisito di progetto:

> iOS deve essere un target.

Non imponiamo al target mobile lo stesso formato di packaging del desktop.

Se il JIT runtime non è utilizzabile nella distribuzione iOS scelta:

```text
same PHP language implementation
+
Truffle interpreter-only
```

Non verrà mantenuta una seconda implementazione PHP.

**Da validare presto con PoC reale:**

- toolchain Native Image → iOS target;
- embedding/pacchettizzazione;
- Truffle runtime interpreter;
- NFI/FFI consentite dal modello iOS;
- startup;
- memory footprint;
- App Store constraints del prodotto concreto.

---

# 36. Android

Android è target obbligatorio.

Packaging naturale:

```text
native runtime / shared library
inside APK/AAB
```

Possibili integrazioni:

- native bridge;
- Java/Kotlin host;
- direct C API;
- embedded PHP application files.

Anche qui non imponiamo “fully static desktop rules” al formato mobile.

---

# 37. Desktop platforms

Target:

- Linux;
- Windows;
- macOS.

## Linux

Obiettivo preferito:

```text
fully static
musl
no external third-party runtime dependencies
```

## Windows

Obiettivo pratico:

```text
single executable / self-contained third-party dependencies
```

Le DLL di sistema Windows non sono considerate dipendenze da distribuire.

## macOS

Obiettivo pratico:

```text
self-contained application binary
no bundled third-party dylib where evitabile
```

Le librerie/framework di sistema Apple rimangono parte della piattaforma.

In altre parole, “statico” per il requisito di prodotto significa soprattutto:

> nessuna runtime dependency di terze parti che l'utente debba installare separatamente.

---

# 38. Tensione: fully-static Linux vs dynamic user FFI

Questo è uno dei pochi punti da provare immediatamente.

Un eseguibile Linux completamente statico via musl e la possibilità di fare `dlopen()` di librerie arbitrarie a runtime non devono essere dati per scontati come combinazione universale.

Possibili soluzioni:

### Profilo A — full-static

- tutte le librerie ufficiali incorporate;
- FFI verso simboli incorporati;
- eventuali limitazioni sul dynamic library loading.

### Profilo B — portable-ffi

- mostly-static o dynamic libc;
- pieno caricamento di `.so` esterni.

### Obiettivo

Provare se possiamo mantenere entrambe le proprietà nel build scelto.

**Questo deve essere uno dei primi spike tecnici.**

Le due distribuzioni restano un'opzione futura esplicitamente ammessa:
full-static senza caricamento dinamico, e portable-ffi con libc/libm di
sistema. Valutare inoltre [SoLo](https://github.com/pg83/solo) come possibile
loader ELF incorporato con ponte ABI glibc→musl. La sua adozione richiede una
prova NFI/Truffle, callback, TLS e threading; non va assunta equivalente a
`dlopen` per ogni libreria. Analisi e stato in `docs/static-distributions.md`.

---

# 39. Dimensione del binario

Requisito espresso:

> **default/full runtime: < 100 MB se tecnicamente raggiungibile**

Stretch target:

> **< 50 MB**

Non è ancora una garanzia.

Fattori che aumentano la dimensione:

- Truffle runtime;
- guest runtime compiler;
- graph encoding necessari alla runtime compilation;
- standard library;
- cURL;
- TLS;
- ICU/Unicode se incorporato;
- PCRE2;
- SQLite;
- debug metadata;
- eventuali guest language addizionali.

## 39.1 Regole

1. nessun guest language addizionale nel build base;
2. build report controllato in CI;
3. `-Os` come build size profile da misurare;
4. stripping release;
5. debug symbols separati;
6. budget per componente;
7. fail CI se il binario supera la soglia concordata.

Profili possibili:

```text
php-core
php-full
php-polyglot
php-mobile
```

La codebase resta unica.

---

# 40. Garbage Collection e lifetime PHP

Truffle/Native Image forniscono GC managed, ma PHP ha semantiche osservabili proprie.

Dobbiamo implementare correttamente:

- `__destruct`;
- request shutdown;
- cyclic object graphs;
- weak references;
- weak maps;
- resource lifetime;
- FFI CData lifetime;
- coroutine cancellation;
- abandoned futures;
- pending callback native;
- shutdown functions.

Non bisogna assumere che “il Java GC” equivalga automaticamente alla semantica PHP.

---

# 41. Request shutdown

In server mode la fine di una request deve avere una fase esplicita:

```text
cancel remaining request tasks
await/cleanup according to policy
flush output
run shutdown functions
release request resources
dispose request context
run deterministic PHP-visible cleanup where required
```

Non si può aspettare il GC globale.

---

# 42. Parser e compatibilità di sintassi

Il frontend deve poter evolvere rapidamente con PHP.

Scelte possibili:

- parser generato da grammatica mantenuta dal progetto;
- riuso/porting di grammatica esistente;
- frontend separato dal backend Truffle.

Requisito:

> il parser non deve contaminare il core runtime con strutture specifiche della versione sintattica.

Versionare:

```text
syntax frontend
semantic lowering
runtime semantics
```

---

# 43. Vecchi progetti GraalPHP / TrufflePHP

I progetti storici possono essere usati come:

- riferimento;
- corpus di idee;
- esempi di nodi;
- casi di test;
- eventuale recupero di parser/semantica.

Non vanno considerati automaticamente la base da aggiornare in blocco.

Prima si definisce il modello runtime moderno; poi si decide cosa riusare.

---

# 44. Standard library: problema di scope

L'assenza di compatibilità Zend extension non elimina il lavoro sulla standard library.

PHP reale dipende da moltissime aree:

- date/time;
- JSON;
- hash;
- OpenSSL/TLS;
- mbstring/Unicode;
- PCRE;
- sockets;
- streams;
- filters;
- PDO;
- SQLite;
- cURL;
- compression;
- fileinfo;
- XML;
- DOM;
- simplexml;
- intl;
- session;
- password APIs;
- random;
- process control, dove disponibile.

Serve quindi una **compatibility matrix** con priorità.

---

# 45. Composer come milestone

Una milestone fondamentale dovrebbe essere:

```text
Composer runs correctly on GraalPHP
```

Poi:

```text
common Composer autoload works
```

e successivamente framework/applicazioni reali.

Composer esercita molte semantiche dinamiche importanti e diventa un ottimo test di compatibilità.

---

# 46. Performance model

Le performance attese non derivano da “Java è veloce” ma da:

- specializzazione Truffle;
- partial evaluation;
- monomorphic inline caches;
- shape specialization;
- unboxed primitives;
- packed array storage;
- COW senza copie inutili;
- thread-local fast path;
- rare invalidations;
- JIT sui path hot;
- native implementation delle librerie adatte.

## 46.1 Fast paths fondamentali

Ottimizzare prioritariamente:

```text
local variable read/write
integer arithmetic
function call
method call
property read/write
packed array read/write
hash array read/write
isset
foreach
string concat
COW check
reference check
```

---

# 47. Reoptimization e deoptimization

Il runtime deve essere progettato assumendo che le condizioni possano cambiare.

Esempio:

```text
callsite sees only integer
→ int specialization

later sees double
→ rewrite / new specialization
```

Analogamente:

```text
storage has never been shared
→ thread-local fast path

storage escapes to another thread
→ invalidate
→ shared strategy
```

Questo è preferibile a inserire preventivamente il costo massimo su ogni operazione.

---

# 48. Inline caches

Use case:

- function lookup;
- method dispatch;
- property lookup;
- dynamic include path;
- array storage strategy;
- FFI function binding.

Esempio:

```text
include($path)
```

Se un callsite vede quasi sempre lo stesso path:

```text
PIC:
path A → SourceUnit A
path B → SourceUnit B
fallback → generic resolver
```

---

# 49. FFI cache

`FFI::cdef` e lookup simboli possono essere dinamici, ma i callsite stabili devono diventare veloci.

Cache:

```text
library identity
symbol identity
signature identity
ABI
```

Una volta stabile, la chiamata non deve riparsare la dichiarazione C ad ogni invocazione.

---

# 50. Reactor + blocking executor come astrazione unica

Non creare un sistema separato “solo FFI”.

Definire:

```text
AsyncOperation
BlockingOperation
NativeOperation
IOOperation
```

Tutte possono produrre un future/waker e sospendere una continuation.

Questo permette di usare lo stesso meccanismo per:

- FFI blocking;
- SQLite;
- filesystem quando necessario;
- DNS blocking;
- legacy native APIs;
- CPU job esplicitamente offloadato.

---

# 51. Native callback lifetime

Una callback data a C può vivere più a lungo della singola chiamata.

Quindi bisogna modellare:

```text
callback registration
callback handle
request ownership
unregistration
native lifetime
runtime shutdown
```

Il callback non deve mantenere accidentalmente viva per sempre una request o un enorme object graph.

---

# 52. Sicurezza FFI

La FFI è per definizione unsafe.

L'utente può:

- dereferenziare pointer errati;
- causare buffer overflow nella libreria C;
- chiamare ABI errate;
- crashare il processo.

Non è obiettivo rendere una FFI C arbitraria memory-safe.

È però obiettivo:

- non introdurre corruption lato managed per errori del bridge;
- validare ciò che può essere validato;
- rendere chiara ownership e lifetime.

---

# 53. Tooling Truffle

Benefici potenziali:

- debugger;
- instrumentation;
- profiler;
- coverage;
- source locations;
- stack traces;
- polyglot tools.

Non è una milestone P0, ma bisogna conservare source sections corrette fin dall'inizio.

---

# 54. Error model

Va definita una mappatura precisa per:

```text
PHP warning
PHP notice/deprecation
PHP Error
PHP Exception
fatal error
parse error
FFI fault detectable
cancellation
timeout
thread remote exception
```

La cancellation TrueAsync non deve diventare accidentalmente un normale warning ignorabile.

---

# 55. Multi-request isolation

Il runtime server deve poter gestire simultaneamente:

```text
Request A
Request B
Request C
```

senza contaminazione di:

- globals;
- superglobals;
- output buffers;
- locale per-request dove richiesto;
- headers;
- errors;
- request context;
- included-files set;
- autoload request state.

Il codice compilato può invece essere condiviso.

---

# 56. Persistent metadata vs request data

Separare chiaramente:

## Shared runtime/code metadata

- parsed source;
- bytecode;
- immutable constants;
- class descriptors;
- method descriptors;
- JIT metadata;
- symbol definitions/version data.

## Request state

- PHP global values;
- static values se configurate request-local;
- superglobals;
- included set;
- output;
- headers;
- request scope;
- open request resources.

---

# 57. Server architecture

Modello possibile:

```text
Process
  ├─ PHP Truffle Context
  ├─ Code Repository
  ├─ Runtime Compiler
  ├─ Scheduler(s)
  ├─ Reactor(s)
  ├─ Thread pools
  │
  ├─ Request A → context tree → coroutines
  ├─ Request B → context tree → coroutines
  └─ Request C → context tree → coroutines
```

Il numero di reactor/thread può essere configurabile.

## 57.1 Cooperazione con altri event loop

Lo scheduler PHP, libuv e un eventuale dispatcher GUI devono poter cooperare
mediante notifiche di lavoro e porzioni finite di esecuzione. Evitare timer
periodici usati per interrogare altri loop. Restano ammessi i timer one-shot
per operazioni e deadline effettive. Ogni adattatore espone avanzamento non
bloccante, notifica di lavoro, prossima deadline, cancellazione e drenaggio.

Non è necessario designare globalmente un loop principale. Il loop GUI, se
presente, impone la propria affinità di thread; altri backend possono avere
un thread proprietario e inviare completamenti. Nessun callback può eseguire
PHP su un thread arbitrario aggirando il dispatcher o le regole di ownership.

L'implementazione consolida scheduler PHP, `uv_run` e callback nativi sullo
stesso thread proprietario. Il server CLI attende direttamente nel backend
libuv; i worker esterni lo risvegliano con `uv_async_send`. Il driver host usa
`post`, timer cancellabili e checkpoint nei loop PHP. In embedding soltanto,
un helper attende gli eventi IOCP/epoll e notifica il dispatcher senza
eseguire callback libuv o PHP. Xmake applica la patch Electron fissata che
aggiunge `UV_LOOP_INTERRUPT_ON_IO_CHANGE` e la sospensione degli interrupt
mentre l'helper è parcheggiato. Non si presume `uv_backend_fd` disponibile
su Windows e non si leggono offset IOCP indovinati: lo shim C è compilato
con la stessa struttura libuv. cURL usa multi/socket e un timer one-shot.
Compose e mobile restano integrazioni successive.

L'allineamento a TrueAsync riguarda anche il percorso locale: FIFO senza lock
per il lavoro del proprietario, inbox sincronizzata solo per notifiche da altri
thread e conteggi di attività aggiornati agli eventi di spawn/completamento.
Gli scope indipendenti partecipano al lifetime della richiesta anche senza
discendere dallo scope radice. Le continuation Bytecode DSL gestiscono oggi la
sospensione PHP. Il bridge FFI ora conserva stack C separati per chiamate
native annidate e callback PHP sospendibili dichiarate con `FFI::cdef`. Cede
al dispatcher prima di entrare in PHP e riprende il C sul proprietario quando
la callback termina; `async: true` mantiene invece il thread C nel pool nominato
e la callback sulla coroutine PHP originale. Le callback di questo percorso
sono limitate alla chiamata e al suo thread C: registrazioni persistenti,
callback da altri thread e il modello CData completo restano da sviluppare.
Non assumere che uno switch C possa sospendere in sicurezza
frame Java/NFI arbitrari senza supporto della VM. La prova e i limiti del bridge
sono descritti in `docs/native-stack-bridge.md`.

Una chiamata C che blocca senza cedere richiede ancora il pool esplicito oppure
un adattatore asincrono nativo; le callback libuv non riprendono direttamente
codice guest. Buffer, puntatori, root del GC, affinità di thread, cancellazione
e unwinding devono coprire l'intera durata della chiamata nativa sospesa.

Il provider curl-impersonate va aggiornato come bundle coerente con la propria
versione di curl e le patch upstream. Non aggiornarne separatamente il pin curl.
Il provider parallelo cURL standard 8.22.0 ha simboli curl/TLS isolati e handle
associati al provider corretto, con BoringSSL non modificata in un archivio separato.
La selezione avviene per ambiente o per handle; entrambi i provider condividono il
thread PHP/libuv. I confronti correnti usano il provider standard e il solo carico
cURL: vedere [provider e verifica](docs/curl-providers.md).

---

# 58. Affinità delle coroutine

Nel fast path è preferibile che una coroutine continui a essere eseguita sullo stesso scheduler thread.

Migrazione fra thread:

- deve essere esplicita o controllata;
- implica publish/visibility delle strutture necessarie;
- può promuovere dati da local a shared.

Questo riduce transizioni inutili.

---

# 59. Thread-local optimization

Un'importante ottimizzazione globale:

```text
context starts single-threaded
```

Truffle espone un hook quando il context passa realmente a uso multi-thread.

Quindi il runtime può avere:

```text
global single-thread assumption
```

fino alla prima esecuzione concorrente.

Dopo:

```text
invalidate
→ multi-thread capable runtime paths
```

Questo si combina con confinamento più fine a livello storage/oggetto.

---

# 60. Build profiles

## `php-core`

- parser;
- core language;
- core stdlib;
- no optional polyglot language;
- minimal native libs.

## `php-full`

Default target.

Include le librerie PHP considerate standard dal progetto.

**Target size: <100 MB.**

## `php-polyglot`

Include esplicitamente altri guest language richiesti.

Nessuna garanzia di stare sotto 100 MB.

## `php-mobile`

Stessa codebase e semantica, packaging mobile-specifico.

---

# 61. Requisito di non dipendenza da toolchain

L'utente finale non deve avere:

```text
gcc
clang
javac
native-image
GraalVM SDK
Maven
Gradle
```

per eseguire normale PHP o per dichiarare una FFI.

Naturalmente, per caricare una libreria FFI esterna, la libreria nativa deve esistere già per quella piattaforma.

---

# 62. Cosa NON viene compilato a build-time

Non vengono preconosciuti:

- file PHP dell'utente;
- `eval`;
- stringhe `FFI::cdef`;
- include path dinamici;
- classi PHP generate runtime;
- callable PHP futuri.

Questi restano guest-level runtime data/code.

---

# 63. Cosa deve essere noto al Native Image

Deve essere incluso nel closed world:

- implementazione Java del runtime;
- parser;
- bytecode DSL runtime;
- Truffle API usate;
- backend FFI;
- standard library managed;
- bridge native ufficiali;
- compiler support necessario alla runtime guest compilation;
- metadata Native Image necessari.

---

# 64. Open questions / rischi reali

## R1 — Fully-static + arbitrary dynamic FFI

Da prototipare subito.

## R2 — Dimensione sotto 100 MB con runtime compilation

Possibile come target, non ancora garantita.

Va misurata su un prototipo reale.

## R3 — Mobile

Le API Native Image modellano Android/iOS come target, ma il prodotto deve validare una pipeline reale end-to-end, soprattutto Truffle runtime + FFI + packaging.

## R4 — iOS runtime JIT

Non va considerato requisito bloccante.

Se non disponibile:

```text
interpreter-only
```

ma la build iOS completa va provata presto.

## R5 — PHP FFI completa

NFI copre il nucleo, ma la compatibilità con tutto il type system FFI PHP può richiedere bridge `libffi` proprio.

## R6 — Shared-memory thread semantics

È una scelta più ambiziosa del TrueAsync attuale e richiede una memoria/runtime architecture rigorosa.

## R7 — Standard library breadth

Probabilmente il maggiore costo complessivo di compatibilità una volta risolto il core language.

## R8 — Destructor/GC/request shutdown semantics

Possibili differenze osservabili con PHP se non progettate esplicitamente.

## R9 — Static properties in long-running multi-request server

Semantica da fissare prima di far girare framework reali.

## R10 — Polyglot + minimal binary size

L'interop deve essere architetturalmente disponibile, ma gli altri language runtime devono essere optional.

---

# 65. Spike tecnici da fare PRIMA del runtime completo

## Spike 1 — COW + reference

Implementare solo:

- int/string;
- PHP array;
- nested array;
- assignment;
- COW;
- `&`;
- nested reference;
- foreach-by-ref minimo.

Misurare correttezza e performance.

## Spike 2 — Deopt local → shared

Oggetto/storage:

```text
LOCAL → SHARED
```

Verificare:

- Assumption invalidation;
- deoptimization;
- recompile;
- costo del fast path local;
- costo dopo promotion.

## Spike 3 — Bytecode continuation

Mini PHP-like bytecode:

```text
call
yield
resume
try/finally
exception
```

Verificare stack, exceptions e cancellation.

## Spike 4 — Request context tree

```text
root
request
scope
coroutine
```

con 100k coroutine sintetiche.

Misurare lookup context e memory footprint.

## Spike 5 — Hot reload + filesystem watcher

Due file:

```text
A.php includes B.php
```

Modificare B mentre il server vive.

Verificare:

- recursive event watcher;
- nessun polling/stat nel request path;
- editor atomic-save (`temp + rename`);
- debounce/coalescing;
- create;
- delete;
- rename/move;
- nuova directory;
- overflow + reconciliation;
- new code generation;
- old request consistency;
- selective symbol/source invalidation;
- symbol assumption invalidation;
- recompilation;
- publish atomico solo dopo parse/compile riuscito.

Ripetere almeno su:

- Linux;
- Windows;
- macOS;

sia JVM sia Native Image.

## Spike 6 — Dynamic FFI

Runtime:

1. parse signature;
2. load native lib;
3. call function;
4. pass function pointer;
5. callback C → PHP.

Nessun rebuild.

## Spike 7 — External-thread callback

C crea un thread e richiama PHP.

Verificare il percorso:

```text
native thread → enqueue → scheduler → correct context
```

## Spike 8 — Async blocking FFI

FFI call sul blocking executor:

```text
submit
yield
complete
resume
```

senza serializzazione dell'intero object graph.

## Spike 9 — Native Image + guest JIT

Creare un mini language Truffle come Native Image.

Verificare realmente:

- standalone binary;
- interpreter;
- guest runtime compilation;
- assumption invalidation;
- deopt/re-JIT.

Misurare la dimensione.

## Spike 10 — Linux static + FFI

Provare:

```text
--static --libc=musl
```

insieme a:

- NFI;
- libffi;
- bundled symbol;
- external `.so` load.

Questo spike decide il packaging Linux.

## Spike 11 — Android

Mini runtime embedded in app.

## Spike 12 — iOS

Mini runtime interpreter-only embedded in app.

---

# 66. Benchmark suite iniziale

Microbenchmark:

- scalar local read/write;
- function call;
- method dispatch;
- closure call;
- packed array read;
- packed array write;
- hash array read/write;
- COW first write;
- COW no-write assignment;
- reference read/write;
- nested reference;
- foreach;
- string concat;
- coroutine yield/resume;
- context lookup;
- thread local array;
- promoted shared array;
- FFI primitive call;
- FFI callback;
- blocking executor round trip.

Confronti:

```text
GraalPHP interpreter
GraalPHP JIT
reference PHP runtime
```

Lo scopo iniziale non è battere PHP ovunque, ma capire dove il modello genera overhead strutturale.

---

# 67. Correctness test suite

La compatibilità deve essere test-driven.

Fonti:

- test PHP `.phpt` compatibili con la licenza;
- test propri mirati;
- casi edge di reference/COW;
- Composer;
- framework reali;
- suite TrueAsync rilevanti;
- test concorrenti;
- stress GC;
- reload races;
- FFI ABI tests.

---

# 68. Test COW/reference obbligatori

Esempi da coprire:

```php
$a = [1];
$b = $a;
$b[0] = 2;
```

```php
$x = 1;
$a = [&$x];
$b = $a;
$x = 3;
```

```php
$a = ['x' => ['y' => 1]];
$r =& $a['x']['y'];
$b = $a;
$r = 2;
```

```php
foreach ($a as &$v) { ... }
```

```php
function &refReturn() { ... }
```

```php
unset($referencedSlot);
```

```php
$a[] =& $x;
```

Questi test devono essere corretti prima di iniziare a “ottimizzare via benchmark”.

---

# 69. Compatibility milestone proposal

### M0 — Feasibility core

Tutti gli spike principali verdi.

### M1 — Language core

- syntax basics;
- functions;
- arrays;
- objects;
- COW;
- refs;
- exceptions;
- include.

### M2 — Dynamic runtime

- autoload;
- reflection;
- eval;
- hot reload;
- request isolation.

### M3 — Composer

Composer funzionante.

### M4 — TrueAsync core

- coroutine;
- future;
- cancellation;
- scope;
- context;
- reactor.

### M5 — Threading

- thread;
- thread pool;
- shared heap opt-in;
- locks/atomics;
- local→shared specialization.

### M6 — FFI

- cdef;
- CData;
- callbacks;
- blocking offload.

### M7 — Standard library full target

PCRE2/SQLite/cURL/TLS/etc.

### M8 — Native distribution

- Linux;
- Windows;
- macOS.

### M9 — Mobile

- Android;
- iOS.

### M10 — Performance / compatibility hardening

Framework e applicazioni reali.

---

# 70. Architettura complessiva proposta

```text
                     ┌─────────────────────────┐
                     │      PHP Source         │
                     └────────────┬────────────┘
                                  │
                           PHP Parser
                                  │
                                  ▼
                     ┌─────────────────────────┐
                     │      Semantic IR        │
                     └────────────┬────────────┘
                                  │
                          Bytecode Builder
                                  │
                                  ▼
              ┌────────────────────────────────────┐
              │       Truffle Bytecode Roots       │
              │ specializations / caches / yields  │
              └────────────────┬───────────────────┘
                               │
          ┌────────────────────┼─────────────────────┐
          │                    │                     │
          ▼                    ▼                     ▼
   PHP Value Model       PHP Symbol System      Async Runtime
  COW / refs / objects   version/assumption     scheduler/reactor
          │                    │                     │
          └──────────────┬─────┴────────────┬────────┘
                         │                  │
                         ▼                  ▼
                  Polyglot Interop        FFI Layer
                                          │
                              ┌───────────┴───────────┐
                              ▼                       ▼
                         Truffle NFI           libffi/custom shim
                              │                       │
                              └───────────┬───────────┘
                                          ▼
                               Native libraries / OS

                               Graal / Truffle runtime
                                          │
                    ┌─────────────────────┴─────────────────────┐
                    ▼                                           ▼
               Interpreter                                  Guest JIT
                    │                                           │
                    └─────────────────────┬─────────────────────┘
                                          ▼
                               Native Image executable
```

---

# 71. Runtime state architecture

```text
Native Process
│
├── PHPContext (Truffle)
│   ├── CodeRepository
│   │   ├── Sources
│   │   ├── Parsed Units
│   │   ├── Bytecode Roots
│   │   └── Code Generations
│   │
│   ├── SymbolRegistry
│   │   ├── Functions
│   │   ├── Classes
│   │   ├── Constants
│   │   └── CyclicAssumptions
│   │
│   ├── ProcessContext
│   ├── Scheduler / Reactor
│   ├── NativeLibraryRegistry
│   └── Shared Runtime Services
│
├── RequestContext #1
│   ├── superglobals
│   ├── global values
│   ├── included-files
│   ├── output
│   └── Scope tree
│       ├── Coroutine A
│       └── Coroutine B
│
└── RequestContext #2
    └── ...
```

---

# 72. Principi di implementazione

1. **Correctness first** per COW/reference.
2. **Pay for concurrency only after escape.**
3. **No serialization by default** fra thread condivisi.
4. **No global locks** nel fast path.
5. **Immutable/versioned code metadata** quando possibile.
6. **Request state separato dal code state.**
7. **Assumptions per eliminare i check stabili.**
8. **Deopt è uno strumento normale**, non un errore.
9. **Native libraries dove ha senso**, managed code dove conviene.
10. **FFI runtime**, non build-time API generation.
11. **One language codebase**, JIT o interpreter configurabili.
12. **Mobile packaging follows the platform.**
13. **No Zend extension ABI.**
14. **Binary size is a CI metric.**
15. **Interop is modular**, non tutto incluso nel default binary.
16. **No filesystem work in the normal request path**: gli aggiornamenti arrivano dal watcher.
17. **Coroutine context follows the coroutine tree, not the OS thread.**
18. **Third-party dependencies are selected for correctness/minimalism first, popularity only as tie-breaker.**

---

# 73. Decisioni già prese

### Sì

- PHP come vero linguaggio Truffle.
- Native Image standalone come obiettivo.
- codice PHP dinamico a runtime.
- hot reload server event-driven tramite filesystem watcher.
- nessun filesystem scan/stat nel normale request path.
- reload incrementale con debounce/coalescing e publish atomico per CodeGeneration.
- context PHP gerarchici con inheritance per branch di coroutine.
- branch-local context overlay senza copia completa dell'intero contesto.
- COW PHP.
- PHP references complete.
- TrueAsync semantics.
- coroutine cooperative.
- structured concurrency.
- hierarchical context.
- real threads.
- shared heap fra thread come capacità del nuovo runtime.
- runtime internal safety senza implicit user-level locking.
- conditional/shared-state overhead.
- FFI dinamiche.
- callback C → PHP.
- async offload di chiamate bloccanti.
- librerie C incorporate per stdlib.
- Linux/Windows/macOS/Android/iOS.
- interpreter-only fallback per target senza JIT.
- no Zend extension compatibility.
- target binario default <100 MB.
- altri guest language non inclusi nel build minimo.

### Da validare con PoC

- fully-static Linux + arbitrary runtime `.so` FFI;
- dimensione reale del native executable con runtime compiler;
- full PHP FFI C type model tramite NFI vs custom libffi bridge;
- iOS end-to-end;
- Android end-to-end;
- costo/beneficio exact local→shared storage strategies;
- scelta reactor backend;
- backend filesystem watcher finale dopo benchmark/Native Image validation (`directory-watcher` come candidato iniziale);
- semantica finale delle static properties in server mode.

---

# 74. Note di verifica tecnica

Al momento della stesura sono stati verificati nella documentazione GraalVM/Truffle corrente i seguenti punti:

- `TruffleLanguage` consente a un linguaggio di optare esplicitamente per accesso concorrente allo stesso context e fornisce hook di inizializzazione multi-thread;
- `Assumption` è una primitive Truffle ottimizzabile e invalidabile;
- `CyclicAssumption` è adatta a dati ridefinibili/versionati;
- la Bytecode DSL supporta continuation/yield e coroutine stackless;
- Native Image può includere metodi destinati a runtime compilation per un linguaggio Truffle;
- NFI supporta signature dinamiche, function pointers e callback native→managed;
- Native Image supporta executable statici Linux con musl;
- le API Native Image attuali modellano esplicitamente target Android e iOS.

Punti che devono comunque essere dimostrati sul nostro prodotto reale sono elencati nella sezione **Open questions / rischi reali**.

---

# 75. Nota TrueAsync

La documentazione TrueAsync corrente descrive:

- coroutine cooperative;
- I/O nonblocking trasparente;
- structured concurrency;
- `Scope`;
- `Future`;
- `Thread` / `ThreadPool`;
- context gerarchici;
- per-request scope/context;
- context per-coroutine.

Una differenza intenzionale del nostro progetto è il threading.

TrueAsync corrente tratta i thread come ambienti PHP separati e copia i valori trasferiti.

Il design di GraalPHP qui descritto vuole aggiungere una modalità shared-memory:

```text
same managed heap
+
explicit synchronization by user
+
runtime integrity guaranteed
```

Questa divergenza deve essere documentata chiaramente nell'API.

---

# 76. Primo deliverable tecnico consigliato

Prima di iniziare il parser PHP completo, costruire un repository di laboratorio con:

```text
graalphp-lab/
  value-model/
  cow-reference/
  bytecode-continuation/
  threading-deopt/
  hot-reload/
  ffi/
  native-image/
  mobile/
  benchmarks/
```

Solo quando questi spike hanno dimostrato i punti architetturali più rischiosi si congela il design del runtime principale.

Questo evita di costruire decine di migliaia di righe di frontend prima di scoprire un limite nel modello di esecuzione.

---

# 77. Criterio di successo architetturale

Il progetto è architetturalmente riuscito se possiamo dimostrare contemporaneamente:

```text
PHP semantics correct
+
COW/reference efficient
+
single-thread fast path with no unnecessary atomic overhead
+
shared-memory threads available
+
TrueAsync coroutine semantics
+
dynamic hot reload
+
dynamic runtime FFI
+
native callbacks
+
standalone Native Image
+
guest JIT on supported targets
+
interpreter fallback on constrained targets
+
mobile builds
+
default distribution under the agreed size budget
```

---

# 78. Riassunto finale

Il punto più importante emerso dall'analisi è che le caratteristiche “strane” del progetto non sono singolarmente incompatibili con Truffle.

Anzi, varie caratteristiche del framework combaciano particolarmente bene con i requisiti:

```text
PHP dynamic dispatch        → specialization / inline cache
hot reload                  → event watcher + assumptions / invalidation
symbol redefinition         → CyclicAssumption
coroutine context branches  → hierarchical overlays + scheduler propagation
COW fast path               → specialized storage
thread confinement          → speculation + deopt
coroutine                    → Bytecode DSL continuations
native functions            → NFI / libffi backend
C callbacks                  → executable interop / trampoline
standalone runtime          → Native Image
JIT optional                → same interpreter, compilation on/off
```

Le parti realmente difficili non sono “Truffle lo permette?”, ma:

1. modellare **COW + reference** in modo esatto e veloce;
2. ottenere shared-memory threading senza rendere atomico tutto;
3. definire request/context isolation corretta;
4. implementare abbastanza standard library da essere un vero PHP;
5. fare una FFI completa senza vincolarsi troppo a un backend;
6. rispettare il budget di dimensione;
7. validare subito static FFI e mobile.

Questi sono i punti su cui deve concentrarsi il prototipo iniziale.
