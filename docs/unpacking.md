# Argument unpacking e array spread

Blocco funzionale del 26 settembre 2026, successivo a
[iterazione sospendibile](iteration.md). Aggiunge `...` nelle chiamate e negli
array usando la stessa pipeline frontend → IR → Truffle Bytecode DSL e le stesse
continuation del runtime. Non introduce un interprete alternativo, worker
aggiuntivi o una nuova campagna prestazionale.

## Chiamate

Sono supportati argomenti unpacked da array e Traversable per funzioni, metodi,
metodi statici, costruttori e callable dinamici. Le espressioni vengono
valutate nell'ordine sorgente; dopo l'unpack possono seguire argomenti nominati,
ma non posizionali. Un unpack dopo un argomento nominato è rifiutato dal
frontend come in PHP.

Le chiavi intere diventano argomenti posizionali e le stringhe argomenti
nominati. L'ordine del Traversable viene conservato senza trasformarlo prima in
un normale array: questo è necessario perché chiavi nominate duplicate devono
produrre errore e una chiave intera successiva a una stringa deve essere
rifiutata. I duplicati fra più spread o fra spread e argomento nominato
esplicito non vengono silently sovrascritti.

Per un array contenuto in una variabile, i parametri by-reference possono
legarsi direttamente agli elementi dell'array, come PHP. Uno spread che deriva
da una proprietà, da un offset o da un'espressione temporanea usa invece un
temporaneo: non propaga per errore la scrittura alla struttura contenitore.
Un Traversable materializza valori temporanei; se il parametro destinazione è
by-reference viene emesso l'avviso PHP e la funzione riceve una locazione
temporanea modificabile, senza mutare retroattivamente il valore dell'Iterator.

Un Traversable può sospendersi in `rewind`, `valid`, `current`, `key` o
`next`. Il materiale unpacked rimane radicato nell'activation chiamante durante
la sospensione e conserva nomi, valori e locazioni necessarie. Autoload,
strict_types e named arguments vengono applicati dal normale percorso di
chiamata, non da un binder parallelo.

## Array spread

`[...$value]` accetta array e Traversable. Le chiavi intere sono reindicizzate,
le chiavi stringa sono conservate e i valori stringa successivi sovrascrivono
quelli precedenti mantenendo le regole d'ordine dell'array PHP. Le reference
esplicite presenti in un array sorgente restano reference; i valori ordinari
mantengono copy-on-write per array annidati.

Anche lo spread di un Traversable è sospendibile. Per gli array di costante di
classe, lo spread viene valutato nel percorso delle espressioni costanti e
mantiene le stesse regole di reindicizzazione/sovrascrittura. Le reference non
sono comunque ammesse nelle espressioni costanti.

## Implementazione e ownership

Il frontend rappresenta separatamente `UnpackArgument` e le entry array con
flag `unpack`. Lo spread di chiamata usa due forme di sorgente:

- un array, con la locazione della sola variabile sorgente quando PHP permette
  di osservare modifiche by-reference;
- una sequenza raccolta da Traversable che conserva anche chiavi duplicate e
  usa locazioni temporanee possedute.

La raccolta Traversable avviene attraverso helper PHP riprendibili in
`IterationApi.SOURCE`; non esegue un loop Java che aggiri scheduler e
cancellazione. Le risorse temporanee sono registrate nell'activation originaria
e vengono chiuse anche su eccezione/cancellazione. Il loro lifetime è per ora
quello dell'activation, non ancora ridotto al minimo della singola chiamata:
la riduzione dei receiver/temporanei rimane parte di VALUE-01.

Lo spread di array, invece, materializza il Traversable secondo le normali
regole dell'array perché in quel caso chiavi stringa duplicate devono davvero
sovrascriversi.

## Verifica

`tests/graalphp/UnpackTest.java` usa gli stessi programmi PHP per oracolo e
runtime. Copre ordine di valutazione, named arguments, duplicati, reference,
temporanei, callable/autoload, strict types, Traversable sincroni/asincroni,
merge degli array, COW e costanti di classe.

Windows:

```bat
build.bat unpack-test
build.bat verify
build.bat unpack-test native
build\graalphp.exe examples\unpacking.php
```

Linux usa `bash build.sh unpack-test` con `PHP_ORACLE` e
`TRUEASYNC_ORACLE` espliciti; dopo `bash build.sh native`, la variante
`unpack-test native` usa quel prodotto.

Gli esiti multipiattaforma sono conservati in
[validation/unpacking-2026-09-26](validation/unpacking-2026-09-26/README.md):
46/46 programmi passano su Windows/Linux JVM, Native Image e Native Image
`--interpreter`, per 276 esecuzioni complessive. Sono archiviati anche la prima
build Windows rifiutata dalla blocklist e il successivo fix con TruffleBoundary.

## Limiti

Questo blocco non implementava generatori/Fiber, ArrayAccess, l'intera SPL,
Reflection completa o tutte le firme builtin. Reflection e generatori sono
stati aggiunti nei blocchi successivi; Fiber e gli altri limiti restano aperti. I nomi binari non UTF-8 usati
come named-argument key richiedono ancora una rappresentazione del nome
argomento non limitata a `String`. Il frontend non aggiunge con questo blocco
la dichiarazione `const` globale: le costanti di classe già supportate coprono
lo spread nelle espressioni costanti.

Non chiude LANG-01 o VALUE-01 e non implica compatibilità Composer generale.
Le verifiche prestazionali rimangono rinviate per richiesta dell'utente.
