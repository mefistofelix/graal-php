# Generatori PHP

Blocco funzionale del 27 settembre 2026, successivo a
[Reflection e attributi runtime](reflection.md). I generatori usano direttamente
le continuation del Truffle Bytecode DSL: non precomputano sequenze e non
introducono un interprete parallelo o uno stack guest separato.

## Sintassi e avvio lazy

Il frontend riconosce `yield`, `yield key => value` e `yield from` e marca
la funzione/closure/metodo come generator. Chiamare una funzione generator
valida normalmente argomenti e tipi, ma non esegue il corpo: restituisce un
oggetto interno `Generator`. Il corpo parte soltanto quando il consumer usa
`current()`, `key()`, `valid()`, `rewind()`, `next()`, `send()`,
`throw()` o un percorso di iterazione.

Ogni generator possiede un task Scheduler e una activation, ma un generator
dormiente non è contato come membro async attivo della richiesta. Il primo
consumer entra nel normale target Bytecode DSL; `yield` produce una
continuation Truffle e trasferisce chiave/valore con ownership esplicito.
Sospensioni TrueAsync dentro il corpo, incluse `Async\\delay()`, restano sullo
stesso task/continuation.

La dichiarazione di ritorno è controllata al momento della compilazione:
`Generator`, `Iterator`, `Traversable`, `iterable`, `object`,
`mixed` e unioni compatibili accettano una funzione generator; tipi
incompatibili come `int` sono rifiutati come dichiarazione. Il valore di un
`return` interno non viene invece validato contro quel tipo: è il valore
osservato da `Generator::getReturn()`, come in PHP.

## Stato, chiavi e metodi

Sono implementate identità e interfacce `Generator`, `Iterator` e
`Traversable`, insieme ai metodi PHP:

- `current()`, `key()`, `valid()` e `rewind()`;
- `next()` e `send()`, compreso l'uso prima del primo `yield`;
- `throw()`, con consegna dell'eccezione dentro la continuation;
- `getReturn()`, valido solo dopo un ritorno normale.

Le chiavi automatiche seguono il contatore PHP anche dopo chiavi numeriche
esplicite, negative o stringa. `new Generator` e il clone di un generator
sono rifiutati. `ReflectionFunction::isGenerator()` e
`returnsReference()` espongono il metadato della funzione.

I builtin di iterazione già implementati consumano i generator attraverso il
medesimo protocollo: `iterator_to_array`, `iterator_count`,
`iterator_apply` e `foreach` non hanno una copia eager speciale.

## `yield from`

La delega conserva il consumer esterno e inoltra `next`, `send` e
`throw`. Un generator delegato espone direttamente chiave/valore e il suo
`getReturn()` diventa il risultato dell'espressione `yield from`.

Array e oggetti `Traversable` sono adattati sul normale protocollo foreach,
quindi un Iterator utente può sospendersi nei propri metodi. Un valore scalare
non iterabile produce l'errore PHP previsto. I generator dichiarati
by-reference rifiutano `yield from`, coerentemente con PHP.

## Reference

Una funzione dichiarata `function &g()` produce un generator iterabile
by-reference. Quando il `yield` espone una vera location, `foreach (... as
&$value)` mantiene quella location e le scritture aggiornano il valore
sorgente. Un generator non by-reference rifiuta foreach by-reference.

Se una funzione generator by-reference produce un valore che non è una
location, viene emesso il notice PHP corrispondente e il runtime conserva una
location temporanea per lo yield corrente. Il frontend non inventa una sintassi
`yield &$x` separata.

## Lifetime, distruzione e cancellazione

Il receiver guest viene mantenuto vivo mentre un metodo Generator è sospeso:
espressioni temporanee come `g()->current()` non possono distruggere il
generator prima della ripresa.

La distruzione distingue due casi:

- un generator mai avviato chiude activation/storage senza eseguire il corpo;
- un generator sospeso viene ripreso con un unwind interno `GeneratorExit`
  non catturabile dal guest. I `finally` attraversati dalla continuation
  vengono quindi eseguiti, mentre un `catch (Throwable)` non può intercettare
  la chiusura e continuare il corpo.

Il cleanup resta conteggiato nello Scheduler fino alla fine dell'unwind e
rilascia anche un eventuale generator delegato. La cancellazione TrueAsync usa
lo stesso meccanismo generale di continuation/finally: il corpus include un
generator sospeso in `Async\\delay()` cancellato da una coroutine esterna.

Questo comportamento è parte del lifetime guest; non viene affidato al GC Java.

## Verifica

`tests/graalphp/GeneratorTest.java` contiene 37 programmi: 35 confrontano
l'output con PHP 8.6 o TrueAsync 0.10.0 e due richiedono rifiuto semantico.
Coprono lazy start, chiavi, rewind, send/throw/getReturn, return type, closure,
metodi/trait, `yield from` su array/generator/Iterator, forwarding send/throw,
by-reference, distruzione/finally, receiver temporanei, sospensioni/cancellazione
TrueAsync, builtin Iterator e Reflection.

Lo stesso corpus passa su Windows e Linux in JVM, Native Image e sullo stesso
Native Image con `--interpreter`: **222 esecuzioni** complessive, 210 confronti
di output e 12 rifiuti semantici. I messaggi completi dei due rifiuti non sono
asseriti byte-per-byte.

Regressioni dello snapshot finale:

- Windows `verify`: 91 integrazioni, 53 scenari valori, 256 grafi / 1.280 fasi
  collector, 56 field-iteration e 52 metadata;
- Linux `verify`: 85 integrazioni con gli stessi 53 / 256 / 1.280 / 56 / 52;
- TrueAsync sul prodotto Native Image: 82/82 scenari sia Windows sia Linux.

Le evidenze sono in
[`docs/validation/generators-2026-09-27/`](validation/generators-2026-09-27/).

L'esempio mantenuto [`examples/generators.php`](../examples/generators.php)
combina `send`, delega, return value e `finally`.

## Limiti

Questo blocco implementa i generatori, non `Fiber`: l'API Fiber e le sue
interazioni con TrueAsync restano il prossimo blocco di LANG-01. Restano inoltre
aperti i limiti generali già tracciati per gerarchia completa Throwable,
diagnostiche byte-identiche, shutdown/lifetime PHP globale, loader SPL,
Reflection avanzata e typing dei builtin rimanenti.

Non è stata eseguita una campagna prestazionale e non sono cambiati default
JIT/GC. I numeri di Native Image nei log sono soltanto evidenza di build.
