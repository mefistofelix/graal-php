# Copertura del design — 26 settembre 2026

Per la ripresa operativa vedere [TODO](../TODO.md) e
[stato di consegna](handoff.md); per metodo e RAM,
[benchmark da freddo](curl-memory.md) e [indagine p99 del 26 settembre](curl-p99.md).
Il successivo blocco [autoload](autoload.md) aggiorna la copertura funzionale.
Le verifiche prestazionali sono ora rinviate per richiesta dell'utente;
le campagne restano distinte in [validation.md](validation.md).

Questa versione è un runtime eseguibile Windows e Linux, con target semantico PHP 8.6.
Scheduler PHP e callback libuv condividono il thread proprietario; la patch
Electron applicata da Xmake supporta l'helper di attesa IOCP/epoll del driver
host. Il server CLI attende direttamente in libuv senza quel thread helper.
Non è ancora una distribuzione PHP compatibile con applicazioni esistenti e non
chiude tutte le milestone M0–M10. Il frontend produce IR semantico; il solo backend
di esecuzione è già Truffle Bytecode DSL. Non è prevista una successiva trasposizione
da un interprete estraneo a Truffle.

| Area del design | Stato verificabile della prima versione | Lavoro ancora necessario |
| --- | --- | --- |
| Frontend / IR / Bytecode DSL (§3–4, 42) | Stessa pipeline per file/include/eval/reload; namespace e alias, classi, ereditarietà, proprietà, metodi, closure, arrow function, default, variadici e tipi semplici; argomenti nominati nelle chiamate utente; for/do/ternario/coalesce; dichiarazioni condizionali e costruzione dinamica; autoload personalizzato sospendibile anche per i padri | Interfacce, trait, enum, attributi, generatori/fiber PHP, binding/ereditarietà e typing completi, nomi in tutti i builtin, unpacking, loader SPL predefinito/include_path |
| Valori, COW, reference, l-value (§5–8, 40) | Heap per richiesta, array ordinati COW, reference, oggetti a identità e ambienti di closure nello stesso grafo; collezione cicli misti; append differito e LHS composto valutato una volta; byte binari preservati in rete, SQLite, concatenazione e output | Stringhe binarie in tutte le coercizioni e chiavi, destructor/weak reference PHP, return-by-reference nel frontend, reference a proprietà tipizzate, storage specializzato, coercizioni/diagnostica complete |
| Primitive / JIT (§46–48) | Bytecode specializzato per aritmetica long con overflow, eliminazione boxing abilitata | Slot locali primitivi, array packed, inline cache polimorfe, misure guest JIT rappresentative |
| Reload / generazioni (§9–10, 56) | WatchService ricorsivo, aggiornamento delle sole unità interessate, directory nuove, riconciliazione su overflow; pubblicazione atomica e batch non valido mantenuto pendente; richieste ancorate alla generazione iniziale, verificato anche quando il primo autoload avviene dopo una pubblicazione | Indice delle dipendenze/simboli, copertura completa rename/delete/overflow, grandi alberi, watcher per ogni filesystem; /mnt/c WSL non supera il test eventi |
| Include dinamici (§10.8) | Indice di codice distinto dall'esecuzione: include installa funzioni e usa il frame locale chiamante; include_once per richiesta; callback autoload con scope del loader e generazione fissata | Warning PHP esatti, include_path, nuovi file fuori dalla root, loader SPL predefinito |
| Continuazioni (§11–12) | Yield solo sulle sospensioni; metodi, costruttori, closure, catene Future e autoload conservano activation, ownership e finally | Generatori/fiber PHP, ulteriori interazioni con tutte le forme di controllo PHP |
| Scheduler / structured concurrency (§13–14, 50, 57) | API TrueAsync per coroutine/scope/channel/future; combinatori, protect e cancellazione; pump host con checkpoint, notifiche e deadline reali; libuv per timer, server TCP HTTP/WebSocket e cURL multi/socket con API PHP curl_multi_*, limiti e waiter cancellabili; SQLite in pool nominato | Traversable nei combinatori, policy errori/disposal, TaskGroup/TaskSet, lifetime guest dei future, API socket/DNS complete, callback PHP cURL, riconfigurazione degli easy associati, cancellazione C generica |
| Contesti logici (§15–16) | API TrueAsync: contesto condiviso per scope e gerarchico, contesto privato per coroutine, scope staccati; globals/heap senza ThreadLocal | Process state pubblico, lookup ottimizzato, SAPI e superglobali, promozione dei valori di contesto condivisi fra thread |
| Thread / LOCAL→SHARED (§17–21) | Worker reali nello stesso contesto Truffle; SharedCounter promosso ad AtomicLong; mutex FIFO con ownership del task e ThreadChannel per scalari/capsule, verificati fra thread fisici | spawn_thread pubblico nello stesso runtime/heap, promozione dei grafi PHP completi, array/oggetti condivisi, semaphore/RWLock/condition e modello race completo |
| FFI dinamica (§22–26, 49, 51–52) | NFI/libffi, cdef primitivo/typedef/callback, flag async; definePool con min/max/coda, max=1 seriale; stack C separati, callback sospendibili sulla stessa coroutine, argomenti posseduti e drain dopo errore/cancellazione; percorso legacy NFI | CData, puntatori/buffer/strutture, callback persistenti o da thread C esterni nel nuovo bridge, cancellazione specifica delle librerie, zero-copy |
| Librerie native / standard library (§27–30, 44–45) | Target php-xmake adattati a Windows/Linux, compresi curl-impersonate e cURL standard 8.22.0 con TLS isolata; bridge DLL/SO e collegamento statico in Native Image; sottoinsiemi cURL, SQLite3 e server TrueAsync verificati insieme | Catalogo completo, API PHP preg/PDO/streams e cURL/SQLite3 complete, Composer, altre librerie; nessuna ABI Zend supportata |
| Host / Polyglot (§31–32) | host_call in-process; EmbeddedPhp con post, timer one-shot e affinità al thread host, porzioni CPU e wakeup da libuv | Oggetti e array interop pubblici, binding GUI, catalogo reflection Native Image |
| Native standalone / JIT on-off (§33–34, 37, 39) | JAR e Native Image Windows/Linux x64; entrambe le build superano il corpus TrueAsync e mostrano compilazione guest Tier 1/2; modalità --interpreter | Profilo compatto, benchmark rappresentativi, riproducibilità e packaging completo |
| Linux static / macOS / mobile (§35–38) | Build Ubuntu WSL tramite SDKMAN; nove target Xmake Linux collegati staticamente, compresi i runtime C++ del prodotto; dipendenze dinamiche limitate a libc/libm/loader | Linux fully-static vs FFI, altre dipendenze php-xmake, macOS, Android, iOS e integrazione UI |
| Errori / shutdown / isolamento (§41, 54–55) | Eccezioni guest, cleanup ownership, separazione richieste verificata anche durante reload | Gerarchia completa Throwable/Error, warning/notice, shutdown hooks PHP, timeout di cleanup rigorosi |
| Tooling / benchmark (§53, 65–68) | Sorgenti bytecode, differenziale PHP 8.6 e TrueAsync 0.10.0; 42 PHPT upstream inclusi negli 82 casi; errori cURL immediati e alias; crash con exit code negativo rilevati dallo script Windows; 38 programmi autoload identici e test request/reload | Suite PHPT completa, debugger/strumentazione completa, benchmark rappresentativi runtime e FFI senza warmup escluso |
| Compose (documento dedicato) | Confine di chiamata managed in-process disponibile tramite Polyglot | Backend Compose/Skia, dispatcher UI condiviso, windowing, frame clock, binding generator, Android/iOS; nessuna UI già implementata |

