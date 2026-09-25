# GraalPHP

Runtime sperimentale **PHP 8.6 su Truffle Bytecode DSL**, con API asincrona basata
su **TrueAsync 0.10.0**. La stessa pipeline esegue file, include, eval e reload
su JVM e negli eseguibili Native Image Windows/Linux.

Non è ancora compatibile con applicazioni PHP generiche. La
[mappa del design](docs/design-coverage.md) e il
[contratto TrueAsync](docs/trueasync-compatibility.md) distinguono implementazione,
verifiche e requisiti ancora aperti, compreso Compose.

## Riprendere il lavoro

[AGENTS.md](AGENTS.md) raccoglie istruzioni e decisioni da preservare;
[TODO.md](TODO.md) elenca attività aperte e criteri di completamento;
[stato di consegna](docs/handoff.md) collega risultati correnti, comandi,
artefatti, limiti e riferimenti. Leggerli prima di modificare il runtime.

## Windows x64

```bat
build.bat
build.bat native-libs
build.bat verify
build.bat oracle
build.bat native
build.bat network-test
build.bat network-test native
build.bat network-benchmark
build\graalphp.exe examples\language.php
build\graalphp.exe examples\trueasync.php
build\graalphp.exe examples\async-composition.php
build\graalphp.exe examples\native-callback.php
build\graalphp.exe --interpreter examples\trueasync.php
```

`build.bat` è l'unico ingresso Windows per setup e build. Scarica Oracle GraalVM
**25.4.4.1.1+1.1** (Java 25.0.4.1.1), verifica il checksum e installa in `tools/`.
Le dipendenze Truffle/NFI sono fissate con SHA-256 in `dependencies.lock`.
MSVC e Windows SDK sono portabili, preparati con `msvcup` in `build/toolchain`.

Il comando senza argomenti compila il JAR senza avviare test.
`verify` esegue i test integrati, il laboratorio semantico e il confronto
TrueAsync. `trueasync` esegue soltanto quest'ultimo; `oracle` confronta
gli esempi `compat.php` e `language.php` con PHP 8.6.0RC2.
`network-test` esegue il percorso WebSocket → HTTPS/curl-impersonate → SQLite
con peer di rete indipendenti; `network-test native` rigenera e verifica
l'eseguibile. Vedere [network-integration.md](docs/network-integration.md).
`network-benchmark` confronta CPU, memoria e rete dell'eseguibile Windows con
TrueAsync, usando lo stesso server PHP. Metodo, risultati e limiti sono in
[performance.md](docs/performance.md).
Il confronto prima/dopo il consolidamento PHP/libuv è in
[reactor-performance.md](docs/reactor-performance.md).
Il confronto dei sorgenti TrueAsync e gli esperimenti che isolano i costi
di GC, scheduler e libuv sono in [trueasync-reactor-comparison.md](docs/trueasync-reactor-comparison.md).
Il successivo [allineamento a TrueAsync](docs/trueasync-alignment.md) porta nel
prodotto il collector corretto, la FIFO locale senza lock e i conteggi degli scope.
Il [bridge con stack C sospendibili](docs/native-stack-bridge.md) integra le
callback PHP asincrone in `FFI::cdef`, senza spostare frame managed su stack C.

Il JAR richiede `build/deps/25.4.4.1.1/`, referenziata dal manifest.
L'eseguibile richiede le DLL MSVC copiate accanto al programma;
`graalphp-native.dll` serve alla JVM e agli esempi che la caricano esplicitamente.
Il catalogo `builtin:*` è collegato staticamente nell'eseguibile e non richiede
il bridge DLL né una JVM installata.

## Linux e macOS

```sh
bash build.sh
bash build.sh verify
bash build.sh native
bash build.sh trueasync native
bash build.sh network-test native
build/graalphp examples/trueasync.php
```

Lo script prepara SDKMAN sotto `tools/sdkman`, senza modificare gli startup file
della shell, e usa `sdk install java 25.4.4+1-graal`. Se manca `zip` su un sistema
Debian/Ubuntu, ne scarica ed estrae il pacchetto in `tools/zip` senza installarlo
globalmente. Compilatori C/C++, archivi statici libstdc++/libgcc e header zlib
devono essere disponibili.

Setup, build, test e Native Image sono stati eseguiti su Ubuntu x64 in WSL.
Il binario Linux usa libc/libm di sistema; non è fully-static. Le librerie
applicative del bundle sono collegate staticamente. Il watcher passa sul
filesystem Linux: il test degli eventi non passa sulla directory Windows
montata in `/mnt/c`. Il codice sorgente può risiedervi per la build, ma la root
PHP da osservare deve usare un filesystem che fornisca gli eventi necessari.

