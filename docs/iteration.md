# Iterazione sospendibile e metadati degli attributi

Blocco funzionale del 26 settembre 2026, successivo a
[enum e valori](language-values.md). Introduce i protocolli di iterazione
utente e le API correlate senza sostituire il backend Truffle Bytecode DSL,
senza worker aggiuntivi e senza una nuova campagna prestazionale.

## Protocolli e ordine delle chiamate

`Traversable`, `Iterator`, `IteratorAggregate` e `Countable` sono interfacce
nel modello delle classi. Le loro firme partecipano allo stesso linker e agli
stessi controlli di parametri, staticità, visibilità e ritorni dei contratti
utente. Un tipo concreto non può implementare direttamente solo Traversable,
né combinare Iterator e IteratorAggregate. Un tipo astratto può rinviare
l'implementazione del protocollo.

`foreach` distingue array, iteratori, aggregate e proprietà degli oggetti.
Le chiamate del protocollo usano le continuazioni ordinarie:
`getIterator`, `rewind`, `valid`, `current`, `key` e `next` possono tutte
sospendersi, lanciare eccezioni o essere cancellate. Il corpo del ciclo non è
un ambiente speciale e non usa una copia differente dei valori.

Un iterator viene riavvolto all'inizio di ogni foreach. Il passo acquisisce
`current` e, soltanto quando necessario, `key`; le variabili del ciclo vengono
aggiornate dopo entrambe le chiamate. Perciò `key` vede ancora il binding
precedente della variabile valore, come verificato nell'oracolo. Un break non
esegue un ulteriore next, un continue sì. Cicli annidati sullo stesso oggetto
Iterator condividono il suo stato, non ricevono cloni impliciti.

Gli aggregate possono restituire altri aggregate prima di arrivare a un
Iterator. Il risultato viene validato e posseduto prima di rilasciare il
precedente. Le catene cicliche o superiori a 512 passaggi sono rifiutate con
un errore esplicito; questo limite difensivo non è presentato come limite
identico a quello di Zend. Gli iteratori utente non si possono attraversare
per riferimento. Per un aggregate viene prima eseguito getIterator e poi
segnalato il divieto; per un Iterator diretto l'errore precede rewind.
Le espressioni temporanee array/oggetto normali restano iterabili per riferimento.

## Funzioni correlate

Sono implementate `iterator_count`, `iterator_to_array`, `iterator_apply`,
`count`/`sizeof`, `is_iterable`, `is_countable`, `get_object_vars` e
`get_mangled_object_vars`, con firme e argomenti nominati dei casi verificati.
Gli argomenti scalari tengono conto di strict_types; il null debolmente
convertibile emette la deprecazione pertinente anziché scomparire silenziosamente.

`iterator_count` e `iterator_apply` non eseguono current/key: sarebbe una
modifica osservabile del programma quando quei metodi hanno side effect o
sospendono. iterator_apply conta anche l'invocazione del callback che ritorna
false, poi interrompe. La validazione del callback e l'eventuale autoload
precedono rewind. Il callable viene risolto nel contesto autorizzato del
chiamante e mantenuto per tutte le invocazioni; i callback ricevono i loro
argomenti, non automaticamente il valore corrente dell'iteratore. Sono
conservati named arguments e riferimenti espliciti.

`iterator_to_array(..., false)` non invoca key sugli iteratori. Quando l'input
è un array, il risultato conserva la semantica COW e le reference esplicite,
anche nella variante reindicizzata. Per un Iterator, chiavi che sono valori
validi in foreach ma non chiavi array producono TypeError al momento della
conversione, senza trasformare il problema in un'eccezione generica del runtime.

`count(..., COUNT_RECURSIVE)` ricorre solo negli array, distingue le ripetizioni
non cicliche e segnala le vere ricorsioni conservando il risultato previsto.
Un Countable viene invocato una volta; il valore di ritorno di un'implementazione
legacy senza tipo viene convertito secondo il comportamento interno verificato,
non secondo strict_types del file che chiama count. I modi diversi da
COUNT_NORMAL/COUNT_RECURSIVE sono ValueError. La profondità ricorsiva è limitata
a 512 livelli come protezione del runtime.

## Proprietà degli oggetti e ownership

Il cursore delle proprietà è vivo: vede aggiunte e modifiche successive e non
restituisce un campo cancellato prima di raggiungerlo. Una proprietà dinamica
cancellata e ricreata viene aggiunta in fondo; una dichiarata mantiene la
posizione. Le proprietà tipizzate non inizializzate vengono saltate. Un probe
su una proprietà inesistente non crea un bucket e non cambia l'ordine di un
successivo inserimento.

La visibilità è quella del codice che ha aperto il foreach: campi privati del
relativo owner, protetti accessibili dalla gerarchia e pubblici/dinamici vengono
selezionati prima dell'assegnazione. get_object_vars usa la stessa visibilità;
get_mangled_object_vars espone i nomi mangled dei campi utente. Entrambe le
funzioni restituiscono un array separato e conservano le reference già condivise.
I root interni delle closure non vengono esposti come proprietà PHP.

