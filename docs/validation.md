# Verifica — 26 settembre 2026

Toolchain: Oracle GraalVM 25.4.4.1.1+1.1, Java 25.0.4.1.1, Truffle 25.4.4.1.1.
Oracoli: PHP 8.6.0RC2 e TrueAsync 0.10.0 / PHP 8.6.0-dev / ABI v0.26.0.

Il blocco funzionale corrente implementa [autoload personalizzato sospendibile](autoload.md)
e dichiarazioni runtime: **38/38 programmi su Windows/Linux, JVM/Native Image**,
per 152 confronti con input identici. Windows usa PHP 8.6.0RC2 per i casi sincroni
e TrueAsync per quelli asincroni; Linux usa il binario TrueAsync/PHP 8.6 per tutti
i casi, non un PHP stock separato. Passano 79/73 scenari integrati Windows/Linux,
53 scenari semantici e 256 grafi/1.280 fasi collector su ciascuna piattaforma;
82 confronti TrueAsync in tutte e quattro le combinazioni e 50 asserzioni
rete/TLS/WebSocket/cURL/SQLite su ciascun Native Image. I sei scenari di
integrazione nativeBundle condizionati alla DLL Windows non sono eseguiti su Linux.

Eseguibili ricostruiti in sequenza, prodotto Linux copiato e avviato dalla root;
87 file src/tests identici nelle due copie. L'esempio autoload passa anche in
modalità `--interpreter`. Le fixture di reload usano un filesystem temporaneo
nativo; nessuna nuova pretesa sul watcher /mnt/c. **Le performance sono rinviate
per richiesta dell'utente:** nessuna nuova campagna, né modifica dei default
JIT/GC. Suite FFI-bridge e cURL dedicate non rieseguite integralmente qui.
[Esiti](validation/autoload-2026-09-26/results.txt),
[hash](validation/autoload-2026-09-26/hashes.json),
[evidenze e metodo](validation/autoload-2026-09-26/README.md).

Il precedente blocco del 26 settembre ha verificato il [tooling e l'A/B p99 sullo stesso
eseguibile](curl-p99.md): 32 prove principali, 8 diagnostiche JIT/GC e 4 con
GC del peer, tutte passate. 5.767.168 richieste e 229.376 durate campionate;
26 controlli Java, 7 test Python e 6 prove brevi GraalPHP/TrueAsync passati.
RSS p50 −47,18%/−41,86%, p99 −2,41%/+10,42%; attribuzione causale ancora aperta.
Solo benchmark/report modificati, binari invariati; nessuna nuova verifica
funzionale completa o esecuzione Linux.
[Esiti](validation/curl-p99-2026-09-26/results.txt),
[hash](validation/curl-p99-2026-09-26/hashes.json).

La precedente campagna del 25 settembre misura [memoria e prestazioni cURL da freddo](curl-memory.md):
36/36 prove Windows, 4.718.592 richieste, nessun warmup escluso. Il nuovo default
CLI `engine.CompilerIdleDelay=500` riduce la RAM trattenuta dai compilatori JIT;
RSS p50 −46,2%/−38,5% con 1.000/10.000 sospese, p99 delle latenze +4,0%/+11,2%.
Suite cURL passata su Windows/Linux, JVM/Native Image; JIT Tier 1/2 verificato.
[Esiti](validation/curl-memory-2026-09-25/results.txt),
[hash](validation/curl-memory-2026-09-25/hashes.json). I risultati precedenti
restano storici e usavano un warmup separato.

La precedente campagna misura [percentili di latenza e RAM cURL](curl-latency-memory.md):
24/24 prove Windows, 3.145.728 richieste misurate, 98.304 durate campionate,
con controlli senza cronometro per quantificarne il costo. È stata aggiunta
`hrtime` per misurare il percorso PHP con un cronometro monotono.
La suite cURL passa su Windows/Linux, JVM/Native Image, e TrueAsync Windows.
[Esiti](validation/curl-latency-memory-2026-09-25/results.txt) e
[hash dei prodotti e dei sorgenti](validation/curl-latency-memory-2026-09-25/hashes.json).

La precedente verifica cURL passa su Windows/Linux, JVM/Native Image e TrueAsync
Windows, inclusi handle temporanei, risposte vuote/grandi e cancellazione prima
della sospensione. Le 24 prove finali misurano un ulteriore +9,4–9,6% di throughput
nell'A/B e −11,5–11,8% di CPU per richiesta; il divario allora misurato con TrueAsync è
10–11% nel throughput. Un controllo C e misure user/kernel separano il backend
dal costo del runtime.
[Esiti](validation/curl-runtime-costs-2026-09-25/results.txt),
[hash](validation/curl-runtime-costs-2026-09-25/hashes.json),
[metodo e risultati](curl-runtime-costs.md).

