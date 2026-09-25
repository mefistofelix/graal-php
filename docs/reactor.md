# Cooperazione tra scheduler PHP e loop host

Il runtime separa l'avanzamento del lavoro dalla sua attesa. Lo scheduler
espone `pump(root, budget)` senza attese bloccanti, una notifica di lavoro e
la prossima deadline effettiva. In modalità CLI, quando libuv è attivo, il driver
attende nel suo backend; senza I/O nativo attende sulla coda dello scheduler.
In embedding il dispatcher host esegue porzioni finite di PHP. Le attese
non usano un tick periodico da 16/50 ms. Restano timer per scadenze reali:
`Async\delay`, timeout e budget di esecuzione/cleanup.

`EmbeddedPhp` è l'adattatore Java per un loop esterno. Il suo `Driver` richiede:

```java
void post(Runnable work); // accoda sul thread proprietario, mai inline
Alarm schedule(long delayNanos, Runnable wakeup); // one-shot cancellabile
```

La costruzione e tutti i pump avvengono sullo stesso thread. Le notifiche
provenienti da worker sono accorpate e chiedono soltanto un `post`: non
eseguono PHP sul thread notificante. Il timer host viene armato alla prossima
deadline, cancellato quando cambia, e riarmato anche se il driver lo segnala
in anticipo. Completamento e cancellazione della sessione sono asincroni.
Il driver deve restare attivo fino alla chiusura della richiesta.

In questa modalità i loop PHP cedono al dispatcher ogni 128 checkpoint;
ogni pump esegue al massimo otto azioni. Questo consente al loop UI di servire
il proprio lavoro anche con PHP CPU-bound. Non è preemption arbitraria:
una chiamata C sincrona, una lunga operazione senza checkpoint o il cleanup
di una chiamata C bloccata possono occupare il thread. Usare l'offload FFI
per le chiamate bloccanti. Le callback del percorso legacy NFI non possono
sospendersi; `FFI::cdef` usa invece il [bridge con stack C separati](native-stack-bridge.md)
per le callback sospendibili, entro i confini controllati dal bridge.

## Backend libuv sul thread PHP

Con `GRAALPHP_REACTOR=libuv`, i delay positivi usano il bridge C. Socket e cURL
attivano il backend anche senza questa variabile. `uv_run`, comandi,
cancellazioni e completamenti nativi vengono eseguiti sul thread proprietario
PHP. Il server CLI non crea un thread libuv. Le chiamate del proprietario
eseguono direttamente il comando C, senza coda nativa, mutex di invio o
`uv_async_send`. La mappa dei trasferimenti pendenti è confinata al proprietario.

Ogni pump alterna una porzione di lavoro PHP con `UV_RUN_NOWAIT`. Quando non
ci sono azioni PHP pronte, il driver CLI usa `UV_RUN_ONCE`, con un timer
one-shot per la prossima deadline dello scheduler. Gli eventi I/O e i timer
libuv possono svegliarlo prima. I completamenti dei worker esterni accodano
lavoro Java e chiamano `uv_async_send`; il proprietario non emette questo
risveglio per le proprie operazioni. Le code Java pubblicano i dati anche con
libuv 1.52.1, senza presumere le garanzie aggiunte nella versione 1.53.

I completamenti C risolvono future interni e accodano le continuazioni PHP:
non richiamano codice guest dal callback libuv. L'affinità dei completamenti
è controllata anche a runtime. Il puntatore NFI al callback viene
trattenuto esplicitamente; alla chiusura si cancellano i timer, si chiudono
gli handle, si drena il loop e infine si rilascia il callback. I pool C devono
terminare prima di liberare le radici della richiesta. La cancellazione della
coroutine inoltra anche la cancellazione del timer nativo.

La FIFO locale è un `ArrayDeque` posseduto dal thread PHP. Solo le notifiche
dei worker attraversano una `LinkedBlockingQueue`, drenata anche durante i
pump con PHP pronto; l'attesa CLI senza libuv usa quella inbox bloccante.
Le deadline host considerano entrambe le code, evitando di parcheggiare il
dispatcher quando è arrivato lavoro esterno. Il conteggio degli scope include
i discendenti ed è aggiornato agli eventi di spawn/completamento. Un conteggio
di richiesta separato comprende anche gli scope indipendenti. Il completamento
di un worker aggiorna questi conteggi sul proprietario prima che la richiesta
possa essere considerata terminata.

Il backend copre timer, wakeup, cancellazione, shutdown e socket TCP server
(listen/accept/read/write/close). HTTP/1.1 e framing WebSocket usano questi
socket; letture a richiesta, scritture ordinate e deadline one-shot non
introducono un tick periodico. Vedere [network-integration.md](network-integration.md).

