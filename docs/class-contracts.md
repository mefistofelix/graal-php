# Interfacce, trait e contratti delle classi

Blocco funzionale del 26 settembre 2026, successivo
all'[autoload personalizzato](autoload.md). La pipeline resta
frontend → IR → Truffle Bytecode DSL per file, include, eval e librerie
riprendibili. Non viene introdotto un interprete alternativo.

## Dichiarazioni e composizione

Il frontend distingue classi, interfacce e trait. Sono presenti classi e metodi
astratti, classi/metodi finali, implementazione di più interfacce, ereditarietà
multipla delle interfacce e uso annidato dei trait. Queste dichiarazioni sono
consentite anche nei blocchi condizionali e dentro le funzioni, nei limiti del
binding anticipato già descritti per l'autoload.

`ClassLinker` costruisce la vista runtime della dichiarazione senza mutare la
definizione di codice condivisa fra richieste. Verifica la categoria delle
dipendenze e compone metodi, requisiti astratti, proprietà e costanti. Il
caricamento delle dipendenze percorre padre, trait e interfacce e può
sospendersi sulla coroutine corrente. La dichiarazione viene pubblicata dopo
la risoluzione; una cancellazione durante il loader non lascia installata una
classe incompleta.

La precedenza è metodo della classe → metodo del trait → metodo ereditato.
`insteadof` seleziona il corpo fra trait in conflitto; `as` aggiunge un alias o
modifica visibilità/finalità. Gli alias possono selezionare anche un metodo
escluso dal nome originale. I requisiti astratti importati sono verificati
anche per gli alias. Un medesimo corpo proveniente da un trait comune, importato
attraverso due altri trait, non viene confuso con una collisione fra due corpi
diversi.

L'importazione riassocia lo scope lessicale del metodo alla classe utilizzatrice.
Sono verificati accesso a membri privati, `self`, `parent`, late static binding,
closure create nel trait e costanti magiche. `__CLASS__` identifica la classe
utilizzatrice; `__TRAIT__`, `__METHOD__` e `__FUNCTION__` conservano le
informazioni del corpo originario anche quando invocato tramite alias.

La ridefinizione di proprietà controlla staticità, visibilità e compatibilità
del tipo. Le collisioni fra proprietà o costanti dei trait confrontano anche
i valori iniziali, incluse le array. Le proprietà statiche dei trait sono
associate alla singola classe utilizzatrice; l'ereditarietà senza riuso del
trait mantiene invece lo storage ereditato. I test distinguono questi due casi.
I default statici con `self` o `parent` vengono valutati sulla classe utilizzatrice:
la sola dichiarazione del trait non deve forzare costanti ancora inesistenti.
L'accesso statico diretto al trait mantiene uno storage distinto, inizializzato
al primo accesso, e produce la deprecazione PHP prevista.

## Firme e tipi

Le firme controllano visibilità, metodo statico/di istanza, riferimento dei
parametri, numero di argomenti obbligatori, variadici e tipi. Sono supportati
tipi nullable, unioni, intersezioni e forme DNF parentetiche. La relazione di
tipo usata dal linker verifica contravarianza dei parametri e covarianza dei
risultati; le invocazioni verificano anche il valore effettivo.

`self` e `parent` vengono risolti nello scope dichiarativo corretto. Il risultato
`static` è verificato sulla classe della chiamata, non soltanto sulla classe che
contiene il corpo. La verifica dei variadici include i parametri aggiuntivi
che un'implementazione pone dopo la posizione variadica del contratto. I
costruttori concreti ereditati conservano la loro eccezione alle normali regole
di firma; i requisiti di un'interfaccia o di un costruttore astratto no.

Prima di convertire scalari si verifica l'appartenenza esatta all'unione:
`int|string` non trasforma una stringa già accettata. Il test per `int|float`
include stringhe numeriche decimali e rifiuto di stringhe non numeriche.
Questo non certifica tutte le coercizioni e tutti i controlli sintattici dei
tipi di PHP 8.6; i limiti rimanenti sono elencati sotto.

## Costanti e interrogazione delle classi

Le costanti di classe/interfaccia/trait possono avere visibilità, tipo e
modificatore `final`. Il linker rileva ambiguità di ereditarietà e verifica gli
override contro tutte le interfacce, non solo la prima. I valori vengono
posseduti dalla richiesta; le array conservano la semantica COW. Sono gestite
costanti che dipendono da altre costanti della stessa classe e costanti usate
come default di proprietà o parametri.

L'accesso esplicito `C::VALUE` carica la classe in modo riprendibile. Sono
presenti anche costanti con ricevitore dinamico, `self::class`, `parent::class`,
`static::class` e `oggetto::class`. La forma `stringa::class` produce TypeError,
non viene confusa con il nome letterale `C::class`.

Sono aggiunti `instanceof`, `interface_exists`, `trait_exists`, `is_a`,
`is_subclass_of`, `method_exists`, `class_parents`, `class_implements` e
`class_uses`. `instanceof` non attiva autoload; le interrogazioni con una
classe espressa per nome seguono il proprio flag di caricamento. Le funzioni
`get_declared_classes/interfaces/traits` espongono le dichiarazioni utente
installate: non costituiscono ancora il catalogo completo dei tipi builtin.

## Errori fatali, diagnostica e confini C

Una firma incompatibile o una composizione non valida è un errore fatale di
dichiarazione, non una normale eccezione catturabile con `Throwable`. Il corpus
verifica separatamente che `catch`, `finally` guest e codice successivo non
vengano eseguiti dopo quel fatal, anche quando nasce in una coroutine.

