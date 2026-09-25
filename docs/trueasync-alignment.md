# Allineamento dello scheduler a TrueAsync

L'allineamento è possibile a livello di architettura: PHP, libuv e callback di
rete condividono già il thread proprietario. Code sincronizzate per operazioni
locali, scansioni ripetute degli scope e capacità residua della mappa del collector
erano scelte della nostra implementazione, non requisiti di GraalVM.

## Confini tecnici

TrueAsync usa fiber Zend con stack nativo. La
[Bytecode DSL di Truffle](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/bytecode/GenerateBytecode.html#enableYield())
espone invece continuation stackless: salva lo stato dell'interprete e lo
restituisce al chiamante. GraalPHP gestisce esplicitamente le attivazioni PHP
annidate e le riprende dal driver. Questo consente lo stesso modello cooperativo,
ma non è uno switch intercambiabile con quello delle fiber Zend.

Non assumiamo che una continuation PHP possa catturare e riprendere uno stack
C/Java arbitrario attraverso NFI. Le funzioni C bloccanti passano dai pool
configurabili, mentre libuv e cURL multi/socket pubblicano completamenti senza
bloccare. Le callback del reactor notificano lo scheduler; non rientrano nel guest.
Le callback dichiarate con `FFI::cdef` possono sospendere la coroutine tramite
il [bridge con stack C separati](native-stack-bridge.md), anche quando la
chiamata C è assegnata a un pool. Il percorso legacy `ffi_call` mantiene invece
callback NFI sincrone. Lifetime e limiti del nuovo percorso sono documentati
nel bridge.
Truffle richiede inoltre di rispettare ingresso nel contesto e affinità dei
frame: il nostro spawn su thread condivide il contesto, ma usa le proprie
attivazioni e strutture condivise previste dal design.

## Modifiche applicate

- La FIFO locale usa `ArrayDeque`, senza lock. Solo le notifiche da altri thread
  usano la inbox `LinkedBlockingQueue` e il wakeup libuv. La inbox viene drenata
  anche durante lavoro PHP continuo; embedding e deadline controllano entrambe
  le code. Senza libuv, la CLI si blocca sulla inbox con attesa interruptible.
- Ogni scope conta le attività proprie e dei discendenti. Spawn e completamento
  aggiornano gli antenati; le attese di scope sono notificate alla transizione
  a zero. Un conteggio separato di richiesta comprende gli scope indipendenti.
  I risultati dei worker aggiornano i conteggi sul proprietario. `finished` e
  `pending` non percorrono più le liste e ogni azione non rivaluta tutti gli scope.
- Il collector sostituisce il set dei candidati dopo aver acquisito quelli del
  giro corrente. Conserva algoritmo e frequenza di raccolta, senza mantenere
  una tabella dimensionata per un precedente picco di coroutine. È la variante
  `compact` già misurata nell'[analisi precedente](trueasync-reactor-comparison.md).

La cadenza del pump resta invariata: la variante `polls` non aveva migliorato
il relay. Le liste di task e scope restano per cancellazione e cleanup; non
sono state eliminate dalla rappresentazione. Le deadline degli scope vengono
ancora controllate nel pump.

Restano da ottimizzare e misurare separatamente: doppia consegna listener/Resume,
catene Future del protocollo, copie dei buffer, binding NFI e timer per operazione.
Questa modifica non dimostra parità di prestazioni con TrueAsync né attribuisce
il divario residuo alle continuation di Truffle.

## Provider cURL

Il bundle rimane curl-impersonate 2.2.2 con curl 8.21.0 e BoringSSL fissata in
Xmake. Il pin curl segue la release impersonate, non l'ultima release standard.
È ora affiancato da cURL standard 8.22.0 e BoringSSL non modificata, con simboli
separati e routing per handle. Entrambi lavorano sullo stesso thread PHP/libuv.
La selezione è descritta in [curl-providers.md](curl-providers.md); il nuovo
[confronto prestazioni](curl-performance.md) usa esclusivamente richieste cURL
dirette con il provider standard.

## Riproduzione diagnostica

La vecchia variante `reactor-probe compact` è ora il comportamento del prodotto.
`build.bat reactor-probe retained` genera una copia diagnostica che ripristina
la capacità residua della mappa, lasciando intatto lo scheduler corrente.
Le varianti `cycles` e `polls` restano diagnostiche; `cycles` cambia il lifetime
e non deve essere usata come prodotto. I numeri storici confrontano gli hash
registrati allora, non eseguibili rigenerati dal sorgente corrente.

## Verifica funzionale

Windows: 73 scenari integrati, 53 semantici/lifetime, 82 confronti TrueAsync sia
su JVM sia su Native Image. Linux: 67 scenari integrati, gli stessi 53 scenari
semantici e 82 confronti per modalità. I sei casi esclusi su Linux richiedono
DLL Windows. I test del watcher Linux usano una directory temporanea sul
filesystem Linux, non `/mnt/c`.

Entrambi i sistemi passano 50 controlli WebSocket → HTTPS/curl-impersonate →
SQLite sia su JVM sia su Native Image. Il pacchetto Windows isolato passa gli
stessi 50 controlli con il solo eseguibile e tre DLL MSVC, `JAVA_HOME` vuota e
`PATH` limitato a Windows. Il client di verifica usa il JDK separatamente.
Linux continua a dipendere solo da libc, libm e loader; il bundle è statico.

Le nuove regressioni verificano scope indipendenti con discendenti, riuso dopo
il completamento e 24 chiamate C su worker dopo il ritorno del programma
principale. La suite esistente copre cancellazione, finally, wakeup da altri
thread e embedding. Dettagli e log rigenerabili in [validation.md](validation.md).

## Misura dello scheduler con collector già corretto

Il controllo è `graalphp-probe-compact.exe` dell'analisi precedente, non il
vecchio prodotto con la mappa GC difettosa. Il nuovo prodotto e il controllo
usano quindi entrambi il collector corretto e lo stesso bundle curl-impersonate.
Il confronto isola l'insieme FIFO/inbox/conteggi, senza attribuire di nuovo allo
scheduler il guadagno già dimostrato del collector.

Windows, Native Image `-O1`, 10.000 coroutine sospese, 64 client WebSocket,
quattro secondi per fase dopo warmup, nessuna build o altra suite in parallelo,
nessun JFR. Due serie da due ripetizioni alternano l'ordine dei runtime:
quattro campioni per eseguibile, otto prove completate e 585.378 messaggi
echo/relay verificati. Il peer è nuovo a ogni prova, con keep-alive abilitato.

| Mediana | Solo collector corretto | Collector e scheduler attuali |
| --- | ---: | ---: |
| Echo msg/s | 12.295 | 12.994 |
| CPU processo, µs/echo | 81,47 | 76,87 |
| Echo p95, ms | 6,79 | 6,58 |
| Relay cURL msg/s | 5.500 | 5.536 |
| CPU processo, µs/relay | 181,23 | 179,80 |
| Relay p95, ms | 15,42 | 15,20 |

L'echo migliora del 5,7% sulla mediana; il relay del solo 0,6%. La prima serie
aveva mostrato circa -5% sul relay, risultato non confermato dalla seconda.
Gli intervalli si sovrappongono: echo 12.003–12.899 contro 12.045–13.607 msg/s,
relay 5.424–5.658 contro 5.085–5.725. Sono prove brevi: non dimostrano un
guadagno stabile del relay né consentono di assegnare percentuali separate
alla FIFO e ai conteggi. Il costo delle continuation e dei confini NFI rimane
da misurare, e questa serie non è un nuovo confronto diretto con TrueAsync.

Durante la fase sospesa la mediana RSS è 246,2 contro 250,5 MiB, con CPU
misurata 0% in tutti i campioni. È memoria dell'intero processo, non costo
isolato delle coroutine. Il peer continua a osservare centinaia di connessioni
distinte per prova: questo intervento non corregge il riuso delle connessioni
del bundle curl-impersonate.

Dati: [prima serie](benchmarks/alignment-windows-2026-09-25/first/results.csv),
[ripetizione](benchmarks/alignment-windows-2026-09-25/repeat/results.csv),
[hash](benchmarks/alignment-windows-2026-09-25/hashes.json) e
[verifiche funzionali](benchmarks/alignment-windows-2026-09-25/functional-results.txt).
Ogni serie conserva anche ambiente, finestre temporali e contatori del peer.
Il nome `graalphp-before` nei CSV identifica la variante `compact`, non il
prodotto precedente all'allineamento.

```bat
set GRAALPHP_BENCH_BASELINE=build\graalphp-probe-compact.exe
set GRAALPHP_BENCH_CLIENTS=64
tools\graalvm-25.4.4.1.1+1.1\bin\java.exe --enable-native-access=ALL-UNNAMED -cp build\test-classes graalphp.NetworkBenchmark 2 4 10000
set GRAALPHP_BENCH_BASELINE=
set GRAALPHP_BENCH_CLIENTS=
```

Il comando è stato eseguito due volte con gli stessi binari conservati in
`build/`. Gli hash fissano quali eseguibili sono stati confrontati; la vecchia
variante `compact` non è rigenerabile dal comando diagnostico corrente.
