# Storage PHP, iterazione SPL e verifica da freddo

La baseline è il sorgente Git `d356a2e`. Il prodotto include la rappresentazione
packed, il passaggio alla mappa associativa, il lookup posizionale SPL,
ArrayObject da array e i quattro builtin di estremità. Il carico `walk.php`
costruisce l'array, crea un ArrayIterator e verifica la somma delle chiavi e dei
valori. Nella variante con buco elimina la chiave 1 prima del foreach.

## Riproduzione Windows

Conservare il JAR compilato dal sorgente precedente come
`build/nov-baseline.jar`, nella stessa directory di `deps`. Costruire il
candidato con `build.bat iteration-test native`. Il seguente confronto usa
processi CLI nuovi e include bootstrap, JIT e terminazione.

```powershell
uv run --no-project python docs/validation/array-storage-2026-11-18/windows_walk.py build/nov-baseline.jar build/graalphp.jar
```

Il driver inverte l'ordine nella seconda ripetizione di ogni configurazione.
La durata è wall time dal lancio all'uscita. La CPU è la somma dei tempi Windows
user e kernel del processo. La RAM è il massimo PeakWorkingSetSize osservato
mentre il processo è vivo. I dati di un workload breve dipendono anche dalle
cache del sistema e dalla risoluzione dei contatori. Tre ripetizioni per gruppo
descrivono questo host. Il CSV contiene ogni run, il checksum e gli hash.

## Funzionalità

I programmi del corpus confrontano l'output con gli oracoli PHP 8.6 e TrueAsync.
I casi negativi richiedono il rifiuto semantico previsto. I nuovi casi coprono
promozione sotto foreach per valore e per reference, COW, estremità dopo unset e
reinserimento, cloni degli iteratori, exchangeArray e sostituzione di reference.
| Host | JVM | Native Image | Native Image `--interpreter` | Regressione |
| --- | --- | --- | --- | --- |
| Windows x64 | [94/94](windows-jvm.txt) | [94/94](windows-native.txt) | [94/94](windows-interpreter.txt) | [verify](windows-verify.txt) |
| Linux x64 (WSL) | [94/94](linux-jvm.txt) | [94/94](linux-native.txt) | [94/94](linux-interpreter.txt) | [verify](linux-verify.txt) |

Ogni modalità comprende 88 confronti di output e 6 rifiuti semantici. La
regressione generale esercita anche reload, rete, NFI e collector. Windows usa
PHP 8.6 RC2 e TrueAsync 0.10.0 e include 82 confronti TrueAsync nella regressione.
Su Linux l'oracolo dei 94 programmi è PHP 8.6-dev di TrueAsync 0.10.0 e
`build.sh verify` esegue le suite integrate. [Hash e dimensioni](products.txt).

## Misure dello storage su Windows

Mediane di tre processi per riga. Le prime righe di ciascun gruppo usano il JAR
di ottobre ricostruito con la medesima toolchain, le successive il JAR corrente.
Il confronto fra eseguibili usa PHP 8.6.0RC2 con `-n` e il Native Image corrente.
Il tempo misura l'intera invocazione che costruisce e attraversa l'array.

| CLI | Elementi iniziali | Layout | Wall ms | CPU ms | Picco RSS MiB |
| --- | ---: | --- | ---: | ---: | ---: |
| JVM prima | 256 | dense | 838.95 | 3375.00 | 382.60 |
| JVM ora | 256 | dense | 777.21 | 2640.62 | 366.33 |
| JVM prima | 256 | hole | 790.56 | 3218.75 | 387.31 |
| JVM ora | 256 | hole | 741.52 | 2687.50 | 357.71 |
| JVM prima | 1024 | dense | 1262.74 | 5921.88 | 658.27 |
| JVM ora | 1024 | dense | 943.10 | 4203.12 | 500.46 |
| JVM prima | 1024 | hole | 1286.91 | 6171.88 | 625.09 |
| JVM ora | 1024 | hole | 970.29 | 4531.25 | 515.71 |
| PHP RC2 | 256 | dense | 31.13 | 31.25 | 16.04 |
| Native Image | 256 | dense | 35.26 | 46.88 | 30.48 |
| PHP RC2 | 256 | hole | 35.97 | 31.25 | 9.86 |
| Native Image | 256 | hole | 34.16 | 31.25 | 32.20 |
| PHP RC2 | 1024 | dense | 31.79 | 31.25 | 14.91 |
| Native Image | 1024 | dense | 124.77 | 234.38 | 125.11 |
| PHP RC2 | 1024 | hole | 35.53 | 31.25 | 16.06 |
| Native Image | 1024 | hole | 124.89 | 250.00 | 125.33 |


Per 1.024 elementi il lavoro end-to-end sul JAR richiede circa il 25% di tempo
in meno, sia contiguo sia con un buco. Il confronto isola il blocco complessivo
di modifiche. Non attribuisce individualmente il risultato al JIT, al costruttore
COW, alla rimozione della tabella hash o alla lettura posizionale. L'eseguibile
corrente impiega circa 125 ms sul caso da 1.024 elementi, contro 32–36 ms
dell'oracolo PHP in questo ambiente.

## cURL standard su Windows

Tre ripetizioni, 64 client HTTP/1.1, 1.024 richieste per client, payload 1 KiB
con verifica di ogni risposta, processi e peer appena avviati. Le 24 prove
comprendono 1.572.864 richieste. Le prove senza cronometro forniscono mediane
di richieste/s e CPU per richiesta, e percentili temporali RSS sui campioni
raggruppati. Le prove abbinate con cronometro forniscono 12.288 campioni di
latenza per riga, con nearest rank. La finestra totale include avvio e rilascio
delle coroutine fino alla notifica di completamento del lavoro.

| Runtime | Sospese | Req/s | CPU µs/req | RSS p50 MiB | Picco RSS MiB | p20 ms | p50 ms | p90 ms | p95 ms | p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| graalphp | 1000 | 23298.566 | 44.584 | 84.520 | 155.465 | 2.362 | 2.526 | 3.303 | 3.693 | 5.568 |
| trueasync | 1000 | 26216.639 | 37.432 | 36.969 | 36.980 | 2.132 | 2.287 | 2.982 | 3.232 | 3.798 |
| graalphp | 10000 | 22572.718 | 47.445 | 115.121 | 189.625 | 2.366 | 2.509 | 3.064 | 3.428 | 4.414 |
| trueasync | 10000 | 16248.563 | 43.631 | 160.691 | 160.824 | 2.124 | 2.284 | 3.089 | 3.325 | 4.379 |

[Ambiente e hash](curl/environment.txt), [risultati per prova](curl/results.csv)
e [campioni SPL](windows-walk.csv). Questo confronto descrive lo stato
corrente di GraalPHP rispetto a TrueAsync. Per attribuire una variazione di
rete allo storage servirebbe anche il vecchio Native Image nella stessa campagna.
