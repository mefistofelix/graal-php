# Enum, match, clonazione, stringhe e strict_types

Blocco funzionale del 26 settembre 2026, successivo ai
[contratti delle classi](class-contracts.md). Riprende l'implementazione enum
rimasta non committata, corregge i casi emersi dal confronto differenziale e
aggiunge offset di stringa e typing rigoroso per sorgente.

Non introduce un secondo interprete: il frontend produce IR e il compiler
usa la stessa Truffle Bytecode DSL, incluse le continuazioni. Nessun nuovo
benchmark, cambio del compiler idle delay, configurazione GC o dipendenza.

## Enum e valori costanti

Sono rappresentati enum senza backing e con backing `int`/`string`, casi,
metodi, interfacce e trait compatibili. `UnitEnum` e `BackedEnum` sono veri
contratti nel modello delle classi; `cases`, `from` e `tryFrom` sono metodi
builtin generati nella normale pipeline. Gli enum sono finali, non costruibili
con `new` e non clonabili. Le restrizioni su proprietà, magic methods e metodi
generati sono verificate dal linker, anche quando le proprietà arrivano da trait.

Ogni caso è un oggetto singleton **della richiesta**. Il registro dei casi e
quello dei valori backing appartengono alla RuntimeClass della richiesta,
non alla Definition condivisa dalla generazione. I root di ownership sono
espliciti nelle proprietà statiche interne; le mappe Java non aggiungono heap
PHP nascosti. `name` e `value` sono readonly e non si possono acquisire per
riferimento, cancellare o modificare attraverso un offset di stringa.

Il risultato di `cases()` è un array PHP separato, ordinato per dichiarazione.
La modifica di quell'array non altera i singleton. Le conversioni di
`from`/`tryFrom` rispettano il modo del chiamante, compresi errori di tipo,
valori non presenti e deprecazioni delle conversioni deboli verificate contro
le versioni fissate. Valori stringa numerici distinti non diventano chiavi
intere della lookup. Il controllo dei valori duplicati o di tipo errato segue
il momento di validazione osservato nell'oracolo: `cases()` non è trasformato
arbitrariamente in una validazione anticipata completa.

Gli inizializzatori possono usare costanti di classe, array costanti e relativi
offset, `name`/`value` di altri casi enum, operatori già disponibili,
coalescenza e condizionali con valutazione corta. I temporanei delle espressioni
costanti sono rilasciati anche quando una dipendenza fallisce. Non è implementato
un linguaggio distinto per il corpo degli enum.

## Match, throw expression e clonazione

`match` usa identità PHP stretta: tipo e valore per gli scalari, contenuto e
ordine delle chiavi per gli array, identità per gli oggetti e i casi enum.
Il confronto ricorsivo degli array preserva l'ownership dei valori letti.
Il controllo delle strutture ricorsive è esplicito, non una ricorsione Java
illimitata. Il limite difensivo corrente è 512 livelli.

Le condizioni e i risultati degli arm vengono valutati solo quando necessari.
`default` è scelto solo dopo il mancato match degli arm; un match non esaustivo
lancia `UnhandledMatchError`. Il soggetto non viene rieseguito. Si conserva
anche la distinzione verificata in PHP tra una variabile letta dopo la
valutazione della condizione dell'arm e un'espressione/proprietà già acquisita
come valore. Il caso che muta il soggetto non è stato sostituito con un test
più semplice per ottenere un risultato favorevole.

Sono disponibili `throw` come espressione e `clone` per oggetti utente e
closure. La clonazione crea un oggetto distinto, conserva i riferimenti
espliciti condivisi e lascia gli array in copy-on-write. L'eventuale `__clone`
si esegue sul nuovo oggetto, con visibilità e continuazioni ordinarie: può
sospendersi ed essere cancellato. Gli errori non vengono convertiti in risultati
validi e i `finally` pertinenti continuano a essere eseguiti.

## Offset di stringa

Gli offset leggono e scrivono **byte**, non unità UTF-16 Java. Sono compresi
indici negativi, indici numerici stringa, letture di valori temporanei,
stringhe UTF-8 e byte non UTF-8. I byte non decodificabili restano PhpString;
non vengono sostituiti con caratteri di rimpiazzo.

Le scritture conservano la normale semantica per valore della stringa e quella
dei riferimenti alla variabile. L'espansione a destra riempie con spazi; una
scrittura multi-byte assegna solo il primo byte e produce l'avviso PHP.
Anche il risultato dell'espressione di assegnazione è quel byte, non tutto
l'operando destro. Sono distinti lettura normale, coalescenza e probe
`isset`/`empty`, incluse le differenze diagnostiche coperte dai test.

Riferimenti a offset, unset, append `[]`, incrementi e assegnazioni composte
vietate sulle stringhe producono errori. Le assegnazioni composte non saltano
l'esecuzione dell'operando destro quando PHP la esegue prima dell'errore.
Le posizioni degli avvisi rimangono associate al punto d'accesso, anche se
l'assegnazione sospende prima di completarsi. I nomi binari delle chiavi array
sono ora conservati come valori stringa invece di essere rifiutati.

