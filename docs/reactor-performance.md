# Confronto prima/dopo il consolidamento PHP/libuv

L'[analisi successiva del reactor TrueAsync](trueasync-reactor-comparison.md)
isola un costo importante nella mappa del collector PHP e confronta varianti
diagnostiche della raccolta e del pump. Questo documento conserva le misure
originali prima/dopo il consolidamento.

Il cambiamento porta `uv_run`, comandi e completamenti nativi sul thread
proprietario PHP. Il server CLI non crea più il thread libuv e non invia i
propri comandi attraverso mutex e `uv_async_send`. I pool FFI e il resolver
DNS mantengono i thread necessari al lavoro bloccante.

La [patch Electron](../src/native/patches/README.md) è applicata da Xmake alla
libuv 1.52.1 fissata. Il suo interrupt sugli aggiornamenti degli handle è
abilitato in embedding, dove un helper attende soltanto readiness IOCP/epoll.
Quel thread non esegue callback libuv o PHP. Il benchmark CLI misura quindi
il consolidamento e la rimozione della coda, non un guadagno attribuibile da
solo alla patch Electron. [Architettura e protocollo host](reactor.md).

## Metodo riproducibile

Prima della modifica è stata conservata una copia dell'eseguibile precedente
in `build/graalphp-before-reactor.exe`, con SHA-256
`332f974ae2dca72076f79df76ae08dab2a923cca2635a6180bb126c66d843169`:
è lo stesso binario della [baseline TrueAsync](performance.md).
Il confronto usa due processi nuovi in sequenza, alternando l'ordine per
ripetizione. Nessuna build gira durante le misurazioni.

```bat
set GRAALPHP_BENCH_BASELINE=build\graalphp-before-reactor.exe
build.bat network-benchmark 3 4
set GRAALPHP_BENCH_BASELINE=
```

Il runner può essere compilato ed eseguito direttamente quando gli
eseguibili sono già stati verificati, come nella misura qui conservata.
La copia precedente è un artefatto locale, non viene ricreata dalla build
del nuovo sorgente. Gli hash delle due immagini sono registrati nei dati.
Senza `GRAALPHP_BENCH_BASELINE`, il runner confronta il prodotto corrente
con TrueAsync.

