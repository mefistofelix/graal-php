# cURL standard: diagnosi e ottimizzazione del runtime

Registro della prima ottimizzazione. Il [rapporto successivo](curl-runtime-costs.md)
aggiunge un controllo C, isola altri costi del runtime e misura i prodotti attuali.

Misure Windows del 25 settembre 2026, successive al [confronto iniziale](curl-performance.md).
Il carico resta il client cURL standard diretto, con libcurl 8.22.0, libuv sul
thread proprietario PHP e nessun worker aggiunto. curl-impersonate conserva
versione, patch e backend TLS precedenti.

## Risultati finali

Il binario aggiornato migliora il throughput del **35–40%** rispetto al controllo
originale nello stesso A/B, riducendo la CPU per richiesta del **25–28%**.
Il divario con TrueAsync è ridotto ma **rimane aperto**: nella campagna finale
GraalPHP completa circa il 22–26% di richieste al secondo in meno e consuma
circa il 33–41% di CPU in più per richiesta.

Mediane del confronto prima/dopo, senza ritardo artificiale del peer:

| Coroutine sospese | Prima req/s | Dopo req/s | Variazione | CPU µs/req prima → dopo |
| ---: | ---: | ---: | ---: | ---: |
| 1.000 | 13.505 | 18.219 | +34,9% | 77,5 → 55,8 |
| 10.000 | 12.626 | 17.668 | +39,9% | 80,1 → 59,8 |

Mediane della successiva campagna contro TrueAsync:

| Coroutine sospese | GraalPHP req/s | TrueAsync req/s | GraalPHP CPU µs/req | TrueAsync CPU µs/req |
| ---: | ---: | ---: | ---: | ---: |
| 1.000 | 17.666 | 23.758 | 59,4 | 42,0 |
| 10.000 | 16.977 | 21.907 | 60,3 | 45,3 |

Memoria nella campagna contro TrueAsync, alla fine della fase di traffico:

| Coroutine sospese | GraalPHP RSS MiB | TrueAsync RSS MiB | GraalPHP commit MiB | TrueAsync commit MiB |
| ---: | ---: | ---: | ---: | ---: |
| 1.000 | 220,7 | 36,4 | 236,8 | 2.086,3 |
| 10.000 | 212,6 | 160,2 | 229,2 | 20.176,1 |

La memoria residente non migliora in modo uniforme: nell'A/B con 10.000 sospese
la mediana RSS sale da 203,2 a 240,9 MiB. La lettura di fine fase comprende tutto
il processo, non isola lo storage delle coroutine; sono conservati anche i
picchi e gli intervalli. Il prodotto Windows cresce da **51,6 a 85,5 MiB**; quello Linux
aggiornato misura **88,4 MiB**. Il guadagno CPU comporta quindi un costo di
spazio e non va presentato come un miglioramento generale della memoria.

**24 prove finali su 24 passate**, 1.572.864 risposte misurate e 393.216 di
riscaldamento validate. Nessuna prova esclusa. Il peer osserva 64 endpoint TCP
per prova, con due eccezioni: 68 nella terza prova del controllo A/B con 10.000
sospese e 78 nella prima prova GraalPHP della campagna contro TrueAsync con
10.000 sospese. Le risposte
sono tutte corrette; il numero di connessioni non è quindi identico in ogni prova.

[CSV A/B](benchmarks/curl-optimization-windows-2026-09-25/final-ab/results.csv),
[CSV TrueAsync](benchmarks/curl-optimization-windows-2026-09-25/final-trueasync/results.csv),
[mediane e intervalli](benchmarks/curl-optimization-windows-2026-09-25/summary.json),
[prove completate](benchmarks/curl-optimization-windows-2026-09-25/completed-trials.json).


## Metodo e limiti

