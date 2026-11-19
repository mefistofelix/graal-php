# Architettura di esecuzione e criteri di completamento

Revisione del 18 novembre 2026. Il target resta PHP 8.6 su Truffle Bytecode DSL.
La scelta è concentrare le operazioni frequenti sul proprietario dei dati. Il
frontend descrive la semantica PHP, il lowering sceglie operazioni specializzate,
lo storage applica reference e COW, lo scheduler custodisce le riprese. Si può
quindi estendere la stessa operazione a un builtin, a SPL o a una coroutine
senza duplicare il modello dei valori.

| Confine | Decisione tecnica | Verifica che determina il passaggio successivo |
| --- | --- | --- |
| Codice e richieste | Usare l'entry compilata dal frontend e risolvere gli include al primo uso senza watch. Con watch, IR e target appartengono alla generazione immutabile. Le cache di funzioni/metodi devono considerare generazione, ricevente, visibilità e dichiarazioni condizionali della richiesta. | Lo stesso call site lavora su due richieste e durante reload. L'autoload può sospendersi, un errore di pubblicazione conserva la generazione precedente e la CLI compila soltanto i file usati. |
| Valori locali | Usare dimensione, ordine e lookup direttamente sullo storage COW. La SPL conserva cursori indipendenti e condivide il backing quando l'oracolo lo richiede. La cardinalità non alloca un elenco. | Confrontare array vuoti, chiavi miste/binarie, buchi, reference e scritture dopo clone. Misurare carico completo da processo nuovo a dimensioni diverse. |
| JIT | Mantenere specializzabile il percorso scalare e spostare diagnostica e operazioni rare dietro confini Truffle. Gli array sequenziali usano bucket contigui indicizzati direttamente. Una chiave incompatibile o un unset promuove lo storage a una mappa ordinata. | Verificare prima alias, append dopo unset, COW e iteratori by-reference. Confrontare wall time, CPU e RSS sia per array packed sia associativi. |
| Coroutine e lifetime | Continuation Truffle su sospensione effettiva. Lo scheduler possiede il task fino al completamento. I future PHP possiedono il risultato nel grafo guest e le sottoscrizioni mantengono vive le sorgenti fino a consegna, distacco o cancellazione. | Verificare completamento immediato, race con cancellazione, cleanup e distruttori osservabili. L'oracolo è TrueAsync per l'API async e PHP per il lifetime dei valori. |
| Thread PHP | Un solo runtime e heap. Il percorso locale resta confinato. La pubblicazione verso un altro worker deve promuovere tutto il grafo raggiungibile, preservando cicli, reference e identità, prima di renderlo osservabile. | Prima di esporre lo spawn pubblico dimostrare pubblicazione concorrente, assenza di corruzione e raccolta dei cicli tra thread. Mutex e ThreadChannel forniscono la sincronizzazione esplicita. |
| I/O e C | Il proprietario PHP avanza libuv e i trasferimenti cURL. Le chiamate FFI bloccanti usano il pool nominato. Il bridge conserva lo stack C e riconsegna la callback alla coroutine. Per ampliare CData servono proprietario, ABI e lifetime espliciti per ogni buffer. | Verificare callback attraverso sospensione, riuso dopo cancellazione, saturazione del pool seriale e rilascio dopo il ritorno fisico del C. In embedding si sveglia il dispatcher alla readiness o alla deadline reale. |
| Distribuzione | Una implementazione Truffle su JVM e Native Image. Xmake produce gli archivi e il bridge dagli stessi sorgenti. Il profilo musl full-static e il caricamento esterno hanno criteri distinti. | Riprodurre da ambiente pulito, verificare dipendenze effettive e programmi identici sui target. Compose e mobile seguono il completamento delle fondamenta. |

## Prima applicazione nello storage

Gli array con chiavi contigue da zero conservano soltanto la sequenza di bucket.
Le letture convertono la chiave PHP alla forma canonica e verificano i limiti
prima di usarla come indice. Una chiave stringa, negativa o discontinua e la
prima cancellazione costruiscono la mappa dai bucket esistenti. La promozione
mantiene gli stessi slot e le stesse celle reference. Ogni copia COW conserva
le posizioni degli iteratori vivi e il prossimo indice intero PHP. Lo snapshot
delle chiavi packed usato da foreach per valore trattiene la sola lunghezza.
Il registro degli iteratori by-reference viene allocato alla prima registrazione.

