# Prestazioni Windows: GraalPHP e TrueAsync

Misura del 25 settembre 2026, **prima del consolidamento PHP/libuv sullo stesso
thread**. Questo confronto conserva la baseline Native Image con thread I/O
separato e il binario Windows ufficiale TrueAsync. Non misura la
compatibilità PHP generale né predice le prestazioni del design completato.

TrueAsync è più veloce sul traffico misurato e usa meno CPU per messaggio.
GraalPHP ha un working set di base maggiore, ma un private commit molto
inferiore quando si sospendono migliaia di coroutine con le impostazioni
Windows predefinite di TrueAsync.

Il successivo confronto tra le due architetture è descritto in
[reactor-performance.md](reactor-performance.md).

## Risultati

Mediane delle tre ripetizioni, salvo il caso marcato con asterisco. Le
coroutine indicate sono sospese in aggiunta ai client WebSocket attivi.
Le colonne CPU e p95 riportano GraalPHP / TrueAsync; ogni mediana è calcolata
separatamente, perciò non è necessariamente il rapporto di altre mediane.

| Coroutine sospese | Percorso | Client | GraalPHP msg/s | TrueAsync msg/s | CPU µs/msg G / T | p95 ms G / T |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1.000 | Echo | 64 | 19.102 | 28.881 | 64,2 / 38,5 | 4,03 / 2,52 |
| 1.000 | Echo | 256 | 16.726 | 28.048 | 70,5 / 42,3 | 20,67 / 10,66 |
| 1.000 | WS → cURL | 64 | 7.435 | 10.277 | 192,0 / 101,9 | 11,48 / 6,90 |
| 1.000 | WS → cURL | 256 | 6.448 | 9.394 | 202,7 / 113,6 | 60,88 / 30,32 |
| 10.000 | Echo | 64 | 18.036 | 27.958 | 66,9 / 41,7 | 4,12 / 2,75 |
| 10.000 | Echo | 256 | 16.420 | 26.598 | 73,6 / 43,9 | 21,59 / 11,30 |
| 10.000 | WS → cURL | 64 | 3.961 | 9.823 | 379,7 / 106,0 | 21,58 / 7,56 |
| 10.000 | WS → cURL | 256 | 4.167* | 9.140 | 362,3* / 119,6 | 80,65* / 30,72 |

A 10.000 coroutine e 64 client, TrueAsync elabora circa **1,55×** i messaggi
echo e **2,48×** i relay cURL. Il costo CPU GraalPHP per messaggio è circa
**1,60×** e **3,58×** quello TrueAsync. Le percentuali CPU mediane sono
121,3% / 115,2% nell'echo e 146,7% / 104,0% nel relay: valori superiori a
100% comprendono il lavoro di più thread del processo.

L'intervallo osservato a 64 client e 10.000 coroutine è 17.832–18.484 msg/s
contro 27.607–28.615 nell'echo; 3.705–4.111 contro 9.740–10.004 nel relay.
Il relay GraalPHP passa da 7.435 a 3.961 msg/s aumentando le coroutine
sospese da 1.000 a 10.000. TrueAsync passa da 10.277 a 9.823. Questa
sensibilità alla popolazione sospesa richiede profiling prima di ottimizzare.

| Memoria del processo | GraalPHP | TrueAsync |
| --- | ---: | ---: |
| RSS dopo riscaldamento, prima del caso 10.000 | 234,1 MiB | 23,9 MiB |
| RSS con 1.000 coroutine sospese | 280,8 MiB | 36,9 MiB |
| RSS con 10.000 coroutine sospese | 249,7 MiB | 160,8 MiB |
| Private commit con 1.000 coroutine sospese | 296,2 MiB | 2.024,8 MiB |
| Private commit con 10.000 coroutine sospese | 264,8 MiB | 20.114,6 MiB |

La variabilità dell'heap GraalPHP impedisce di dedurre un costo lineare per
coroutine da queste righe: RSS sospeso 232,2–346,2 MiB a 1.000 e
249,6–312,6 MiB a 10.000, senza GC forzato. In tutti i campioni di attesa
il contatore CPU non è avanzato: consumo sostanzialmente nullo alla
risoluzione della misura, non una dimostrazione di zero lavoro assoluto.