La [prima ottimizzazione](curl-optimization.md) aveva verificato anche 53 scenari
dei valori e 1.280 fasi di raccolta dei cicli su entrambe le piattaforme;
quei test non sono stati ripetuti in questa campagna cURL.

La tabella seguente conserva il registro delle verifiche precedenti del runtime;
questa modifica non ha rieseguito l'intera matrice. Il [confronto cURL iniziale](curl-performance.md)
e i suoi [esiti](validation/curl-standard-2026-09-25/results.txt) restano storici.

| Verifica | Risultato |
| --- | --- |
| Compilazione e discovery del linguaggio | Passate su Windows e Ubuntu x64; annotation processor Truffle attivo |
| Integrazione Windows/JVM | 73 scenari, inclusi pool min/max, serializzazione C, catalogo built-in, embedding, reactor sul thread PHP, stringhe binarie, lifetime multi e drenaggio di scope indipendenti con worker |
| Integrazione Linux/JVM | 67 scenari su filesystem Linux; i 6 casi con DLL Windows non si applicano |
| Modello dei valori | 53 scenari semantici, COW/reference e invarianti di lifetime |
| Grafi oggetto/array | Cicli misti reclamati; controlli aggiunti alla suite integrata |
| TrueAsync Windows | 82 programmi identici passati su JVM e Native Image |
| TrueAsync Linux | 82 programmi identici passati su JVM e Native Image; confronto con il binario Linux upstream |
| WebSocket → HTTPS → SQLite | 50 controlli su Windows/Linux, JVM/Native Image: 16 client concorrenti, 64 messaggi paralleli, 67 righe messages più 6 risultati multi e database riaperto da un altro processo |
| API curl_multi_* | Membership e alias, parametri per riferimento/nominati, ordine della coda, limiti reali di connessioni, cancellazione indipendente dei waiter, rimozione/close con riuso prima del rilascio remoto, raccolta di cicli |
| curl-impersonate | Profilo Chrome 136: User-Agent, ordine delle cipher suite e GREASE osservati nel ClientHello; confronto con trasferimento senza profilo; CA verificata e timeout |
| Avanzamento durante HTTPS | Almeno otto trasferimenti trattenuti da una barriera: HTTP e ping WebSocket avanzano prima del rilascio; picco osservato di sedici trasferimenti payload nel reactor |
| Cancellazione cURL | Il peer riceve una richiesta e rimane bloccato; cancellazione e riuso dello stesso easy handle terminano prima del rilascio della barriera |
| CurlHandle | Errori immediati senza socket, alias e riuso dopo errore; curl_close mantiene l'oggetto utilizzabile come PHP 8; rilascio nativo collegato all'ownership guest |
| Protocollo WebSocket | UTF-8 frammentato, messaggi da 70 KiB, binario con tutti i valori di byte, ping intercalati, close, disconnessione, cancellazione, errori di framing e limite messaggi |
| Prestazioni Windows prima del consolidamento | Confronto Native Image/TrueAsync: tre ripetizioni, 1.000/10.000 coroutine sospese e 64/256 client WS/cURL. 11/12 casi completi; un errore di connessione nella terza prova GraalPHP, non riprodotto in una ripetizione separata. Risultati e dati grezzi in [performance.md](performance.md) |
| Prestazioni dopo il consolidamento | Echo con 10.000 coroutine/64 client: mediana 18.190 → 12.305 msg/s, CPU 67,12 → 82,35 µs/msg. La serie A/B completa termina 6/12 casi, con tre errori di connessione per immagine e pressione sulle porte TCP; la ripetizione isolata passa 2/2 casi. Dati e limiti in [reactor-performance.md](reactor-performance.md) |
| Allineamento scheduler corrente | Contro la variante con collector già corretto, 10.000 coroutine/64 client: echo 12.295 → 12.994 msg/s, relay 5.500 → 5.536 msg/s; 8/8 prove, 585.378 messaggi. Intervalli sovrapposti e nessun guadagno stabile dimostrato sul relay. [Dati e limiti](trueasync-alignment.md) |
| SQLite persistente | Parametri con apici, rollback, stringa vuota, BLOB, int64, double e NULL; contenuto e formato file verificati dopo riapertura |
| PHPT upstream | 42 degli 82 casi provengono direttamente dalla sezione FILE di test del tag v0.10.0 |
| Mutex e ThreadChannel | Contesa tra coroutine, rilascio dopo grant/cancellazione, 2.000 aggiornamenti protetti fra thread host, backpressure e consegna fra worker PHP |
| FFI async | Typedef/callback, flag per invocazione, nomi distinti dei pool, riuso del worker libero, avanzamento del dispatcher e cancellazione con finally |
| Pool C configurabili | Minimo preparato, crescita on demand, massimo, configurazione immutabile; otto chiamate concorrenti serializzate sullo stesso worker con max=1 |
| Reactor/host | PHP e callback libuv sul dispatcher proprietario; in CLI nessun thread libuv; in embedding helper di sola attesa IOCP/epoll. HTTP, timer, avanzamento UI durante loop CPU PHP, risveglio da worker FFI, cancellazione/finally e shutdown; callback nativo conservato anche attraverso GC esplicito |
| Stack C: prova iniziale | Windows/Linux x64, JVM/Native Image: 12 stack C, 36 sospensioni e 36 Full GC per configurazione; protocollo esplicito. Superata dall'integrazione nell'API ordinaria descritta in [native-stack-bridge.md](native-stack-bridge.md) |
| Bridge FFI integrato | Windows/Linux x64, JVM/Native Image: callback annidate sulla stessa coroutine, ABI scalari, più callback, stringhe, void, pool seriale, cancellazione attiva/in coda, scope protetto, errori e callback SQLite; 80 Full GC ciascuno. Suite anche sui due eseguibili del prodotto e sul pacchetto Windows isolato. [Risultati e hash](validation/native-bridge-2026-09-25/results.txt) |
| Archivi Xmake | Nove dipendenze statiche su Windows/Linux più libffi del GraalVM fissato; bridge da cinque sorgenti: shim, reactor, services, curl_reactor e ffi_bridge; chiusura curl/BoringSSL compilata senza CMake |
| Built-in senza bridge (verifica precedente) | SQLite e callback su pool seriale con libuv eseguiti in directory senza il bridge DLL/SO, anche con guest JIT disabilitato; la nuova suite usa gli stessi lookup statici |
| Composizione asincrona | Sei combinatori su array, protect, motivi di cancellazione, token Future e guardie deadlock degli scope; esempio async-composition identico all'oracolo anche in modalità interprete |
| PHP generale | Esempio language con classi, closure, cicli e assegnazioni uguale all'oracolo; esempio compat preservato |
| Guest JIT (verifica precedente) | Tier 1 e Tier 2 osservati negli eseguibili Windows e Linux; risultato 599990000 |
| Modalità interprete (verifica precedente) | language/trueasync eseguiti con compilazione guest disabilitata |
| Native Windows senza JVM esterna (verifica precedente) | FFI async con callback e mutex, più esempio ThreadChannel, eseguiti con JAVA_HOME vuota e PATH limitato a Windows; FFI ripetuta in modalità interprete |
| Packaging Windows corrente | Gli stessi 50 controlli di rete passano con una copia dell'eseguibile e delle sole tre DLL MSVC, senza bridge, JAVA_HOME vuota e PATH limitato a Windows; il JDK serve al client di test indipendente |
| Native Linux | ELF eseguito in Ubuntu WSL; ldd mostra solo libc, libm e loader di sistema; le librerie del bundle sono statiche |
| FFI::cdef su Native Linux (verifica precedente) | Chiamate libc abs/strlen su due pool e labs con long a 64 bit: risultato 42:5:10000000000 |
| Build SDKMAN | Eseguita realmente su Ubuntu; zip estratto localmente dal pacchetto Ubuntu senza installazione globale |
| Watcher su Linux | Passa su filesystem Linux nativo; fallisce per gli eventi nella directory Windows montata in /mnt/c |
| macOS / Android / iOS | Non verificati; backend e packaging mobili non implementati |