cURL usa un `CURLM` interno per `curl_exec` e uno per ogni
`CurlMultiHandle` pubblico. `CURLMOPT_SOCKETFUNCTION` registra e aggiorna
i watcher `uv_poll_t`; `CURLMOPT_TIMERFUNCTION` arma un solo timer non ripetuto.
Socket pronti e scadenze chiamano `curl_multi_socket_action`, poi drenano
`CURLMSG_DONE`. Anche l'avvio drena subito i risultati: URL non supportati
possono fallire senza creare alcun evento successivo. Non c'è un worker
occupato per ogni trasferimento, né un tick aggiunto dall'adattatore.

Questo segue il modello di `ext/curl/curl_async.c` di TrueAsync, consultato
nel checkout php-xmake. La cancellazione esegue il distacco dell'easy handle
sullo stesso thread; il finally protetto verifica il completamento prima di consentire
riuso o rilascio. Lo shutdown rimuove i trasferimenti, chiude il multi e i suoi
watcher prima di liberare il callback NFI. Tutti gli oggetti PHP restano sul
dispatcher, mentre buffer e handle libcurl restano nativi.

La risoluzione DNS interna usa il resolver a thread di libcurl anche su Linux
(`HAVE_THREADS_POSIX` e `USE_RESOLV_THREADED`). Questo lavoro interno è distinto
dall'offload di interi trasferimenti. I provider fissati sono cURL standard
8.22.0 e curl-impersonate 2.2.2 con curl 8.21.0; non serve il workaround
TrueAsync specifico della regressione DNS di 8.20.x. Vedere [provider cURL](curl-providers.md).
L'API PHP esplicita `curl_multi_*` usa lo stesso thread PHP/libuv. I risultati
DONE rimangono nella coda pubblica e gli easy handle rimangono associati
fino alla rimozione esplicita. Ogni select possiede un waiter e un deadline
one-shot separati: cancellare il waiter non cancella il trasferimento.
Remove, close e shutdown notificano le attese rimaste senza callback PHP
sul thread nativo. Le letture del contenuto e dei metadati di handle
associati usano lo stesso dispatcher C, eseguito direttamente dal proprietario.

Il multi trattiene i riferimenti guest agli easy handle; le membership
native hanno un proprio riferimento C, così la raccolta dei cicli può
visitare gli oggetti in qualsiasi ordine. La distruzione guest distacca il
multi sincronicamente sul proprietario; non aspetta la risposta remota.
L'API DNS generica resta aperta.

Riferimenti: [multi/socket libcurl con libuv](https://curl.se/libcurl/c/multi-uv.html),
[contratto dei timer](https://curl.se/libcurl/c/CURLMOPT_TIMERFUNCTION.html)
e [lifetime dei poll handle](https://docs.libuv.org/en/v1.x/poll.html).

## Relazione con Slint e futuri loop GUI

L'[articolo Slint](https://slint.dev/blog/slint-and-the-nodejs-event-loop)
descrive integrazioni senza polling periodico. La
[patch Electron fissata](../src/native/patches/README.md)
aggiunge `UV_LOOP_INTERRUPT_ON_IO_CHANGE` per notificare modifiche agli handle
al driver esterno. Xmake applica la patch a libuv 1.52.1 prima della compilazione
statica, su Windows e Linux, includendo `uv_loop_interrupt_suspend/resume`.
L'opzione è abilitata soltanto in embedding.

Il driver host mantiene un helper che attende soltanto la readiness del backend:
IOCP su Windows, epoll su Linux. Non chiama `uv_run` e non esegue callback
libuv o PHP. Al risveglio chiede un `post` al dispatcher host. Un handoff con
semafori parcheggia l'helper prima che il proprietario consumi gli eventi,
poi gli pubblica il nuovo timeout quando il pump termina. Un nuovo evento
o una modifica agli handle interrompe l'attesa; non esiste un timeout periodico.
Durante il pump, con helper parcheggiato, gli interrupt della patch vengono
sospesi per evitare risvegli inutili. I risvegli espliciti dai worker restano attivi.

Su Windows l'helper reinserisce in IOCP le completion lette, prima di cedere
il controllo al proprietario, come nel modello Electron. Lo shim C accede
alla struttura della stessa libuv compilata con Xmake: non usa offset
indovinati da Java. `uv_backend_fd` non viene usato come se esponesse IOCP.
Alla chiusura si interrompe l'helper, si attende la sua uscita e si drena libuv
sul proprietario prima di liberare callback, semafori e loop.

Il contratto `EmbeddedPhp.Driver` resta `post` più timer one-shot. Il test usa
un dispatcher host reale a thread singolo, socket TCP, timer e worker FFI;
Compose/Skia e il frame clock non sono ancora integrati. Tutta l'esecuzione
PHP/libuv condivide quel dispatcher; l'helper di attesa esiste solo in embedding.
