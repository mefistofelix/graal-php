# WebSocket, curl-impersonate e SQLite

Lo scenario completo è in `examples/websocket-sqlite.php`. Un messaggio di
testo WebSocket avvia una richiesta HTTPS con profilo Chrome 136, salva
messaggio, corpo remoto e status HTTP in SQLite con parametri associati,
quindi risponde al client con l'identificativo della riga. Le scritture
usano transazioni e un mutex PHP; le chiamate SQLite usano un pool seriale.
I messaggi binari vengono restituiti senza conversioni o perdita di byte.

## Esecuzione riproducibile

Verifica del 25 settembre 2026: **50 controlli superati** su Windows x64
e Ubuntu x64, sia su JVM sia negli eseguibili Native Image.
Su Windows passano anche usando una copia isolata dell'eseguibile e delle
tre DLL MSVC, senza bridge GraalPHP e senza JVM nel percorso del server.

```bat
build.bat network-test
build.bat network-test native
```

```sh
bash build.sh network-test
bash build.sh network-test native
```

La variante `native` rigenera prima l'eseguibile. Il test crea sotto
`build/network-*` un certificato locale temporaneo, database, log server,
log di riapertura e un rapporto dei ClientHello. Non contatta servizi Internet
durante il test e non modifica gli archivi dei certificati del sistema.
I download delle dipendenze appartengono al setup.

Il client WebSocket è indipendente: usa `java.net.http.WebSocket`; i casi di
protocollo usano inoltre un client TCP che costruisce i frame. Un server
HTTPS JDK e un proxy TCP di osservazione completano la fixture. Il runtime
sotto test gira in un processo separato, sulla JVM oppure come Native Image.
Questo non aggiunge un'API client WebSocket PHP.

Il test verifica:

- 16 client concorrenti, 64 messaggi nel carico parallelo e 67 righe totali;
  ogni risposta corrisponde al messaggio inviato e al corpo HTTPS ricevuto.
- Almeno otto trasferimenti HTTPS trattenuti da una barriera nel server remoto:
  HTTP e ping WebSocket continuano ad avanzare prima del loro rilascio.
- Cancellazione dopo l'arrivo della richiesta al peer HTTPS e riuso dello
  stesso CurlHandle prima che il peer venga sbloccato; errore immediato
  senza socket, riuso dopo errore e identità dell'handle dopo `curl_close`.
- Frammentazione UTF-8, frame oltre 65535 byte, frammentazione binaria con
- Multi esplicito con sei risposte HTTPS salvate in SQLite e un errore:
  limite nativo di due connessioni, identità e ordine dei risultati,
  contenuto, metadati, riuso del multi dopo close.
- Due trasferimenti multi trattenuti dal peer: cancellazione di una sola
  attesa senza fermare l'altra coroutine, rimozione e riuso immediato
  dell'easy handle, completamento del trasferimento superstite e chiusura
  del multi con attese pendenti.
- Frammentazione UTF-8, frame oltre 65535 byte, frammentazione binaria con
  NUL e byte non UTF-8, ping/pong anche fra frammenti.
- Lettura concorrente rifiutata, cancellazione della lettura pendente,
  close handshake e disconnessione TCP improvvisa.
- Frame non mascherati, continuazioni invalide, testo UTF-8 invalido,
  dimensioni eccessive e upgrade malformati.
- Verifica TLS attiva: il certificato locale è accettato solo con la CA
  esplicita; senza CA la richiesta fallisce. Una risposta lenta supera il
  timeout configurato.
- Header Chrome, sequenza di cipher suite e GREASE osservati nel ClientHello,
  con fingerprint distinta dal trasferimento senza impersonation.
- Parametri SQL contenenti apici e testo simile a SQL, rollback, testo vuoto,
  BLOB, interi a 64 bit, float e NULL; riapertura in un nuovo processo.
- Arresto del server con una connessione inattiva ancora aperta, scadenza
  del drenaggio, uscita del processo e presenza del database SQLite su disco.

Il controllo TLS non certifica l'intera fingerprint di un browser: non
verifica equivalenza HTTP/2, HTTP/3, tutte le estensioni TLS o tutti i profili.
I tempi riportati descrivono questo carico locale; non sono un benchmark
di produzione o una misura della stabilità su esecuzioni prolungate.

## API e confini implementati

I nomi e le firme server provengono dagli stub `true-async/server` consultati
nell'overlay di php-xmake: `HttpServerConfig`, `HttpServer`, `HttpRequest`,
`HttpResponse`, `WebSocket` e `WebSocketMessage`, nel namespace `TrueAsync`.
L'esempio usa `recv()`; l'iterazione `foreach ($ws ...)` non è ancora supportata.
I timeout dei setter TrueAsync sono in **secondi**, quelli cURL con suffisso
`_MS` in millisecondi.