## Artefatti

| Artefatto | Dimensione |
| --- | --- |
| `build/graalphp.exe` | 90.210.304 byte, 86,03 MiB |
| `build/graalphp-linux-x64` | 92.932.904 byte, 88,63 MiB |
| `build/graalphp-native.dll` | 6.533.120 byte, 6,23 MiB |

Le due build Native Image usano `-O3`, target macchina compatibile e includono
il compilatore guest. Windows richiede anche le tre DLL MSVC copiate dal build:
`msvcp140.dll`, `vcruntime140.dll` e `vcruntime140_1.dll`.
Linux non è fully-static. Il bundle dei target php-xmake è compilato
con Xmake su entrambi i sistemi. Bridge DLL/SO per JVM e archivi per Native
Image condividono gli stessi sorgenti. Il prodotto nativo non richiede il
bridge; le librerie esterne richieste esplicitamente via FFI restano esterne.

Le sorgenti runtime usate dalla build Linux sono state confrontate con quelle
Windows e sono identiche. Il workspace Linux di verifica è una copia
rigenerabile sotto `build/linux-workspace/`; il test degli eventi filesystem
è stato eseguito con directory temporanee sul filesystem Linux, dopo aver
isolato il comportamento della mount Windows.

## Evidenze rigenerabili

Verifica corrente dell'[allineamento dello scheduler](trueasync-alignment.md):