## Contratti che evitano riscritture strutturali

Priorità di completamento: semantica PHP e autoload, API TrueAsync, threading
con heap condiviso, FFI e integrazione delle librerie C. Le feature linguistiche
usate da Composer hanno priorità; Composer come applicazione non la ha.
Compose e mobile vengono per ultimi. Il [contratto threading/FFI](threading-ffi.md)
precisa sincronizzazione manuale, pool nominati e stato della barriera sui grafi.

- `Execution.Unit` / `Function` contengono codice; `Request` e `Activation` contengono dati mutabili.
- Il parser non conosce frame o scheduler. Il lowering crea operazioni Truffle e punti di sospensione.
- Le locazioni risolvono la destinazione al momento dell'accesso; i riferimenti possiedono celle.
- Gli array temporanei sono owner espliciti, spostati tra activation/continuation/future e rilasciati alla chiusura.
- Il target spawn_thread condivide runtime e heap. Il vecchio parallel usa richieste separate e resta solo laboratorio; le capsule mutex/channel sono condivisibili, i grafi PHP non promossi sono rifiutati.
- NFI gestisce l'ABI. Il C shim contiene confini di allocazione/risorsa, senza strutture Zend o valori PHP incorporati.
- `host_call` ed `EmbeddedPhp` forniscono chiamate e avanzamento per il futuro embedding Kotlin/Compose, nello stesso processo e con affinità esplicita del dispatcher.

## Limiti operativi attuali

Il watcher aggiorna le unità indicate dagli eventi e scansiona le directory
nuove; inizializzazione e overflow richiedono riconciliazione. Le directory
escluse vengono saltate prima di attraversarle. Non fa scan nelle richieste.
La root viene precompilata: un file PHP
non supportato nella root blocca il primo indice o il successivo reload. Gli
include devono appartenere a tale root. Gli errori di reload mantengono l'ultima
generazione valida e sono osservabili con `reload_failed()`.