Stesso programma PHP per tutti i prodotti; peer Java indipendente, HTTP/1.1
keep-alive e corpo di 1.024 byte verificato a ogni risposta. Per prova: 16.384
richieste di riscaldamento escluse, poi 65.536 richieste misurate su 64 coroutine
attive, con 1.000 o 10.000 coroutine aggiuntive sospese. Tre ripetizioni per
configurazione, processi e peer nuovi, ordine alternato. Le campagne finali
prima/dopo e contro TrueAsync sono sequenziali; nessuna build o altra suite
eseguita durante le misure. Host e contatori sono quelli del confronto iniziale.

Il controllo prima/dopo è l'eseguibile originale conservato prima delle
ottimizzazioni, identificato da SHA256 nei metadati. Non si confrontano le
mediane di due giornate diverse per calcolare il miglioramento. TrueAsync è
il binario Windows ufficiale 0.10.0 / PHP 8.6, con la sola estensione cURL
aggiuntiva. Entrambi riportano libcurl 8.22.0, ma le build non sono identiche:
GraalPHP usa BoringSSL, TrueAsync OpenSSL 4.0.2. Questo carico HTTP non misura
il costo TLS. Il numero di versione libuv del binario TrueAsync non è stato
verificato separatamente.

La CPU è quella dell'intero processo, inclusi compilatore guest e gestione
memoria. RSS è il working set residente; commit privato non equivale a RAM
fisica. Tre campioni consentono un confronto locale, non una caratterizzazione
statistica completa. Gli intervalli e tutte le prove sono conservati nei CSV.

## Cosa mostrano i profili