macOS ha un percorso di setup predisposto, ancora non verificato.
Il bundle dei target php-xmake e il bridge sono compilati con Xmake
anche su Linux x64. Il setup crea localmente il bundle Xmake con le estensioni
richieste, senza installarlo nel sistema; gli eventuali runtime ncurses del
bootstrap vengono estratti in `tools/`. La build nativa macOS non è ancora
predisposta per queste ricette.

## Linguaggio e API

Il frontend implementa funzioni, namespace e alias, classi con ereditarietà,
proprietà/metodi pubblici, protetti, privati e statici, closure e arrow function,
default, variadici, argomenti nominati nelle chiamate utente e tipi semplici. Include inoltre array COW e reference,
l-value annidati, for/while/do/foreach, break/continue, ternario/coalesce,
try/catch/finally, include ed eval. Classi e closure conservano ownership
e continuazioni anche quando sospendono.

L'API asincrona pubblica usa il namespace upstream:

```php
use Async\Scope;
use function Async\await;
use function Async\delay;
use function Async\timeout;

$scope = Scope::inherit();
$task = $scope->spawn(function() {
    delay(2);
    return 42;
});
$scope->awaitCompletion(timeout(1000));
echo await($task);
```

`current_context()` è condiviso nello scope; `coroutine_context()` è privato
della coroutine. `new Scope()` crea una radice staccata, mentre
`Scope::inherit()` conserva la gerarchia. Sono implementati anche channel
bufferizzati/rendezvous, FutureState e le catene `Future::map/catch/finally`.
I sei combinatori `await_all/any/first_success` lavorano su array di awaitable
e preservano chiavi, COW ed errori secondo il contratto upstream.
`protect()` mantiene la coroutine corrente e differisce la cancellazione
fino all'uscita dalla closure. [async-composition.php](examples/async-composition.php)
mostra selezione del primo risultato, risultati parziali e cleanup protetto.
Il [documento di compatibilità](docs/trueasync-compatibility.md) elenca i limiti.

## FFI, host e laboratorio

`FFI::cdef` risolve dichiarazioni C primitive, typedef e callback a runtime.
La modalità async è un'opzione della singola invocazione:

```php
$ffi = FFI::cdef('int abs(int value);', 'ucrtbase.dll');
echo $ffi->abs(-42, async: true, pool: 'blocking');
```

La coroutine riceve il risultato C dopo la sospensione. Il pool nominato crea
worker su richiesta e li riutilizza. `FFI::definePool('blocking', min: 0,
max: 1, queueCapacity: 32)` serializza le chiamate inviate a quel pool.
[ffi-async.php](examples/ffi-async.php)
mostra chiamate concorrenti e callback. CData e layout C completi restano aperti.

`Async\Mutex` offre `lock/tryLock/unlock` e `synchronized(closure)` con rilascio
in finally. `Async\ThreadChannel` fornisce code protette fra thread reali per
scalari e capsule; i grafi PHP attendono la promozione LOCAL→SHARED.
[threading-ffi.md](docs/threading-ffi.md) descrive API, ownership e limiti.

`ffi_call`, `ffi_call_async` e `ffi_callback` usano NFI/libffi con firme risolte
a runtime. Le callback possono essere closure; quelle provenienti da worker
vengono inoltrate al dispatcher della richiesta. Un thread C esterno deve
essere collegato al contesto NFI: [native-callback.php](examples/native-callback.php)
mostra il contratto ENV e lo shim esegue attach/detach. La native call va
eseguita in offload per permettere al dispatcher di rispondere.

`host_call` invoca direttamente un binding Polyglot host nello stesso processo.
Le funzioni globali `parallel`, `shared_counter/add/get`, `sleep_ms` e
`context_get/set` restano API del laboratorio, non il contratto pubblico TrueAsync.
Il vecchio `parallel` crea richieste separate e non implementa il target
`spawn_thread`: quest'ultimo deve condividere runtime e heap, come da design.
I worker del laboratorio accettano scalari, SharedCounter, mutex e ThreadChannel;
non condividono ancora grafi PHP.

I pool impliciti usano `GRAALPHP_WORKERS` thread
(default 4) e `GRAALPHP_WORK_QUEUE` posti in coda (default 256); quelli espliciti
usano i limiti di `definePool`. `GRAALPHP_TIMEOUT_MS` configura la deadline
della richiesta (default 30000), controllata anche nei loop PHP. La cancellazione
non garantisce di interrompere una funzione C bloccata.