Lo scheduler attende i task e l'offload della richiesta; la deadline predefinita
è 30 secondi, configurabile con GRAALPHP_TIMEOUT_MS e controllata anche nei loop
PHP. Il cleanup ha un limite di 5 secondi. Non garantisce l'interruzione di
chiamate C bloccate. I pool configurati con FFI::definePool usano min/max/coda
espliciti; i pool impliciti usano GRAALPHP_WORKERS (default 4) e
GRAALPHP_WORK_QUEUE (default 256);
una coda piena produce un errore esplicito. Il codice PHP sincrono usa ancora lo stack Java tra
chiamate; solo la sospensione materializza la catena delle continuazioni.

Le callback `FFI::cdef` usano il bridge con stack C separati e possono sospendersi
sulla coroutine chiamante. I worker nominati restano assegnati alla chiamata C
fino al ritorno; le notifiche passano per future senza polling. Il timeout di
5 secondi e l'attach/detach NFI restano specifici del percorso legacy
`ffi_call`/`ffi_callback`, dove una sospensione produce un errore guest.
Il nuovo bridge richiede callback limitate alla chiamata e al suo thread C;
registrazioni persistenti e callback da altri thread restano aperte.

La compatibilità scalare è intenzionalmente parziale: equality/coercizioni,
precisione di stampa float, stringhe binarie, accessi a scalari, variabili non
definite e ulteriori casi di ordine di valutazione complesso
richiedono ulteriore lavoro differenziale. Non vengono dichiarati conformi solo
perché gli esempi semplici passano. La collezione dei cicli segue ownership
logica, anche per oggetti e closure; non implementa ancora destructor, weak
reference o la policy GC di PHP. I receiver temporanei delle proprietà restano
radicati nell'activation fino alla chiusura; serve ridurne il lifetime nei loop.

Le verifiche TrueAsync e le parti ancora non implementate dell'API sono elencate
in [trueasync-compatibility.md](trueasync-compatibility.md). I test di esempio
non chiudono la milestone Composer/framework, né dimostrano compatibilità
completa PHP 8.6. Il backend Compose e i target mobili restano da implementare.

L'accesso generico a locazioni/collezioni e il dispatch dinamico hanno confini
`TruffleBoundary`; l'aritmetica specializzata rimane compilabile. Questi confini
evitano inlining ricorsivo del modello generico e l'inclusione dello scheduler,
parser e filesystem nel guest JIT Native Image. Le future cache e gli slot
specializzati dovranno spostare i percorsi frequenti entro il codice compilato.
L'architettura JIT è quindi reale, ma non è ancora una dimostrazione delle
prestazioni o dell'assenza di overhead richieste dal design finale.

## Integrazione php-xmake

Upstream: [php-xmake](https://github.com/mefistofelix/php-xmake), commit
`f6526d592bdcedf8b43fb2b5f2d834df058ef713` per i primi quattro target e
`8c00ce25562f6dcdbffa42a1c335e3d71dc5d58b` per la catena curl-impersonate.
Root `xmake.lua` mantiene l'adattamento a Windows/Linux e
aggiunge lo shim dinamico e l'archivio per Native Image. Fetch e generazione
seguono i callback di php-xmake; versioni e opzioni comuni restano fissate.
Le regole e i manifest di origine sono documentati in `native-build.md`.

| Target adattato | Versione nella ricetta | Uso verificato |
| --- | --- | --- |
| sqlite3 | 3.53.2 | Query in-memory, file persistenti, parametri, transazioni, rollback e riapertura |
| pcre2 | 10.44 | Compilazione ed esecuzione regex |
| zlib | 1.3.2 | CRC32 |
| libuv | 1.52.1 | Timer, wakeup host, cancellazione, server TCP HTTP/WebSocket e drenaggio |
| cURL standard | 8.22.0 / BoringSSL non modificata | Provider separato statico, selezione per handle, stesso reactor; [test dedicati](curl-providers.md) |
| curl-impersonate | 2.2.2 / curl 8.21.0 | HTTPS con CA esplicita, profilo Chrome 136 osservato nel ClientHello, errori TLS e timeout |
| boringssl | 156c7b75ae9b8c3b3f847acf264f17594c3859fb | Backend TLS del provider cURL, implementazione generica senza assembly |
| impersonate-nghttp2 | 1.63.0 | Build e link nel client; scambio HTTP/2 non coperto dal test |
| brotli / zstd | 1.2.0 / 1.5.7 | Decoder compilati e collegati; corpi compressi non coperti dal test |

Le ricette upstream contengono inoltre altri provider TLS, HTTP/3, compression,
Unicode, XML/XSLT, database e librerie grafiche. Sono la base per estendere il
bundle; non sono tutte scaricate, collegate o esposte come estensioni PHP in
questa versione. NFI usa il proprio libffi incluso negli artefatti GraalVM.

Il percorso completo e i suoi limiti sono descritti in
[network-integration.md](network-integration.md).