Il server implementa un listener TCP IPv4/IPv6 con HTTP/1.1, upgrade RFC 6455,
coroutine per connessione, una sola lettura WebSocket e scritture ordinate.
Non sono implementati listener TLS, HTTP/2/3 server, compressione WebSocket,
subprotocol negotiation, rooms o worker PHP con grafi condivisi. Le risposte
HTTP chiudono la connessione; i corpi request chunked sono rifiutati. Limiti
attuali: header 32 KiB, corpo HTTP 1 MiB, buffer di scrittura 16 MiB; il limite
WebSocket è configurabile (1 MiB predefinito). Le operazioni assenti generano
un errore, senza simulare il supporto.

`curl_init/setopt/setopt_array/exec/getinfo/errno/error/close/version` coprono
le opzioni dichiarate in `CurlApi.CONSTANTS`. `curl_impersonate($handle,
$profile, $defaultHeaders = true)` è un'estensione GraalPHP che chiama la
vera `curl_easy_impersonate`. Il provider unico è curl-impersonate 2.2.2:
senza profilo esplicito esegue normali trasferimenti libcurl. La build
include HTTP/1.1 e HTTP/2 client; HTTP/3 non è abilitato. Ogni risposta è
limitata a 16 MiB. Le callback PHP cURL restano aperte.

`curl_exec` sospende la coroutine e usa `curl_multi_socket_action` nel reactor
libuv, con socket readiness e timer one-shot. Non usa il pool FFI `curl`;
eventuali pool così nominati rimangono disponibili alle chiamate FFI esplicite.
La cancellazione rimuove l'easy handle sul thread I/O e ne attende il distacco.
Il test richiede almeno otto trasferimenti contemporaneamente in attesa e
osserva un picco di sedici, superando il precedente limite di quattro worker.
Impostare comunque timeout cURL adatti alle richieste.

Come in PHP 8, `curl_close` conserva l'identità utilizzabile dell'oggetto.
Il rilascio nativo segue i riferimenti PHP, incluse alias, closure e array,
con cleanup di riserva alla chiusura della richiesta. Il warning di
deprecazione introdotto da PHP 8.5 non è ancora emesso.

L'API `curl_multi_*` comprende init, add_handle, remove_handle, exec, select,
info_read, getcontent, get_handles, close, errno, strerror e setopt.
Usa un CURLM distinto per oggetto.
`exec` aggiorna il conteggio per riferimento; `info_read` restituisce gli
stessi CurlHandle e modifica il conteggio della coda solo quando trova un
risultato. Gli handle completati restano associati fino a remove/close.
`curl_multi_close` rimuove gli easy handle e lascia riutilizzabile il multi.
L'esempio mantenuto è `examples/curl-multi.php`, caricato dal server.

La cancellazione di `curl_multi_select` rimuove soltanto il suo waiter e
attende la conferma nativa: i trasferimenti e le altre attese proseguono.
I comandi che modificano lo stesso multi sono serializzati da un mutex
coroutine e completano il bookkeeping prima di consegnare la cancellazione.
I deadline select sono one-shot; non introducono un tick nel reactor.

`setopt` copre le otto opzioni numeriche dichiarate in
`CurlMultiApi.CONSTANTS`, inclusi limiti di connessioni e stream. Restano
assenti callback PHP e server push. Per riconfigurare un easy handle occorre
prima rimuoverlo dal multi; PHP permette più casi di riconfigurazione.
Senza RETURNTRANSFER l'output viene consegnato al successivo exec/info_read
dopo il completamento, non in streaming durante i callback C.

`SQLite3` espone open/close, exec, prepare, query, querySingle scalare,
busyTimeout, changes e lastInsertRowID. `SQLite3Stmt` espone bindValue,
execute, clear e close; `SQLite3Result` espone fetchArray e finalize.
Il pool `sqlite` ha un worker predefinito, configurabile prima dell'uso con
`FFI::definePool`. La serializzazione delle singole chiamate non rende
atomica una sequenza di chiamate: l'esempio usa `Async\Mutex` intorno alla
transazione. Le API complete SQLite3/PDO non sono ancora implementate.

## Implementazione

Libuv esegue listen/accept/read/write/close sul thread proprietario PHP.
Comandi, buffer nativi copiati e completamenti attraversano il confine NFI,
senza coda verso un secondo thread. Non si
esegue PHP nel callback C. Le letture partono su richiesta; le scritture
conservano il buffer fino al callback libuv e sospendono il chiamante fino
al completamento. Deadline reali usano timer one-shot cancellabili.

Le stringhe UTF-8 conservano il percorso Java String; i dati non UTF-8
usano uno storage di byte immutabile, preservato da WebSocket, cURL, SQLite,
concatenazione, strlen, hex2bin/bin2hex e output. Questo non completa ancora
ogni coercizione, funzione e uso delle stringhe binarie come chiavi PHP.

SQLite e cURL rimangono collegati staticamente nel prodotto nativo. La JVM
usa il bridge DLL/SO. Ricette, provenienza e differenze rispetto al bundle
php-xmake sono descritte in `native-build.md`.