L'errore fatale interrompe l'avanzamento guest della richiesta; il cleanup dei
root e delle risorse Java/native non è invece omesso. La chiusura della richiesta
notifica prima le chiamate C drenabili e risolve i dispatch verso callback
native rimasti in attesa, quindi attende i worker, infine rilascia i root.
Il callback C non può rientrare in PHP dopo il fatal. Questo evita che
l'omissione dei `finally` guest lasci bloccato un worker sullo stack C.

Il nuovo test `NativeBridgeFatalTest` combina callback C sincrone/async con
fatal immediato/dopo sospensione. Nella verifica JVM misura i contatori della
fixture: nessuna chiamata C attiva e una chiamata terminata in più, quindi
riutilizza lo stesso contesto per un'altra richiesta. Nella verifica del
prodotto CLI controlla output, errore ed uscita senza hang; non presenta quei
controlli come una lettura dei contatori dentro il processo già terminato.

La deprecazione PHP 8.6 per `is_a` con stringa e `allow_string=false` ha un
percorso dedicato. `error_reporting`, `error_get_last` ed `error_clear_last`
conservano stato per richiesta. Il bytecode registra nodo/indice del punto di
chiamata; file e riga vengono risolti solo quando serve la diagnostica. Il
corpus verifica testo, file, riga e conservazione dell'ultimo errore anche
quando la stampa è mascherata. Non è un'implementazione completa di ini,
handler personalizzati o soppressione `@`.

## Completamenti concorrenti sul dispatcher host

Le regressioni hanno riprodotto un caso del driver `EmbeddedPhp`: una completion
può diventare pronta fra il ritorno idle di `pump` e la lettura di `deadline`.
Quella deadline immediata veniva trasformata in un allarme host a zero; ora
lavoro già pronto torna su `Driver.post`, mentre soltanto deadline future
usano `Driver.schedule`. Non viene aggiunto polling.

Il test host espone le deadline osservate, forza una vera attesa del server
prima di connettere il client e seleziona esplicitamente libuv nei due contesti.
Dodici ripetizioni Windows del test mirato e la suite integrata passano dopo
la correzione; le esecuzioni fallite restano separate nelle evidenze. Il test
non richiede che un'operazione completata molto rapidamente programmi comunque
un allarme inutile. Questi controlli sono funzionali, non benchmark.

## Verifica e uso

`ClassContractsTest` contiene 93 programmi comuni a oracolo e runtime: 61 con
output identico, 32 con rifiuto semantico richiesto. Per i secondi non viene
asserita uguaglianza del testo diagnostico o del codice numerico di uscita:
PHP termina con 255, la CLI GraalPHP usa 1. Il test verifica che non si tratti
di una generica mancata implementazione o di un errore interno e che non
vengano eseguiti i percorsi guest proibiti dopo un fatal.

Il test di reload pubblica una nuova definizione di interfaccia/trait mentre
una richiesta è ferma prima della composizione: la richiesta vecchia usa
ancora il corpo vecchio, una richiesta nuova usa il nuovo. Le fixture usano
filesystem temporanei nativi, non assumono funzionante WatchService su `/mnt/c`.

Le piattaforme e modalità effettivamente verificate, i log di sviluppo e gli
hash dei prodotti sono distinti in
[validation/class-contracts-2026-09-26](validation/class-contracts-2026-09-26/README.md).
Non è stata eseguita una campagna prestazionale per questo blocco.

Ingressi Windows:

```bat
build.bat class-test
build.bat verify
build.bat autoload-test
build.bat ffi-bridge-test
build.bat ffi-fatal-test
build.bat class-test native
build\graalphp.exe examples\class-contracts.php
```

`class-test native --interpreter` verifica lo stesso corpus disabilitando il
JIT guest. `ffi-fatal-test native` ricostruisce e verifica la CLI del prodotto.
Il vecchio `ffi-bridge-test native` costruisce invece il runner dedicato con
checkpoint GC: non sono lo stesso tipo di verifica.

Su Linux `bash build.sh class-test` richiede `PHP_ORACLE` e `TRUEASYNC_ORACLE`.
Dopo `bash build.sh native`, le varianti `class-test native` e
`ffi-fatal-test native` usano il prodotto già costruito. Il rapporto dichiara
esplicitamente quando il binario TrueAsync/PHP 8.6 funge anche da oracolo per
i casi sincroni, invece di un eseguibile stock separato.

L'esempio [class-contracts.php](../examples/class-contracts.php) stampa:

```text
Hello from a trait
contract satisfied
```

## Limiti e seguito

Il blocco non chiude LANG-01 né il progetto. Restano enum, attributi,
reflection completa, generatori/Fiber, iteratori builtin, unpacking,
proprietà readonly/hooks e accessori, return-by-reference e altre forme PHP.
La grammatica dei tipi accetta le forme composte implementate, ma non ha ancora
tutti i controlli di validità delle combinazioni illegali né tutti i casi di
risoluzione del tipo attraverso autoload. I riferimenti a proprietà tipizzate
richiedono ancora il contratto completo di validazione delle scritture.

Il binding anticipato fra unità e le dipendenze dinamiche dentro espressioni di
costante non sono dichiarati completi. Gli accessi espliciti alle costanti
sono riprendibili; la valutazione dei default di dichiarazione è ancora un
percorso sincrono. I builtin riconosciuti dalle interrogazioni di classe sono
quelli già modellati; warning e parametri del catalogo completo richiedono
altre verifiche. Errori fatali del linker non equivalgono a copertura completa
di tutte le fasi fatali, diagnostiche e shutdown hook di PHP.

Il seguito funzionale resta coordinato con ownership, TrueAsync, threading
e FFI. Le misure RAM/throughput/p99 restano rinviate per richiesta dell'utente.