Il limite di rappresentazione delle stringhe è quello di un array Java di
byte: un offset che lo supera fallisce esplicitamente. Questo non è una
promessa di poter allocare tutta tale dimensione, né una misura di memoria.
La conversione automatica di oggetti con `__toString` e tutte le varianti
numeriche esotiche restano contratti distinti da completare.

## Typing rigoroso per sorgente

`declare(strict_types=0|1)` è metadato della singola unità compilata. Viene
conservato nelle funzioni, nei metodi, nelle closure e nei metodi provenienti
da trait o alias, senza modificare un flag globale della richiesta.
Le dichiarazioni fuori posizione, con valori non consentiti o in modalità
blocco sono rifiutate. Dichiarazioni ripetute non disattivano un modo già
abilitato, come verificato nell'oracolo; gli statement vuoti iniziali sono ammessi.

Per le chiamate dirette, gli argomenti usano il modo **del chiamante**. Il
ritorno usa quello **della funzione eseguita**; le scritture di proprietà
usano quello del punto di scrittura. Rimane ammessa la conversione int→float
prevista anche in modo rigoroso. Sono coperti tipi unione/nullable, variadiche,
named arguments, costruttori, callable statici e argomenti per riferimento.
Questi ultimi vengono controllati e, in modo debole, eventualmente convertiti
all'ingresso: non diventano variabili permanentemente vincolate al tipo del
parametro dopo la chiamata.

Le chiamate eseguite da un builtin verso callback utente rimangono deboli:
il test TrueAsync `spawn` lo verifica senza cambiare il programma fra runtime.
Un callable che deve prima sospendere per l'autoload conserva invece il
chiamante originario. `eval` è una nuova sorgente, debolmente tipizzata salvo
una propria dichiarazione; `include` conserva il modo della sorgente inclusa.

I metodi enum generati, le query di classe/autoload e le API diagnostiche qui
coperte applicano i relativi controlli. **Non è ancora centralizzata la
validazione delle firme di tutti i builtin**: il supporto di strict_types
non va esteso per deduzione a qualunque funzione nativa del catalogo PHP.
`declare(ticks)` e `declare(encoding)` non sono implementati. Restano inoltre
la diagnostica completa dei tipi, le conversioni Stringable sospendibili e i
riferimenti a proprietà tipizzate.

## Verifica e punto di uso

Il corpus nuovo contiene 90 programmi enum/match/clone, 27 programmi sugli
offset di stringa e 38 programmi sul typing: **155 programmi distinti**.
29 sono rifiuti semantici: il runner confronta il rifiuto e una categoria
pertinente, non dichiara identici exit code o interi messaggi fatal fra Zend e
Truffle. Negli altri casi confronta stdout e stderr, non solo l'exit code.

L'integrazione mantiene una richiesta aperta mentre il watcher pubblica nuovi
casi enum e un diverso strict_types: la vecchia richiesta mantiene entrambi
i vecchi contratti; la successiva riceve quelli nuovi. Le fixture del watcher
sono sul filesystem temporaneo nativo, senza estendere il risultato a /mnt/c.
Le suite preesistenti rimangono separate e vengono rieseguite, non sostituite.
Gli esiti effettivi per piattaforma e modalità sono nel
[registro di validazione](validation/language-values-2026-09-26/results.txt), con
[18 report e gli input comuni](validation/language-values-2026-09-26/README.md).
Tutte e sei le combinazioni Windows/Linux JVM/native/interpreter passano: 930
esecuzioni, di cui 756 confronti di output e 174 rifiuti semantici.

```bat
build.bat enum-test
build.bat string-test
build.bat strict-test
build.bat verify
build.bat enum-test native
build\graalphp.exe examples\enums.php
```

I target nativi Windows ricostruiscono il prodotto. Su Linux, con PHP_ORACLE
e TRUEASYNC_ORACLE espliciti, sono disponibili gli stessi target in `build.sh`;
`bash build.sh native` precede il test `native`, che usa il binario già costruito.
L'argomento successivo `--interpreter` permette la verifica senza JIT guest.

L'esempio [enums.php](../examples/enums.php) combina enum backed, match, una
proprietà tipizzata, clone e scrittura COW dentro un array e stampa:

```text
Ready to run:demo
Completed:Demo
3:1
```

## Riferimenti e limiti generali

Il comportamento è confrontato con gli eseguibili PHP 8.6/TrueAsync fissati
nel progetto, non dedotto dalla sola documentazione pubblica. Riferimenti
primari: [enum backed](https://www.php.net/manual/en/language.enumerations.backed.php),
[match](https://www.php.net/manual/en/control-structures.match.php),
[clone](https://www.php.net/manual/en/language.oop5.cloning.php),
[typing](https://www.php.net/manual/en/language.types.declarations.php).

Questo blocco non chiude LANG-01 o l'intero progetto. Iteratori e unpacking
sono stati aggiunti nei [blocchi successivi](iteration.md) e [unpacking](unpacking.md).
Generatori e il nucleo Reflection sono stati aggiunti nei blocchi successivi.
Restano Fiber, Reflection avanzata, standard library, lifetime completo,
shared-memory threading, FFI completa e i target GUI/mobile rimangono
tracciati in TODO.md. Le evidenze prestazionali precedenti non sono riscritte
e le loro misure non sono attribuite ai nuovi eseguibili.
