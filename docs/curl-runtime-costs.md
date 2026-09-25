# cURL: costi del runtime e sospensione diretta

Misure Windows del 25 settembre 2026, successive alla
[prima ottimizzazione](curl-optimization.md). Il nuovo A/B migliora il throughput
del **9,4–9,6%** e riduce la CPU per richiesta dell'**11,5–11,8%** rispetto a quel
binario già ottimizzato. Nel confronto aggiornato con TrueAsync rimane un
divario di **10–11% nel throughput** e **17–19% nella CPU per richiesta**.

Le librerie native, le opzioni del compilatore e il thread proprietario del
reactor sono invariati in questo A/B. Le modifiche riguardano il percorso
`curl_exec` nel runtime e l'accesso alla risposta attraverso due funzioni C.

## Risultati controllati

HTTP/1.1 locale, risposta verificata di 1.024 byte, nessun ritardo artificiale,
64 client attivi; 16.384 richieste di riscaldamento escluse, 65.536 misurate per
prova. Tre ripetizioni alternate, processi e peer nuovi per ogni prova.
Le due campagne finali passano **24/24 prove**, 1.572.864 richieste misurate.
CPU, RSS e private commit sono contatori esterni del processo; CPU include
anche eventuali thread del compilatore e del GC.

Mediane dell'A/B contro `graalphp-before-runtime-costs.exe`:

| Coroutine sospese | Prima, req/s | Ora, req/s | Guadagno | CPU prima, µs/req | CPU ora, µs/req |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1.000 | 20.503 | 22.437 | +9,4% | 51,74 | 45,78 |
| 10.000 | 19.691 | 21.582 | +9,6% | 54,60 | 48,16 |

Gli intervalli osservati del throughput non si sovrappongono in questo A/B:
18.385–20.795 contro 22.260–22.668 con 1.000 sospese;
19.498–19.800 contro 21.451–21.779 con 10.000. Sono tre prove per gruppo,
non intervalli di confidenza. Il guadagno combina le due modifiche descritte
sotto: non attribuisce una percentuale separata a ciascuna.

Mediane di una seconda campagna contro TrueAsync 0.10.0:

| Coroutine sospese | GraalPHP, req/s | TrueAsync, req/s | Divario throughput | CPU GraalPHP, µs/req | CPU TrueAsync, µs/req |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1.000 | 22.687 | 25.455 | −10,9% | 46,49 | 39,10 |
| 10.000 | 21.584 | 23.992 | −10,0% | 48,40 | 41,49 |

Il binario di controllo raggiunge prestazioni diverse dalle sue misure nella
campagna precedente. Per questo il guadagno di questa modifica deriva dal
nuovo A/B, non dal confronto fra mediane di sessioni diverse.

Il peer osserva 64 endpoint per tutte le prove del nuovo binario nell'A/B;
una prova del controllo ne osserva 78. Nel confronto con TrueAsync GraalPHP
ne osserva 64–86, TrueAsync sempre 64. Si riusano le connessioni, ma non si
afferma un numero identico di riconnessioni in tutte le prove.

Entrambi dichiarano libcurl 8.22.0. GraalPHP usa BoringSSL, il pacchetto
TrueAsync usa OpenSSL 4.0.2: non sono build identiche di libcurl. La misura
usa HTTP e non stabilisce equivalenza TLS. Il libuv di GraalPHP resta 1.52.1
con la patch Electron; non è stata verificata la revisione esatta incorporata
nel binario TrueAsync. curl-impersonate resta invariato.

[Dati grezzi A/B](benchmarks/curl-runtime-costs-windows-2026-09-25/final-ab/results.csv),
[confronto TrueAsync](benchmarks/curl-runtime-costs-windows-2026-09-25/final-trueasync/results.csv)
e [mediane, minimi e massimi](benchmarks/curl-runtime-costs-windows-2026-09-25/summary.json).

## Come è stato isolato il costo

### Controllo C sullo stesso backend

`tests/native/curl_benchmark.c` usa lo stesso reactor di produzione,
`gp_reactor_curl`, il provider standard e gli stessi archivi statici. Una coda
di completamenti riavvia le richieste senza ricorsione nei callback C.
Alloca, copia e verifica ogni risposta. Non esegue PHP, NFI o coroutine e
ammette solo una popolazione sospesa pari a zero: serve come controllo del
backend, non come sostituto semanticamente equivalente del carico PHP.

Due ripetizioni alternate, 262.144 richieste misurate per prova, riscaldamento
ordinario; GraalPHP è ancora il binario precedente alla modifica corrente:

