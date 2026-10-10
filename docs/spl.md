# SPL concreta: primo blocco

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

## Verifica e limiti

I tre casi aggiunti a `IterationTest` confrontano protocollo vuoto, errori e
sottoclasse con l'oracolo PHP 8.6. La suite Windows/JVM dopo l'intervento passa
**77 programmi** in JVM, Native Image e `--interpreter`: 231 esecuzioni,
con 6 rifiuti semantici per modalità. Risultati e hash in
[`validation/spl-2026-10-10/`](validation/spl-2026-10-10/README.md).
Linux non è stato verificato per questa modifica; la prova non copre la SPL
completa.

Passi successivi: `ArrayIterator`, `ArrayObject`, accessi by-reference,
lifetime/ownership, firme e gerarchia delle eccezioni SPL, e relativo corpus
differenziale con TrueAsync/Native Image. Per la compatibilità di caricamento
restano aperti il loader SPL predefinito e `include_path`.