Il bundle riusa [php-xmake](https://github.com/mefistofelix/php-xmake), commit
`f6526d592bdcedf8b43fb2b5f2d834df058ef713`: SQLite 3.53.2, PCRE2 10.44,
zlib 1.3.2 e libuv 1.52.1. Root `xmake.lua` adatta le ricette a Windows e Linux,
con fetch nei target e materializzazione negli input, usando Xmake su entrambi.
`FFI::cdef(..., 'builtin:sqlite3')` risolve il catalogo statico del runtime;
[native-pools.php](examples/native-pools.php) mostra callback e pool seriale.
Il bundle non implementa automaticamente PDO, preg o l'ABI Zend.

Il provider curl-impersonate 2.2.2 aggiunge curl 8.21.0/BoringSSL, nghttp2,
Brotli e zstd con ricette adattate da php-xmake. È affiancato da **cURL standard
8.22.0**, selezionabile con `GRAALPHP_CURL_PROVIDER=standard` oppure per handle.
Entrambi sono collegati staticamente: [provider e test cURL](docs/curl-providers.md).
[Memoria e prestazioni cURL da avvio a freddo](docs/curl-memory.md):
nessun warmup escluso; rilascio anticipato dei compilatori JIT inattivi.
I seguenti confronti conservano invece il metodo storico con warmup:
[Confronto prestazioni cURL standard con TrueAsync](docs/curl-runtime-costs.md):
carico diretto, 64 coroutine attive, 1.000/10.000 sospese, CPU e memoria separate.
[Percentili p50/p90/p95/p99 e RAM sotto carico](docs/curl-latency-memory.md),
con misurazione separata del costo del cronometro.
Sono disponibili il
sottoinsieme cURL, l'estensione `curl_impersonate` e SQLite3 con statement
parametrizzati descritti in [network-integration.md](docs/network-integration.md).
L'[esempio server](examples/websocket-sqlite.php) usa le API
`TrueAsync\HttpServer` e `TrueAsync\WebSocket` sul trasporto TCP libuv.

`EmbeddedPhp` permette a un dispatcher host di eseguire porzioni di PHP e
ricevere notifiche di lavoro. Con `GRAALPHP_REACTOR=libuv`, il backend iniziale
gestisce delay, wakeup e cancellazione senza polling periodico. Il server
usa libuv anche senza questa variabile. cURL usa multi/socket nello stesso
reactor anche attraverso l'API PHP esplicita `curl_multi_*`, con limiti di
connessione e attese cancellabili separatamente dai trasferimenti.
Compose resta una fase successiva. Contratto in
[reactor.md](docs/reactor.md); build in [native-build.md](docs/native-build.md).

Scheduler PHP, libuv e callback di rete condividono il thread proprietario.
Il server non crea un thread libuv separato. Nell'adattatore host resta un
helper per attendere IOCP/epoll e notificare il dispatcher; tutte le callback
vengono eseguite sul dispatcher. La patch Electron per le notifiche degli
eventi è fissata e applicata da Xmake. I pool FFI per C bloccante restano separati.

## Reload e verifiche

```bat
build\graalphp.exe --watch examples\compat.php
```

Invio avvia una nuova richiesta. Il watcher aggiorna le unità interessate dagli
eventi e pubblica solo batch validi; le richieste già avviate conservano la
generazione iniziale. Non vengono fatte scansioni nelle richieste.
`GRAALPHP_ROOT` e `GRAALPHP_WATCH=1` configurano l'embedding.

Passano **73 scenari integrati Windows**, **67 Linux**, **53 scenari semantici**
e **82 confronti TrueAsync** su ciascun eseguibile nativo, inclusi **42 PHPT
upstream**. Le build Native Image mostrano compilazione guest Tier 1 e Tier 2;
sono verifiche funzionali, non benchmark di prestazioni.

Il percorso WebSocket/curl-impersonate/SQLite supera **50 controlli** su JVM
e Native Image in entrambi i sistemi: 16 client concorrenti, TLS osservato
sulla rete, cancellazione/riuso cURL, 67 righe persistenti e riapertura del
database in un altro processo. Il multi pubblico aggiunge sei risultati
persistenti, limiti di connessioni, cancellazione delle sole attese e riuso.
Il carico raggiunge sedici trasferimenti HTTPS
contemporanei senza occupare un worker per trasferimento.

Dimensioni correnti: Windows **50,08 MiB**, Linux **51,50 MiB**, DLL native
Windows **4,71 MiB**. Dettagli e condizioni in [validation.md](docs/validation.md).
`build.bat benchmark` misura il value model Java del laboratorio.

`src/` contiene i sorgenti, `tests/` i test, `examples/` gli esempi.
`tools/` e `build/` sono cache/output rigenerabili. I documenti di design
originali restano la specifica; grammatica PHP completa, Composer, intera API
TrueAsync, grafi condivisi, cdef/CData, standard library, Compose e mobile
richiedono ancora lavoro.
L'ordine di completamento richiesto dà precedenza al runtime e alla compatibilità;
Compose e i target mobili vengono affrontati per ultimi.