PHP usa reference counting e un collector sincrono dei cicli. La distruzione
quando l'ultimo riferimento viene eliminato non esclude la raccolta dei cicli;
le due cose coesistono. Riferimenti: [GC PHP](https://www.php.net/gc.collecting-cycles)
e [distruttori](https://www.php.net/manual/en/language.oop5.decon.php).

In GraalPHP l'ownership guest governa il rilascio degli handle; il GC Java
recupera lo storage host. La semantica completa di `__destruct`, resurrezione e
weak reference PHP rimane **non implementata**: questa ottimizzazione non cambia
lo stato di copertura riportato in [design-coverage.md](design-coverage.md).

Il JFR iniziale contava 110 campioni su 172 del thread principale nel collector
guest, ma non significa che quel metodo consumasse il 64% della CPU. Su Windows
Native Image usa un sampler a callback, soggetto a bias dei safepoint:
[documentazione GraalVM](https://www.graalvm.org/dev/reference-manual/native-image/debugging-and-diagnostics/JFR/).
Nel medesimo intervallo di 19,334 s, le 191 pause GC Java totalizzavano 281,8 ms
(circa 1,46% del tempo). Questo non misura tutto il costo delle allocazioni,
ma non sostiene l'ipotesi che le sole pause spieghino il divario del 50%.

La riscrittura isolata del collector dà un guadagno limitato: nel suo A/B
13.901 → 14.457 req/s con 1.000 sospese e 12.717 → 12.811 con 10.000; gli
intervalli si sovrappongono. La strumentazione diretta del collector riscritto
misura circa 0,53 µs per invocazione, circa tre invocazioni per richiesta nel
carico originario. Il JFR è quindi servito a trovare il percorso da esaminare,
non ad assegnargli una percentuale affidabile di CPU.

I timer sul percorso nativo trovano circa 42 µs dentro `gp_reactor_curl` per
richiesta nella variante Native Image strumentata. Una successiva scomposizione
C, eseguita su JVM con il bridge diagnostico, trova circa 24 µs nella `send()`
Windows e 8 µs nello stato CONNECT di libcurl (anche con connessioni riusate).
Sono tempi inclusivi di parete, con strumentazione, raccolti durante l'intero
processo; non vanno sommati ai timer dei loro chiamanti o presentati come
percentuali CPU del prodotto finale. Il corrispondente percorso C del binario
TrueAsync non è stato profilato: questi dati localizzano lavoro da approfondire,
ma **non dimostrano la causa del divario residuo**.

## Modifiche applicate

- Collector dei cicli: visita del grafo una volta, contatori temporanei nei nodi,
  propagazione dei nodi vivi con una coda. Eliminati boxing dei conteggi e
  scansioni ripetute delle tabelle. Cadenza e rilascio a refcount zero conservati.
- Cleanup di `curl_exec`: protezione e attesa del distacco nativo nello stesso
  scope, eliminando closure e due attivazioni interne per richiesta. Il livello
  di protezione esterno viene ripristinato anche durante cancellazione/errori.
- Risposte cURL: percorso UTF-8 con validazione tramite round trip; byte non
  validi e NUL rimangono integri, senza usare eccezioni per normali dati binari.
- Dispatch: ciclo diretto per preparare gli argomenti, ritorni scalari NFI senza
  predicati interop ripetuti, tracking dei valori primitivi inlinabile. Il
  percorso generico resta per oggetti interop e floating point.
- Build: shim C ottimizzato e Native Image `-O3 -march=compatibility` su entrambi
  i sistemi. L'aumento della dimensione dell'eseguibile è esplicito nei risultati.

L'A/B della variante intermedia `-O1` (cleanup/stringhe/shim e collector)
mostrava +17,8% e +11,8% di throughput. Il passo successivo combina le modifiche
al dispatch e `-O3`: non consente di attribuire il guadagno a una sola modifica.
Le varianti diagnostiche sono separate dal prodotto; i normali build Windows e
Linux forzano `curl_costs=n`.

## Verifica e riproduzione

La suite cURL passa su Windows e Linux, JVM e Native Image, e sull'oracolo
TrueAsync Windows. Comprende HTTPS con verifica CA, redirect, gzip, POST,
header, errori, timeout, riuso, 16 chiamate bloccate contemporaneamente sul peer,
multi e convivenza dei provider. Aggiunti byte UTF-8 non validi/NUL e
cancellazione dentro una protezione esterna, seguita dal riuso dell'handle.
Il numero di assert include il polling multi e può variare con il completamento.

Passano anche 53 scenari del modello dei valori e 256 grafi di ownership,
con 1.280 fasi di raccolta confrontate con la raggiungibilità e rilascio
esattamente una volta, su entrambe le piattaforme. Non è una nuova esecuzione
dell'intera matrice storica di test del progetto.

I prodotti finali sono `build/graalphp.exe` e `build/graalphp-linux-x64`,
ricompilati dai sorgenti verificati. Linux mantiene libc, libm e loader di
sistema; Windows mantiene le tre DLL runtime MSVC. Le stringhe dei contatori
diagnostici sono assenti dai prodotti e dai bridge normali.
[Esiti](validation/curl-optimization-2026-09-25/results.txt) e
[hash di sorgenti, prodotti e oracoli](validation/curl-optimization-2026-09-25/hashes.json).


```text
build.bat curl-test native
build.bat curl-test trueasync
build.bat curl-benchmark 3 1024 64 0
bash build.sh curl-test native
```

Le opzioni del benchmark sono ripetizioni, richieste per coroutine, client,
ritardo del peer e, opzionalmente, popolazioni sospese separate da virgola.
Per l'A/B impostare `GRAALPHP_BENCH_BASELINE` al percorso del binario di controllo;
il driver alterna i due prodotti. Eseguire i benchmark in sequenza senza build
concorrenti. `build.bat profile-native` abilita JFR; `build.bat reactor-probe
curl-costs` crea una variante con timer Java separata. I timer C richiedono
l'opzione Xmake esplicita `--curl_costs=y`; `BuildSupport.java curl-states` genera
le copie strumentate in `build/`, senza modificare i sorgenti scaricati.

[Dati e driver conservati](benchmarks/curl-optimization-windows-2026-09-25),
[aggregate diagnostiche](benchmarks/curl-optimization-windows-2026-09-25/diagnostic-summary.json)
e [profilo iniziale](benchmarks/curl-optimization-windows-2026-09-25/profile/summary.md).