**Esito e anomalia:** la serie principale ha completato 11 casi su 12 e
47 fasi di traffico, con **2.921.656 risposte verificate** nelle fasi riuscite.
Nella terza ripetizione GraalPHP, 10.000 coroutine e relay a 256 client,
il client ha ricevuto `CLOSED:1006`; il server ha registrato errori cURL
di connessione al peer locale. Il processo era ancora vivo. Quella fase
e il successivo rilascio non hanno un risultato valido: l'asterisco indica
la mediana delle sole **due fasi riuscite**, non tre prove senza errori.
Il runner ha correttamente terminato con codice 1.

Un tentativo separato con peer e processi nuovi, una ripetizione a 10.000,
ha completato entrambi i runtime con codice 0. GraalPHP ha ottenuto circa
4.439 msg/s nel relay a 256 client. Questo tentativo è conservato a parte
e non sostituisce il fallimento nelle mediane. La causa dell'errore resta
da isolare: non è dimostrato se dipenda dal runtime o dalle risorse del
test locale prolungato.

Dati conservati: [CSV principale](benchmarks/windows-2026-09-25/results.csv),
[ambiente e hash](benchmarks/windows-2026-09-25/environment.txt),
[hardware/versioni](benchmarks/windows-2026-09-25/hardware.json),
[errore del runner](benchmarks/windows-2026-09-25/failures.txt),
[log del server](benchmarks/windows-2026-09-25/failure-server.log),
[ripetizione separata](benchmarks/windows-2026-09-25/retry.csv) e
[prova esplorativa 50.000](benchmarks/windows-2026-09-25/exploratory-50000.csv).

## Riproduzione

```bat
build.bat network-benchmark
build.bat network-benchmark 3 4
```

Gli argomenti sono ripetizioni e secondi per fase di traffico. Il comando
prepara le dipendenze, rigenera il Native Image, compila il runner Java e avvia
tre ripetizioni per ciascun runtime e popolazione (1.000 e 10.000 coroutine).
Un quarto argomento può indicare altre popolazioni, separate da virgole;
sopra 10.000 vengono eseguite soltanto le fasi di memoria. I risultati e i
log sono scritti in una nuova directory `build/network-benchmark-*`.
Un errore viene registrato in `failures.txt`; gli altri casi proseguono e il
comando termina con errore se anche un solo caso fallisce.

Il runner mantenuto è [NetworkBenchmark.java](../tests/graalphp/NetworkBenchmark.java);
il server identico per entrambi è [network-benchmark.php](../tests/php/network-benchmark.php).
La misurazione iniziale ha compilato direttamente il runner ed eseguito gli
eseguibili già verificati, senza ricompilare o modificare il runtime.

## Metodo

- Windows 11 x64, Intel Core i7-11800H, 16 processori logici, 15,73 GiB di RAM.
- GraalPHP Native Image/Truffle 25.4.4.1.1, build `-O1`, compilatore guest
  incluso e abilitato, reactor libuv. TrueAsync 0.10.0, PHP 8.6.0-dev,
  estensione server 0.15.0, `-n`, `memory_limit=-1`, stack fiber predefinito.
- GraalPHP: libcurl 8.21.0/curl-impersonate 2.2.2/BoringSSL. TrueAsync:
  libcurl 8.22.0/OpenSSL 4.0.2. Si usa HTTP locale senza TLS o impersonazione.
- Ogni caso parte da un nuovo processo, server con un worker. L'ordine dei
  runtime si inverte a ogni ripetizione. Il generatore Java 25 gira sulla
  stessa macchina, fuori dal processo misurato.
- Riscaldamento escluso: 4 secondi echo più 3 secondi cURL, 32 client.
  Il riscaldamento è fisso: non dimostra che tutte le compilazioni JIT siano
  terminate o che il regime di lungo periodo sia raggiunto.
- Si misura la memoria di base dopo il riscaldamento, poi si creano 1.000
  oppure 10.000 coroutine, tutte sospese sullo stesso Future. Un Channel
  conferma l'avvio di ciascuna prima della misura.
- Traffico con 64 e 256 connessioni WebSocket, una richiesta in volo per
  connessione. Echo: messaggio testuale da 128 byte. Relay: ogni messaggio
  chiama `curl_exec` verso un peer HTTP indipendente, che attende 5 ms e
  restituisce 1.024 byte. Handle cURL riusato per connessione, keep-alive
  HTTP/1.1. Ogni risposta viene verificata.
