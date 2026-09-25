# Threading e FFI: contratto e stato

Il target resta un solo runtime PHP/Truffle con heap managed condiviso.
`Async\spawn_thread` dovrà pubblicare i grafi effettivamente condivisi attraverso
la barriera LOCAL→SHARED, mantenendo identità degli oggetti, reference e COW.
La copia dei valori in un'altra richiesta, usata dal vecchio laboratorio
`parallel`, non è l'implementazione di questo contratto.

Il runtime deve preservare l'integrità di storage, ownership e GC anche sotto
race. L'utente deve sincronizzare le operazioni della propria applicazione:
non sono previsti lock impliciti intorno a ogni accesso PHP.

## Primitive esplicite

`Async\Mutex` è un'estensione del runtime; non viene presentata come una classe
già disponibile nel binario TrueAsync di riferimento.

```php
$mutex = new Async\Mutex;
$mutex->lock();
try {
    // Sezione critica, anche attraverso sospensioni della coroutine.
} finally {
    $mutex->unlock();
}

$result = $mutex->synchronized(function() {
    return aggiornaStato();
});
```

Il proprietario è il task logico, non il thread del sistema operativo.
L'attesa FIFO sospende la coroutine senza bloccare il suo dispatcher.
`tryLock()` non attende; `isLocked()` espone lo stato. Solo il proprietario può
sbloccare; il mutex non è ricorsivo. `lock($cancellation)` accetta un token
TrueAsync. Una cancellazione dopo la prenotazione ma prima della ripresa
restituisce il lock alla coda. `synchronized()` esegue il rilascio in `finally`.
La forma a blocco nel parser non è ancora introdotta.

`Async\ThreadChannel` segue i nomi dello stub TrueAsync: costruttore con capacità
predefinita 16 e minima 1, `send`, `recv`, `close`, `capacity`, `count`,
`isClosed`, `isEmpty`, `isFull`. Le code sono protette tra thread reali;
backpressure e ricezione sospendono il task. Dopo close i valori bufferizzati
restano leggibili; poi viene sollevata `ThreadChannelException`.

Al momento sono trasferibili scalari e capsule esplicitamente condivisibili
(`SharedCounter`, mutex e ThreadChannel). Array, oggetti e closure PHP sono
rifiutati finché non esiste la barriera sui grafi. Questa restrizione temporanea
non cambia il target in copia profonda o runtime separati. Il test con due
dispatcher usa ancora `parallel` solo come infrastruttura di laboratorio;
`spawn_thread` pubblico con heap condiviso rimane da implementare.

## FFI con modalità di invocazione

```php
$ffi = FFI::cdef('int abs(int value);', 'ucrtbase.dll');
echo $ffi->abs(-42);
echo $ffi->abs(-42, async: true, pool: 'blocking');
```

Su Linux l'esempio può usare `libc.so.6`. `async` è booleano e predefinito false;
`pool` è una stringa non vuota, predefinita `ffi`. Sono opzioni riservate
dell'invocazione; eventuali parametri C con questi nomi vanno passati per
posizione. Il risultato PHP è il normale risultato C: in modalità async il
dispatcher continua a lavorare mentre la coroutine chiamante si sospende.

Ogni nome individua un pool della richiesta, configurabile prima dell'uso:

```php
FFI::definePool('sqlite', min: 0, max: 1, queueCapacity: 32);
FFI::definePool('blocking', min: 2, max: 8, queueCapacity: 256);
echo FFI::poolSize('blocking'); // 2 worker già avviati
```

`min` avvia subito quel numero di worker; con zero la prima chiamata async
li crea su richiesta. I worker liberi vengono riutilizzati; il numero cresce
con il lavoro pendente fino a `max`. Con `max: 1` le chiamate inviate allo
stesso pool sono serializzate FIFO sullo stesso thread. Chiamate sincrone o
inviate ad altri pool non partecipano a quella serializzazione.
La configurazione è immutabile: ridefinirla identica è idempotente, cambiarla
solleva `ValueError`. `poolSize` richiede un pool esistente e non lo crea.
I limiti richiedono `0 <= min <= max <= 65536` e una capacità tra 1 e 65536;
la saturazione della coda solleva un errore esplicito.
Per pool impliciti restano i default `GRAALPHP_WORKERS` (4) e
`GRAALPHP_WORK_QUEUE` (256). `definePool` usa invece i suoi default 0/4/256.
I worker restano disponibili fino alla chiusura della richiesta. Il riuso
dei pool fra richieste a livello ProcessContext è ancora da completare.