Il percorso precedente di `ArrayIterator::key` materializzava un array PHP di
tutte le chiavi a ogni passo. Su n elementi un attraversamento effettuava
lavoro quadratico. `PhpArray.keyAt` ora legge il bucket per indice. Se gli unset
hanno lasciato buchi costruisce sulla prima lettura posizionale un elenco delle
sole chiavi vive. Un'inserzione o rimozione invalida quell'elenco. La memoria
extra è O(n) solo sugli storage con buchi effettivamente letti per posizione.
Un attraversamento con ordine stabile è O(n). Alternare mutazioni strutturali e
letture su un array con buchi può ancora ricostruire l'indice a ogni passo.

Il costruttore da array condivide lo storage via COW. La sostituzione di uno
slot SPL separa il valore dalla reference sorgente al momento della scrittura.
La cardinalità e le estremità usano la mappa ordinata direttamente. La stessa
rappresentazione serve i quattro builtin `array_first`, `array_last`,
`array_key_first`, `array_key_last` e il sottoinsieme da array di `ArrayObject`.

## Ownership nei future

L'heap PHP visita il risultato di un future come gli altri archi del grafo.
`FutureState` e i suoi handle condividono completamento e cancellazione. I
future di un `map`, `catch` o `finally` hanno un risultato proprio e staccano
il collegamento al task sorgente quando si risolvono o quando vengono
rilasciati. Il task attivo mantiene il proprio future. Quando termina cede
il risultato e rilascia activation e registrazione attiva. Lo scheduler
conserva gli errori necessari alla policy di fine scope.

Il passaggio da chiamata PHP a continuation mantiene vivi sorgente e token.
La registrazione successiva conserva le radici fino a consegna o cancellazione.
Il risultato viene acquisito prima di rilasciare l'attesa. Una ricezione
`Channel::recvAsync` pendente si rimuove dalla coda quando perde l'ultimo
proprietario. I segnali degli adapter nativi e dei worker continuano a
completarsi sul proprio percorso. L'ownership dei valori guest è modificata
sul thread proprietario.

## Confini per le prossime milestone

Il return-by-reference trasporta una cella posseduta oltre la chiamata e
la continuation. Il chiamante per valore ne legge il valore corrente e
rilascia la cella secondo le regole COW. Gli argomenti valutati prima di una
sospensione possono avere sia uno snapshot sia una cella per il binding.
La cella condivisa verifica i tipi dichiarati dagli slot che la possiedono,
con coercizione per sito di scrittura. Il prossimo lavoro riguarda gli hook e
i casi di modifica indiretta sugli offset restituiti per valore.
[Contratto ArrayAccess](arrayaccess.md).

Il collector accoda i distruttori mantenendo una radice temporanea. Le
operazioni guest drenano la coda dopo la transizione dello slot sul
dispatcher proprietario. Il ciclo viene riesaminato dopo i callback, così
la resurrezione può preservare identità e riferimenti deboli.
Restano da verificare gli altri errori e punti semantici di rilascio.
[Contratto di lifetime](object-lifetime.md).

## Sequenza di integrazione

1. Usare questa base per completare lifetime e policy degli errori async.
   Distruttori, reference e gestione degli errori sono prerequisiti anche per
   le strutture condivise e i callback nativi persistenti.
2. Validare LOCAL→SHARED con il collector, poi pubblicare lo spawn sullo stesso
   heap e permettere i grafi completi nel ThreadChannel.
3. Estendere modello CData, binding delle librerie e caricamento dinamico con
   test combinati di lifetime e cancellazione. Completare linguaggio, SPL e
   loader secondo i contratti PHP.
4. Chiudere packaging, embedding GUI e target mobili con applicazioni reali.
   Ogni milestone chiude soltanto le righe verificate della mappa di copertura.

Le campagne cURL del 25–26 settembre misurano il percorso di rete. Per una
modifica allo storage, misurare anche iterazione PHP e pressione di memoria
prima di attribuirle un impatto sul server. Startup, compilazione e prime
operazioni entrano nel denominatore. Confrontare RAM residente, tempo CPU e
wall time a parità di risultati. Per I/O conservare anche p50/p95/p99 e prove
senza campionamento delle richieste. Le attività aperte hanno ID stabili in
[TODO](../TODO.md) e una [mappa di copertura](design-coverage.md).