| Percorso | Req/s | CPU totale, µs/req | CPU user, µs/req | CPU kernel, µs/req |
| --- | ---: | ---: | ---: | ---: |
| GraalPHP precedente | 20.008 | 50,52 | 29,56 | 20,95 |
| TrueAsync | 24.380 | 40,86 | 18,48 | 22,38 |
| Controllo C | 27.638 | 36,12 | 13,98 | 22,14 |

Il backend di GraalPHP supera il throughput di TrueAsync quando viene guidato
direttamente da C. Questo contraddice l'ipotesi che il suo limite sia imposto
interamente dal bundle cURL/libuv. La differenza CPU è soprattutto in user
space. Le sottrazioni fra queste righe non sono una scomposizione causale
esatta: il controllo C ha scheduling, rappresentazione dei valori e ownership
diversi dal runtime PHP.

### Riscaldamento e compilazione guest

Con 262.144 richieste di riscaldamento, sedici volte il normale, e altrettante
misurate, il binario precedente raggiunge 20.393 req/s contro 23.870 di
TrueAsync. La CPU resta 49,68 contro 41,72 µs/req. Aumentare il riscaldamento
non elimina quindi il divario.

Le trace confermano compilazioni Tier 1 e Tier 2 prima della misura. Non tutte
sono fuori dalla finestra: nella prima prova terminano due compilazioni nei
primi 65 ms; nella seconda si osservano anche invalidazioni e ricompilazioni
tra 1,74 e 1,84 secondi. La finestra dura circa 12,9 secondi. Non si deduce
quindi che il costo JIT sia zero.
[Eventi nella finestra](benchmarks/curl-runtime-costs-windows-2026-09-25/long-warmup/compilation-in-window.json).

### Timer annidati nel runtime

La variante diagnostica `dispatch-costs` sottrae i timer figli dai chiamanti.
I contatori sono separati dal prodotto; le copie strumentate della versione
precedente sono conservate. Coprono l'intero processo: 278.532 chiamate cURL,
inclusi riscaldamento e quattro richieste di controllo.

| Percorso nella versione precedente | Tempo esclusivo, µs/chiamata cURL |
| --- | ---: |
| Dispatch generico delle funzioni | 1,98 |
| Dispatch delle funzioni cURL | 2,15 |
| Ricerca delle variabili nelle attivazioni | 2,07 |
| Avanzamento scheduler + ripresa | 2,86 |
| Ingresso nell'attivazione figlia | 0,61 |
| Chiusura dell'attivazione | 0,19 |
| Collector dei cicli | 0,38 |
| Conversione dei byte della risposta | 0,96 |

Si osservano circa otto visite al dispatch e undici ricerche di variabili per
richiesta, comprese quelle del wrapper PHP interno. I timer sono tempi di
parete strumentati, non percentuali CPU. La variante raggiunge 19.065 req/s e
53,35 µs CPU/req; la strumentazione modifica quindi il carico. Gli intervalli
NFI includono anche codice C e kernel, e le attese native sono separate: non
si può attribuire tutto il loro tempo al solo attraversamento NFI.

Questi dati hanno motivato la rimozione del wrapper residuo; la conferma del
guadagno viene dall'A/B dei prodotti senza timer. Il profilo storico JFR,
con i suoi limiti di campionamento e le pause GC, rimane descritto nel
[rapporto precedente](curl-optimization.md). Non c'è evidenza per attribuire
il divario principalmente alle pause GC; il costo complessivo delle
allocazioni non è stato isolato da questi timer.

## Modifiche nel prodotto

`curl_exec` ora restituisce direttamente una sospensione allo scheduler.
`Await` accetta un completamento sincrono facoltativo sul thread proprietario,
eseguito prima di riprendere il codice guest anche in caso di cancellazione.
Le attese già esistenti conservano il comportamento senza completamento.
Gli errori di conversione/cleanup entrano nella continuation PHP, così che
i suoi `catch` e `finally` possano eseguirsi.

Un oggetto `Transfer` conserva esplicitamente l'handle fino al distacco C e
alla ripresa PHP. Copre anche handle temporanei, errore immediato,
cancellazione prima della registrazione dell'attesa e shutdown. L'handle
rimane occupato dopo il completamento nativo finché il guest non riprende;
un'altra coroutine non può riconfigurarlo in quell'intervallo. La raccolta
dei cicli rimane nel cleanup: non è stata disabilitata per ottenere il risultato.

Native Image chiama direttamente `gp_curl_size` e `gp_curl_copy` per leggere
la risposta, con ABI fisso, array mantenuto fermo durante la copia e normale
transizione C compatibile con il GC. JVM conserva NFI. Lo stesso percorso
serve `curl_multi_getcontent`. FFI generale, callback, bridge degli stack C
e thread pool non cambiano.