La cancellazione rimuove logicamente il lavoro non iniziato e richiede
l'interruzione dei worker attivi. Non forza il ritorno di una funzione C.
Il completamento fisico di una chiamata iniziata resta tracciato anche quando
la coroutine viene cancellata. Alla chiusura vengono attesi prima i pool, poi
liberati callback e radici PHP. Se un worker supera il limite di chiusura, il
runtime segnala l'errore e conserva quelle radici.

`FFI::cdef` analizza dichiarazioni C a runtime senza compilazione aggiuntiva:
tipi numerici primitivi, alias typedef, parametri nominati o anonimi, input
`char*` tramite stringa, callback tramite puntatori a funzione e relativi
typedef. Il modello dei tipi resta separato dal binding NFI. I simboli vengono
risolti e memorizzati nella cache. I puntatori restituiti, CData, layout di
struct/union, variadici C e buffer nativi posseduti non sono implementati:
le dichiarazioni fuori dal sottoinsieme sono rifiutate.

Le callback `FFI::cdef` possono sospendersi: il bridge conserva lo stack C e
le esegue sulla stessa coroutine PHP, anche per una chiamata `async: true`.
Le closure e gli argomenti vengono posseduti fino al ritorno C e poi rilasciati.
Il drain dopo errore/cancellazione completa normalmente la chiamata C usando
risposte zero/void alle callback residue. Le callback hanno durata limitata
alla chiamata e devono provenire dal suo thread C. Vedere
[native-stack-bridge.md](native-stack-bridge.md) per il contratto e i limiti.

Il percorso legacy `ffi_call`/`ffi_callback` conserva invece le closure fino
alla chiusura della richiesta, esegue PHP sincrono sul dispatcher e richiede
attach/detach NFI dai thread C esterni. Nessuno dei due percorsi costituisce
un'API per registrazioni arbitrarie persistenti in C. Conservare la closure PHP
non sostituisce un riferimento nativo al trampoline. Il bridge interno libuv usa esplicitamente `newClosureRef` e
`releaseClosureRef` e rilascia il riferimento solo dopo il drenaggio del loop.

## Librerie esterne e built-in

Le DLL/SO esterne continuano a essere caricate per percorso tramite NFI.
`builtin:sqlite3`, `builtin:zlib`, `builtin:pcre2`, `builtin:libuv` e
`builtin:runtime` e `builtin:curl` risolvono invece un catalogo esplicito di simboli. In Native
Image gli indirizzi provengono dagli archivi collegati nell'eseguibile; su JVM
lo stesso catalogo è nel bridge DLL/SO. Non servono una DLL SQLite separata o
un `dlopen` dell'eseguibile. Un simbolo assente viene rifiutato.

Il catalogo è in `src/native/shim.c`: include versioni, wrapper scalari,
il ponte reactor e gli adattatori SQLite/cURL. Le API PHP SQLite3 e cURL
implementate sono descritte in [network-integration.md](network-integration.md).
Non espone ancora tutte le funzioni delle librerie né implementa PDO/preg.
Le ricette Windows/Linux sono in root `xmake.lua`; derivano da php-xmake e
compilano le dipendenze staticamente. `examples/native-pools.php` mostra SQLite
con callback e un pool seriale. Vedere anche `native-build.md` e `reactor.md`.

Gli argomenti nominati PHP sono implementati per funzioni, metodi,
costruttori e closure: ordine di valutazione, default, reference e chiavi dei
variadici vengono preservati. Sono supportati anche nei combinatori e nelle
principali funzioni di attesa TrueAsync, in FFI e nelle nuove primitive.
Gli altri builtin rifiutano esplicitamente i nomi non ancora supportati.
Unpacking e supporto completo a tutti i builtin restano aperti.