- Connessioni aperte prima della misura, 4 secondi per fase più completamento
  delle richieste già in volo. I percentile sono round-trip osservati dal
  client. Le coroutine sospese restano presenti durante tutto il traffico.
- Fine caso: risveglio e attesa di tutte le coroutine, memoria dopo rilascio,
  arresto ordinato del server con codice 0. Nessun GC forzato.

CPU = tempo user + kernel dell'intero processo, inclusi JIT e thread I/O;
100% equivale a un processore logico occupato. Il costo CPU per messaggio è
`cpu_seconds / operations`, distinto dalla percentuale CPU: un server che
elabora meno messaggi non diventa più efficiente perché usa meno CPU.
I contatori sono quelli di
[GetProcessTimes](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getprocesstimes).

RSS indica il working set residente del processo, non soltanto l'heap PHP.
Il private commit indica la memoria privata impegnata da Windows; non è RAM
fisica residente né il semplice spazio di indirizzi riservato. Si leggono
`WorkingSetSize` e `PrivateUsage` da
[PROCESS_MEMORY_COUNTERS_EX](https://learn.microsoft.com/en-us/windows/win32/api/psapi/ns-psapi-process_memory_counters_ex).
Il picco RSS viene campionato ogni circa 40 ms nel traffico, quindi può
perdere picchi più brevi. La CPU in attesa è campionata per un secondo e ha
la granularità dei contatori Windows.

## Prova esplorativa a 50.000 coroutine

Una prova precedente, esclusa dalle mediane del confronto, ha completato su
GraalPHP la creazione, sospensione e rilascio di 50.000 coroutine. RSS:
294,8 MiB prima, 379,3 MiB sospese, 383,0 MiB dopo il rilascio;
private commit sospeso 392,3 MiB. Non è stata eseguita la fase di traffico
a questa popolazione.

Nella stessa prova il processo TrueAsync è terminato durante `/park`, senza
output diagnostico. Il primo runner non conservava il codice di uscita:
la causa della terminazione non è dimostrata e non si presenta questo caso
come un limite generale di TrueAsync. Il runner ora conserva fase e stato
del processo in caso di errore. La prova non è stata ripetuta a 50.000 per
non reiterare la forte pressione sul commit di sistema.

Il binario TrueAsync riporta `fiber_stack_size=2097152` tramite
`Async\runtime_stats()`. Nel sorgente PHP di riferimento il ramo Windows di
`zend_fiber_stack_allocate` usa `VirtualAlloc(..., MEM_COMMIT, ...)`.
[MEM_COMMIT](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-virtualalloc)
impegna memoria prima che tutte le pagine diventino residenti. Durante una
prova a 10.000 coroutine, `Get-Process.PrivateMemorySize64` ha confermato
circa 21,23 miliardi di byte privati, mentre il working set era circa
171 milioni di byte. Il commit totale del sistema era circa 35,56 miliardi
di byte. Questi controlli indipendenti confermano l'ordine di grandezza
letto dal runner; non dimostrano da soli la causa della terminazione a 50.000.
Cambiare lo stack TrueAsync richiederebbe un confronto separato.

## Limiti e prossime verifiche

È un confronto locale a carico chiuso: non misura un tasso di arrivo imposto,
saturazione di rete reale, migliaia di socket contemporanei, stabilità per
ore, PHP CPU-bound, TLS/impersonazione, SQLite o throughput multithread.
10.000 coroutine sospese non equivalgono a 10.000 connessioni attive.
Il client e il peer HTTP condividono la macchina con il server; il loro
consumo è escluso dai contatori del processo ma può influenzare i tempi.
I risultati Windows non si trasferiscono automaticamente a Linux.

Il working set GraalPHP oscilla con compilazione e raccolta dell'heap:
la differenza prima/dopo non è una misura precisa dei byte per coroutine.
La memoria dopo il rilascio non dimostra da sola né un leak né il recupero
di tutti gli oggetti. Attualmente lo scheduler conserva metadati di task
fino alla fine della richiesta; serve una misura prolungata del ciclo
creazione/completamento in un server persistente.

Le priorità da profilare sono il percorso scheduler/completamenti con molti
task presenti e le allocazioni, copie di buffer e passaggi al reactor nel
percorso WebSocket e cURL. Il confronto individua il divario osservato;
non attribuisce ancora una percentuale del costo a ciascuna causa.