Il wrapper PHP residuo e la sua attivazione per richiesta non sono più
necessari. Questo riduce dispatch, ricerca delle variabili, oggetti temporanei
e riprese della continuation. Il risultato non dimostra un limite intrinseco
di Graal/Truffle né richiede un thread aggiuntivo per libuv.

## Memoria e costo residuo

Mediane della campagna finale contro TrueAsync, alla fine della fase di traffico:

| Sospese | RSS GraalPHP, MiB | RSS TrueAsync, MiB | Private commit GraalPHP, MiB | Private commit TrueAsync, MiB |
| ---: | ---: | ---: | ---: | ---: |
| 1.000 | 199,03 | 37,77 | 215,58 | 2.086,39 |
| 10.000 | 222,61 | 161,52 | 238,58 | 20.176,14 |

RSS e commit sono misure diverse: il secondo non è RAM residente. La memoria
residente di GraalPHP rimane maggiore. Nell'A/B la mediana RSS scende da
191,73 a 182,96 MiB e da 242,49 a 222,68 MiB, ma i campioni variano tra processi.

Nel confronto finale la CPU user mediana è 24,08 contro 17,17 µs/req con 1.000
sospese e 24,80 contro 20,27 con 10.000. Le mediane kernel sono rispettivamente
22,89/22,65 e 20,98/21,46. Ogni colonna è aggregata separatamente e la somma
delle mediane user/kernel può differire dalla mediana totale.

Rimane da attribuire il costo user residuo fra dispatch PHP, rappresentazione
dei valori, allocazioni, gestione delle continuation e percorso NFI di
sottomissione/completamento. I timer precedenti non sono un profilo della nuova
implementazione e non quantificano questa ripartizione. Non viene dichiarata
parità con TrueAsync né completata la semantica PHP di `__destruct`, weakref
e resurrezione, che resta un lavoro distinto.

## Verifica e riproduzione

La suite cURL passa su Windows JVM (94 assert), Windows Native Image (102),
Linux JVM (100), Linux Native Image (106) e TrueAsync Windows (83).
Il conteggio include polling e controlli specifici dei provider, perciò varia.
Tutte le esecuzioni osservano 16 trasferimenti trattenuti contemporaneamente.

Oltre a TLS con verifica CA, redirect, gzip, errori, timeout, multi, byte
binari/NUL e protezione dalla cancellazione, sono verificati handle temporanei,
risposte vuote e da 1 MiB, cancellazione prima della sospensione con `finally`
guest e riuso immediato dell'handle. GraalPHP verifica anche il rifiuto di
eseguire o riconfigurare un handle occupato. Nessuna suite SQLite, WebSocket
o prestazioni FFI è stata aggiunta a questa campagna; la matrice generale
precedente non è stata rieseguita.

I prodotti aggiornati sono `build/graalphp.exe` (89.886.720 byte, 85,72 MiB) e
`build/graalphp-linux-x64` (92.801.832 byte, 88,50 MiB). Entrambi usano `-O3`;
Windows conserva le DLL MSVC, Linux libc/libm/loader di sistema.
Le copie dei sorgenti Windows/Linux sono identiche. I target del controllo C
compilano tramite gli entry point Xmake di entrambi i sistemi.
[Esiti e build](validation/curl-runtime-costs-2026-09-25/results.txt),
[hash dei sorgenti e dei prodotti](validation/curl-runtime-costs-2026-09-25/hashes.json).

```text
build.bat curl-test native
build.bat curl-test trueasync
bash build.sh curl-test native
build.bat curl-native-control
bash build.sh curl-native-control
build.bat curl-benchmark 3 1024 64 0
```

Per l'A/B impostare `GRAALPHP_BENCH_BASELINE` al binario di controllo conservato.
Per il controllo C impostare `GRAALPHP_BENCH_NATIVE=1` e usare
`build.bat curl-benchmark 2 4096 64 0 0`. Le popolazioni sospese devono essere
zero. Per il riscaldamento lungo usare `GRAALPHP_BENCH_WARMUP=4096`; la trace
richiede `GRAALPHP_BENCH_TRACE_COMPILATION=1`. Rimuovere gli override al termine.
Il campionatore delle prestazioni supporta attualmente Windows; la verifica
Linux è funzionale e di compilazione.

`build.bat reactor-probe dispatch-costs` produce una variante separata della
versione dei sorgenti corrente. Le misure diagnostiche di questo rapporto
si riferiscono invece ai sorgenti precedenti archiviati, non al nuovo binario.
Per misurare eseguibili già compilati senza ricompilarli, dopo aver compilato
il driver si può avviare direttamente `graalphp.CurlBenchmark` con la JVM
locale e `-cp build/test-classes`. Eseguire le campagne in sequenza, senza build
o altri test concorrenti.

[Archivio con driver, sorgenti e log](benchmarks/curl-runtime-costs-windows-2026-09-25).