- `build/alignment-integration-windows.log`: 73 scenari; `alignment-verify-windows.log` include i 53 scenari del value model.
- `build/alignment-trueasync-windows-jvm.log` e `alignment-trueasync-windows-native.log`: 82 confronti per modalità, inclusi riuso degli scope e scope indipendenti oltre la fine del programma principale.
- `build/alignment-network-windows-jvm.log`, `alignment-network-windows-native.log` e `alignment-package-windows.log`: 50 controlli per modalità/pacchetto.
- `build/linux-workspace/build/alignment-integration-linux.log` e `alignment-values-linux.log`: 67 e 53 scenari.
- `build/linux-workspace/build/alignment-trueasync-linux-{jvm,native}.log`: 82 confronti per modalità.
- `build/linux-workspace/build/alignment-network-linux-{jvm,native}.log`: 50 controlli per modalità.
- `build/linux-workspace/build/alignment-linkage-linux.log`: libc, libm e loader di sistema.
- `build/alignment-native-windows.log` e `build/linux-workspace/build/alignment-native-linux.log`: build Native Image; bundle curl-impersonate invariato.

Verifica precedente del consolidamento PHP/libuv:

- `build/reactor-windows-verify.log`: 72 scenari Windows, 53 semantici e 80 confronti TrueAsync/JVM.
- `build/reactor-windows-native-network.log` e `reactor-windows-jvm-network.log`: 50 controlli di rete per modalità; il primo include la build Native Image.
- `build/reactor-windows-native-trueasync.log`: 80 confronti Native Image.
- `build/reactor-windows-package.log`: 50 controlli sul pacchetto isolato.
- `build/linux-workspace/build/reactor-linux-filesystem-verify.log`: 66 scenari su filesystem Linux.
- `build/linux-workspace/build/reactor-linux-values.log`: 53 scenari semantici.
- `build/linux-workspace/build/reactor-linux-native-network.log` e `reactor-linux-jvm-network.log`: 50 controlli per modalità; il primo include la build Native Image.
- `build/linux-workspace/build/reactor-linux-native-trueasync.log` e `reactor-linux-jvm-trueasync.log`: 80 confronti per modalità.
- `build/linux-workspace/build/reactor-linux-linkage.log`: sole dipendenze libc, libm e loader.

Le seguenti evidenze appartengono alla verifica precedente del backend cURL:

- `build/curl-multi-windows-regression.log`: 69 scenari Windows, 53 semantici e 80 differenziali TrueAsync/JVM.
- `build/trueasync-cases/results.txt` e `native-results.txt`: confronti Windows.
- `build/curl-multi-linux-regression.log`: 63 scenari Linux e 53 semantici.
- `build/curl-multi-windows-trueasync-native.log`: 80 confronti sul Native Image Windows.
- `build/curl-multi-linux-trueasync-native.log` e `curl-multi-linux-trueasync-jvm.log`: 80 confronti per ciascuna modalità Linux.
- `build/curl-multi-windows-native.log` e `curl-multi-linux-native.log`: build native finali e 50 controlli di rete per piattaforma.
- `build/curl-multi-windows-jvm.log` e `curl-multi-linux-jvm.log`: 50 controlli di rete per piattaforma sulla JVM.
- `build/network-*/`: certificato locale, database persistente, log server/riapertura e `client-hellos.txt`; su Linux sotto `build/linux-workspace/build/`.
- `build/curl-multi-linux-linkage.log`: dipendenze dinamiche del nuovo eseguibile.
- `build/curl-multi-windows-linkage.log`: import Windows, inclusa la dipendenza MSVCP del backend BoringSSL.
- `build/curl-multi-windows-package.log`: test completo sul pacchetto isolato.
- `build/trueasync-multi-close-reuse.log`: crash riproducibile del binario Windows upstream.
- `build/curl-multi-windows-lifetime.log` e `curl-multi-linux-lifetime.log`: stesso riproduttore sui prodotti nativi GraalPHP.
- `build/native-guest-jit.log` e `build/linux-native-guest-jit.log`: compilazione guest.