Il cursore possiede esplicitamente l'oggetto e i suoi valori anche quando la
variabile sorgente viene rilasciata. Un alias a una cella può sopravvivere alla
chiusura del cursore senza tenere vivo artificialmente l'oggetto contenitore.
Le chiusure ripetute sono idempotenti. I metadati di ordinamento non aggiungono
archi di ownership PHP: i test diretti verificano anche oggetti ciclici,
clonazione, proprietà readonly e il rilascio transitivo degli array.

Il controllo di ciclo è una risorsa dell'activation originale; gli helper PHP
sospendibili non cambiano il proprietario delle variabili né la visibilità.
Break, ritorno, errore e cancellazione chiudono il cursore. La richiesta e gli
oggetti rimangono ancorati alla propria generazione anche se il watcher
pubblica una nuova implementazione mentre current è sospeso.

## Attributi e tipi di ritorno provvisori

Il frontend distingue `#[...]` dai commenti e conserva attributi, ordine,
nomi qualificati, argomenti e intervalli sorgente su classi, interfacce, trait,
enum/casi, funzioni/metodi/closure, parametri, proprietà e costanti.
Gli argomenti sono IR non valutato: un attributo sconosciuto non provoca
un autoload o l'esecuzione di una costante durante la semplice dichiarazione.
Composizione dei trait e alias conservano i metadati della funzione originale.

Sono rappresentate le classi builtin Attribute e ReturnTypeWillChange e le
costanti target di Attribute. La gestione degli attributi in questo blocco
comprende **metadati e soppressione mirata degli avvisi**, non ancora Reflection,
getAttributes/newInstance, verifica di target/ripetibilità o tutte le semantiche
degli attributi builtin aggiunti nelle versioni PHP recenti.

Le firme provvisorie di Iterator/IteratorAggregate/Countable producono la
Deprecation prevista quando l'implementazione manca di un ritorno compatibile.
`#[\\ReturnTypeWillChange]` la sopprime; un attributo omonimo in un altro
namespace no. Non sopprime errori di parametri, staticità o visibilità.
Il tipo di ritorno esplicito di un metodo continua a essere controllato alla
chiamata. I warning conservano file/riga, ordine dei contratti e stato di
error_get_last della richiesta; l'eredità della stessa implementazione non
moltiplica un avviso già emesso per quella dichiarazione.

## Verifiche ripetibili e limiti

Il corpus `IterationTest` contiene 74 programmi: 68 confronti di output e
6 rifiuti semantici. Il medesimo programma viene eseguito da riferimento e
target; i rifiuti richiedono una diagnostica pertinente, non messaggi fatal
identici fra Zend e Truffle. I casi includono mutazione viva, visibilità,
callback privati, autoload, reference, diagnostica, asincronia e cancellazione.

`FieldIterationTest` aggiunge 56 controlli diretti di lifetime/ordinamento e
`AttributeMetadataTest` 52 controlli su metadati, immutabilità e intervalli
sorgente. Entrambi sono nel normale `verify`, insieme alle suite precedenti.
Un nuovo scenario integrato cambia contemporaneamente l'iteratore e Countable
mentre la richiesta precedente è ancora aperta, poi verifica vecchia e nuova
generazione senza affidarsi a un ritardo fisso del watcher.

```bat
build.bat iteration-test
build.bat verify
build.bat iteration-test native
build.bat iteration-test native --interpreter
build\graalphp.exe examples\iteration.php
```

Linux espone gli stessi target in build.sh, con gli oracoli espliciti già usati
dal progetto e la build native eseguita prima del suo test. Il test Windows
native ricostruisce il prodotto; per riusare un'immagine appena compilata tra
più suite, le invocazioni Java dirette sono conservate nei log di validazione.

L'esempio [iteration.php](../examples/iteration.php) usa un aggregate Countable
e un Iterator il cui current sospende; stampa:

```text
0:parse
1:execute
2:execute
```

Non sono ancora comprese le classi concrete dell'intera SPL (ArrayIterator,
EmptyIterator, filtri e wrapper), Fiber, tutti i callable builtin, ArrayAccess,
reference di proprietà tipizzate o il sistema completo di attributi/Reflection.
I generatori sono stati aggiunti in un blocco successivo. Argument unpacking e array spread sono stati aggiunti nel
[blocco successivo](unpacking.md). Il sostegno dei protocolli non implica la disponibilità
di quelle classi. Le conversioni generali e le diagnostiche non coperte restano
nel backlog. La suite non è una misura prestazionale e non chiude LANG-01.

Riferimenti primari:
[Iterator](https://www.php.net/manual/en/class.iterator.php),
[IteratorAggregate](https://www.php.net/manual/en/class.iteratoraggregate.php),
[ereditarietà e ritorni provvisori](https://www.php.net/manual/en/language.oop5.inheritance.php),
[callable dipendenti dal contesto](https://www.php.net/manual/en/language.types.callable.php).
Le differenze fini sono verificate contro gli eseguibili PHP8.6/TrueAsync
fissati nel progetto, non dedotte soltanto dalla documentazione corrente.
[Evidenze, versioni e prodotti finali](validation/iteration-2026-09-26/README.md).
