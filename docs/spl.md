# SPL concreta: EmptyIterator e ArrayIterator parziale

Avvio del 10 ottobre 2026, dopo ArrayAccess. La superficie SPL resta
incompleta: questo è un passo incrementale di LANG-01, non una certificazione
che le classi SPL o Composer siano supportati.

## EmptyIterator

`EmptyIterator` è un tipo builtin visibile a `new`, `instanceof`,
`class_exists` e agli strumenti di iterazione esistenti. Viene compilato sullo
stesso Truffle Bytecode DSL degli altri metodi PHP, senza un interprete o uno
scheduler dedicato. Non è `final`, e implementa `Iterator`/`Traversable`.

`valid()` restituisce sempre `false`; `rewind()` e `next()` non hanno effetti.
`current()` e `key()` lanciano rispettivamente `BadMethodCallException` con i
messaggi PHP `Accessing the value/key of an EmptyIterator`. Le firme interne
usano `never` per questi ultimi metodi e `false` per `valid()`, preservando la
covarianza delle override. La rappresentazione delle eccezioni segue ancora
`PhpError`: il modello generale delle classi Throwable, la gerarchia delle
eccezioni SPL e Reflection dettagliata rimangono lavoro aperto.

La proibizione di `foreach` by-reference sugli iteratori non-generatore
produce ora un `Error` anziché un'`Exception`, rendendola intercettabile
coerentemente con PHP 8.6.

## ArrayIterator, 10 ottobre 2026

La nuova interfaccia `SeekableIterator` estende `Iterator`. La classe builtin
`ArrayIterator` implementa `SeekableIterator`, `ArrayAccess` e `Countable`,
attraverso i metodi PHP compilati normalmente sul Truffle Bytecode DSL. Il
sottoinsieme corrente comprende il costruttore da array, `getArrayCopy`,
`count`, `rewind`, `valid`, `key`, `current`, `next`, `seek`, `offsetExists`,
`offsetGet`, `offsetSet`, `offsetUnset` e `append`.

Il costruttore fa una copia dei valori che **stacca le reference** esplicite
dell'array iniziale. I cloni condividono invece l'identità del contenitore
backing, pur conservando ciascuno il proprio indice di cursore: una modifica
tramite il clone è osservabile anche dall'originale. Il contenitore è un
normale oggetto nello heap PHP; la COW degli array usa quindi il sistema di
ownership già esistente, senza un altro storage Java parallelo.

`key` ricava i nomi in ordine dal modello array; `offsetExists` verifica
l'esistenza della chiave (anche quando il valore è null), mentre `seek` genera
`OutOfBoundsException` per posizioni fuori range. La cancellazione di una
chiave prima del cursore ne aggiorna l'indice. Metodi e subclassi possono
sospendersi tramite TrueAsync senza percorsi sincroni speciali.

**Limiti deliberati:** costruzione da oggetto con deprecazione PHP 8.6,
`Serializable`, sorting, `getFlags`/`setFlags`, semantica dei flag
`STD_PROP_LIST`/`ARRAY_AS_PROPS`, accesso by-reference agli offset e
`ArrayObject` rimangono da implementare. Il parametro flags e accettato ma
non ancora applicato. L'accesso a un offset inesistente, `isset` su offset
con valore null, i dettagli della diagnostica e le modifiche incrociate
al cursore fra cloni richiedono ulteriori confronti con PHP. `key` fa per ora
una materializzazione ordinata delle chiavi: non è una primitiva prestazionale.

L'esempio mantenuto [`examples/arrayiterator.php`](../examples/arrayiterator.php)
mostra foreach, clone, append e seek sullo stesso backing condiviso.

## Verifica e limiti

I tre casi aggiunti a `IterationTest` confrontano protocollo vuoto, errori e
sottoclasse con l'oracolo PHP 8.6. La suite Windows/JVM dopo l'intervento passa
**77 programmi** in JVM, Native Image e `--interpreter`: 231 esecuzioni,
con 6 rifiuti semantici per modalità. Risultati e hash in
[`validation/spl-2026-10-10/`](validation/spl-2026-10-10/README.md).
Per `ArrayIterator`, altri 12 programmi confrontano valori, reference, clone,
seek, offset, mutazioni e metodi sospendibili. Passano insieme al corpus
precedente: **89/89** su Windows JVM, Native Image e medesimo Native Image
`--interpreter`, quindi **267 esecuzioni** (83 confronti output e 6 rifiuti
semantici per modalità). `build.bat verify` passa con 91 integrazioni e
82/82 TrueAsync, oltre ai controlli su valori, ownership e metadata. I
risultati e gli SHA-256 sono conservati in
[`validation/arrayiterator-2026-10-10/`](validation/arrayiterator-2026-10-10/README.md).
Linux non è stato riverificato; nessuna nuova misura prestazionale è stata
eseguita e la prova non copre la SPL completa.

Passi successivi: completamento `ArrayIterator`, `ArrayObject`, accessi by-reference,
lifetime/ownership, firme e gerarchia delle eccezioni SPL, e relativo corpus
differenziale con TrueAsync/Native Image. Per la compatibilità di caricamento
restano aperti il loader SPL predefinito e `include_path`.