Le verifiche precedenti di isolamento e setup restano riproducibili:
`standalone-ffi.txt`, `interpreter-ffi.txt`, `standalone-synchronization.txt`,
`linux-native-ffi.log`, `standalone-pools.log`, `interpreter-pools.log`,
`linux-static-pools.log` e `linux-setup-final.log`, tutti sotto `build/`.
Non sono presentate come nuove esecuzioni della suite di rete.

La prova JIT usa compilazione sincrona e soglie ridotte
(FirstTierCompilationThreshold=50, LastTierCompilationThreshold=500) per
rendere deterministica l'osservazione dei due livelli su `examples/jit.php`.
Non costituisce una misura di prestazioni o una dimostrazione dell'ottimizzazione
completa del modello dei valori.

## Problemi identificati e risolti

L'API multi mantiene distinti membership, trasferimenti e attese select.
Cancellare una coroutine in select rimuove solo il relativo waiter, con ack
prima del ritorno; remove/close distaccano invece gli easy handle. I riferimenti
C delle membership proteggono il cleanup durante la raccolta dei cicli guest.
Il riproduttore `tests/php/trueasync-multi-close-reuse.php` passa in GraalPHP;
il binario TrueAsync Windows 0.10.0 stampa `1:1` e termina con
`-1073741819` (access violation). Il caso rimane separato dal corpus degli
82 confronti passati, come descritto in `trueasync-compatibility.md`.

L'offload cURL iniziale limitava il numero di trasferimenti al numero di worker.
Il backend multi/socket ora avanza sul thread proprietario PHP/libuv. La cancellazione richiede
il distacco dell'easy handle e lo attende in un finally protetto prima di
liberare lo stato occupato. I risultati immediati vengono drenati anche se
libcurl non registra socket o timer. La configurazione Linux abilita inoltre
il resolver asincrono POSIX della versione 8.21.0.

Il confronto con il sorgente PHP/TrueAsync ha corretto `curl_close`: ora non
invalida l'oggetto, mentre l'ultimo riferimento guest ne rilascia la risorsa C.
Il runner differenziale carica esplicitamente `ext/php_curl.dll` dell'oracolo
Windows per il nuovo caso cURL, senza modificare alcun php.ini.

Le prove concorrenti hanno evidenziato un problema nell'ordine dei callback
dei future: la coroutine poteva riprendere prima del rilascio dello stato
«lettura in corso» o «handle occupato». TCP/WebSocket e gli adattatori cURL/SQLite
pubblicano ora il completamento dopo aver aggiornato tale stato. Sono stati
corretti anche l'upgrade HTTP h2c offerto dal client indipendente, i BLOB/stringhe
vuoti nel confine NFI e il rilascio del cursore SQLite alla finalizzazione.

L'append ora sceglie la destinazione dopo la valutazione del RHS; le assegnazioni
composte conservano una sola locazione. Gli oggetti e le catture delle closure
partecipano all'ownership esplicita e alla collezione dei cicli.

Un thread C non collegato al contesto NFI causava un access violation su Windows.
Lo shim ora usa il `trufflenfi.h` della toolchain fissata e chiama
attachCurrentThread/detachCurrentThread; il callback viene poi eseguito dal
dispatcher della richiesta. Il test passa sia su JVM sia su Native Image.
Non dimostra supporto automatico per librerie che chiamano callback da thread
non collegati, né per callback trattenuti oltre il lifetime previsto.

Lo script batch controlla anche codici di uscita negativi: il precedente
`if errorlevel 1` non riconosceva il codice Windows dell'access violation.
Un crash non viene quindi più seguito da una falsa conclusione di verifica.

Il confronto TrueAsync ha corretto il tipo di eccezione per chiavi di contesto
duplicate, oltre al comportamento della divisione esatta tra interi. La copertura
rimane un sottoinsieme esplicito: vedere [trueasync-compatibility.md](trueasync-compatibility.md)
e [design-coverage.md](design-coverage.md).

Il nuovo blocco distingue la selezione dei risultati dal completamento delle
coroutine rimanenti nei combinatori TrueAsync. Corregge inoltre l'attesa di
future già risolti, l'uso della sorgente come proprio token di cancellazione,
i motivi personalizzati e il rilascio dei valori di riprese invalidate.
Le registrazioni disattivate non consegnano eventi a continuation già riprese.
Restano da completare il lifetime dei future rispetto ai riferimenti guest
e l'intera policy degli errori/disposal degli scope.
La protezione dalla cancellazione cooperativa non disabilita il controllo
della deadline host nei loop CPU; un caso dedicato verifica anche il finally
della closure protetta.