Hardware, riscaldamento, payload, coroutine sospese e metriche sono quelli
del [primo confronto](performance.md#metodo): Windows 11, i7-11800H,
1.000/10.000 coroutine sospese più 64/256 client WebSocket, echo 128 byte
oppure cURL HTTP locale con risposta di 1.024 byte e ritardo di 5 ms.
CPU include tutti i thread del processo; 100% equivale a un core logico.

Il runner aggiornato crea un peer HTTP nuovo per ciascun caso, per separare
lo stato delle connessioni dei processi successivi. Imposta inoltre
`sun.net.httpserver.maxIdleConnections=4096`: il default 200 del JDK 25 è
inferiore alla fase con 256 client. Entrambe le immagini
ricevono lo stesso trattamento. Per questo il confronto A/B diretto è più
appropriato del rapporto con i valori storici di TrueAsync, che usavano
un peer comune all'intera serie e il limite predefinito. Gli errori restano nei log e non vengono
trasformati in campioni validi.

Le prove restano locali, a carico chiuso e di breve durata. Non dimostrano
stabilità per ore, prestazioni WAN/TLS/SQLite o scalabilità di migliaia di
socket: la popolazione grande è di coroutine sospese, con al massimo 256
client attivi. Il GC e la compilazione guest possono variare il working set.

## Risultati e limiti del confronto

**Il consolidamento non migliora il throughput in questa implementazione.**
Nel caso echo con 10.000 coroutine sospese e 64 client, tutti e sei i campioni
della fase passano: la mediana scende da **18.190 a 12.305 msg/s** (-32,4%).
La CPU complessiva scende da 122,1% a 100,7%, ma il costo CPU per messaggio
sale da **67,12 a 82,35 µs** (+22,7%). Il p95 passa da 4,13 a 6,58 ms.
A 256 client, sempre con tre campioni per immagine, echo scende da 16.812
a 11.976 msg/s (-28,8%).

La serie completa non è una verifica di stabilità superata: **6/12 casi
terminano**, tre per architettura. I casi interrotti sono registrati in
[failures.txt](benchmarks/reactor-windows-2026-09-25/failures.txt).
La tabella riporta solo le fasi concluse e indica il numero di campioni;
le celle cURL con campioni mancanti sono dati esplorativi, soggetti a
selezione dei casi sopravvissuti. Non sostituiscono una serie completa.

Ogni cella con due valori indica **prima / dopo**; mediane calcolate
separatamente, CPU inclusiva di tutti i thread del processo.

| Coroutine sospese | Percorso | Client | Campioni prima / dopo | msg/s prima / dopo | CPU µs/msg prima / dopo | p95 ms prima / dopo |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1.000 | echo | 64 | 2 / 3 | 18.689 / 12.534 | 65,43 / 78,70 | 4,08 / 6,49 |
| 1.000 | echo | 256 | 2 / 3 | 16.987 / 12.096 | 68,08 / 82,66 | 20,41 / 29,16 |
| 1.000 | cURL | 64 | 2 / 3 | 7.456 / 5.051 | 188,07 / 202,44 | 11,37 / 17,31 |
| 1.000 | cURL | 256 | 2 / 1 | 6.469 / 3.877 | 201,59 / 257,25 | 59,58 / 78,74 |
| 10.000 | echo | 64 | 3 / 3 | 18.190 / 12.305 | 67,12 / 82,35 | 4,13 / 6,58 |
| 10.000 | echo | 256 | 3 / 3 | 16.812 / 11.976 | 69,30 / 83,31 | 21,10 / 30,28 |
| 10.000 | cURL | 64 | 2 / 3 | 3.836 / 2.809 | 372,94 / 363,44 | 21,00 / 31,22 |
| 10.000 | cURL | 256 | 1 / 2 | 3.616 / 2.632 | 382,83 / 376,86 | 94,52 / 116,00 |

Con 10.000 coroutine sospese, il working set mediano passa da 249,6 a
245,5 MiB; il private commit da 264,3 a 262,1 MiB. Sono variazioni piccole
rispetto alla variabilità del GC: non dimostrano una riduzione strutturale
del consumo per coroutine. L'attesa delle coroutine sospese misura CPU 0%
alla risoluzione dei contatori, senza tick di polling.

Il vecchio thread poteva sovrapporre il lavoro I/O a quello PHP. Questo è
compatibile con la CPU sopra un core e il throughput maggiore, ma non è
un profilo che attribuisca tutto il divario alla perdita di parallelismo.
Il costo per messaggio indica lavoro da ottimizzare anche nel nuovo
percorso. La patch Electron risolve il contratto di risveglio dell'embedding;
non ottimizza automaticamente NFI, allocazioni o scheduler PHP.

## Errori di connessione sotto carico

Una prima serie, interrotta, aveva ancora il limite di 200 connessioni idle
del peer Java. Durante quella prova Windows ha registrato l'evento TCP/IP
**4227 alle 06:47:44 del 25 settembre 2026 (Europe/Rome)**: impossibilità di
riutilizzare abbastanza rapidamente gli endpoint locali. I dati iniziali
sono conservati con il prefisso `diagnostic-initial-`, esclusi dalla tabella.

Il limite 4096 e il peer nuovo per caso non hanno eliminato gli errori.
Nella serie riportata, tre casi per immagine falliscono con cURL
`Failed to connect` e chiusura WebSocket 1006. Durante la serie sono state
osservate **14.099 connessioni TIME_WAIT**; l'intervallo porte dinamiche
Windows è quello predefinito, 49152–65535 (16.384 porte).
Non sono state modificate impostazioni TCP globali.

Il peer conta da 1.494 a 7.159 endpoint distinti nei casi arrivati al carico,
nonostante al massimo 256 trasferimenti concorrenti. Gli endpoint contati
non sono un conteggio perfetto delle connessioni, perché una porta può
essere riutilizzata, ma confermano frequenti riconnessioni. Il sorgente
libcurl 8.21.0 (`Curl_cpool_conn_now_idle` in `lib/conncache.c`) ridimensiona
la cache predefinita in base ai trasferimenti attivi. Questo è un candidato
per spiegare il ricambio, non una causa dimostrata da tracing.
La gestione della cache e l'esaurimento delle porte restano da risolvere;
la presenza degli errori in entrambe le immagini esclude che siano una
prova sufficiente di regressione introdotta dal solo consolidamento.

Dopo che le connessioni TIME_WAIT erano scese a quattro, è stata eseguita
una ripetizione isolata con 10.000 coroutine, stesso peer e stessi binari:
**2/2 casi completi**, incluse entrambe le fasi cURL a 64 e 256 client.
Il nuovo percorso misura 12.987/12.465 msg/s nell'echo e 3.075/2.680 msg/s
nel relay. Questo conferma il calo di throughput anche senza errori e
rafforza l'ipotesi di pressione transitoria sulle porte, senza risolvere
la causa delle riconnessioni. La ripetizione non sostituisce i campioni
falliti e non è mescolata alle mediane precedenti.

## Evidenze

- [results.csv](benchmarks/reactor-windows-2026-09-25/results.csv): 77 righe, 38 fasi di traffico riuscite e 1.597.902 messaggi verificati; le fasi fallite non contribuiscono ai conteggi.
- [fixture.csv](benchmarks/reactor-windows-2026-09-25/fixture.csv): richieste HTTP ed endpoint distinti per caso, inclusi i casi falliti e il warmup.
- [environment.txt](benchmarks/reactor-windows-2026-09-25/environment.txt): versioni, parametri e hash dei due eseguibili.
- [hardware.json](benchmarks/reactor-windows-2026-09-25/hardware.json): macchina del confronto.
- [isolated-results.csv](benchmarks/reactor-windows-2026-09-25/isolated-results.csv) e [isolated-fixture.csv](benchmarks/reactor-windows-2026-09-25/isolated-fixture.csv): ripetizione isolata passata dopo il rilascio delle porte.
- [Verifica funzionale](validation.md): 72 scenari Windows, 66 Linux, 53 semantici, 80 confronti TrueAsync per piattaforma/modalità e 50 controlli di rete per piattaforma/modalità, tutti passati.

Il consolidamento mantiene PHP e libuv sul proprietario e prepara il
contratto per futuri loop GUI. La regressione di throughput è esplicita;
questa versione non è presentata come un'ottimizzazione di prestazioni.
