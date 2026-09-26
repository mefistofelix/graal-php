# Autoload e dichiarazioni runtime — LANG-01

Seguito funzionale: [interfacce, trait e contratti delle classi](class-contracts.md).
I risultati della campagna autoload originaria restano distinti.

26 settembre 2026. Blocco funzionale successivo alla diagnostica PERF-01:
**nessun nuovo benchmark**, né modifica a compiler idle delay, GC o scheduler.
Questo documento descrive il nucleo di autoload con callback personalizzate,
non l'intera SPL e non la compatibilità con Composer come applicazione.

## Funzionalità

Sono disponibili `class_exists`, `spl_autoload_register` con callback esplicita,
`spl_autoload_unregister`, `spl_autoload_functions` e `spl_autoload_call`.
Accettano gli argomenti nominati delle rispettive firme. Il registro è della
richiesta, non globale al contesto Truffle o condiviso fra generazioni.

Le callback possono essere funzioni utente, closure, oggetti invocabili,
coppie oggetto/metodo e metodi statici indicati con stringa o array. Il registro
conserva l'invocazione risolta, anche per un metodo privato registrato dal suo
scope autorizzato. Duplicati della stessa invocazione non vengono aggiunti o
spostati da `prepend`; nomi di funzioni/classi/metodi sono confrontati senza
distinzione di maiuscole. La lista restituita è un array PHP separato: modificarlo
non cambia il registro.

La risoluzione mancante può avviare autoload da costruzione, chiamata statica,
proprietà statica, callable statico dinamico, registrazione di un callback la cui
classe manca e dichiarazione di una classe il cui padre manca. `class_exists`
permette di disabilitarlo. `Nome::class` rimane una stringa, senza caricamento.
La costruzione ora accetta anche `new $nome`, `new ($espressione)` e un oggetto
utente come indicazione della classe; gli altri tipi producono un `Error`.

Le dichiarazioni condizionali e dentro funzioni sono eseguite quando raggiunte.
Una classe con padre ancora da risolvere non viene pubblicata prima che il
caricamento del padre sia terminato; un errore non lascia una classe figlia
parzialmente installata. Il parser mantiene l'anticipazione delle classi senza
padre e delle classi il cui padre anticipabile compare prima nella stessa
unità. Il binding PHP completo tra unità e tutte le regole di ereditarietà
rimangono un lavoro separato, non sono dedotti da questi casi.

Esempio eseguibile: [examples/autoload.php](../examples/autoload.php). Carica
una classe e il suo padre da file distinti; la callback sospende con
`Async\delay` e il risultato è `Hello from autoload`.

## Coda attiva, ricorsione e cancellazione

La callback termina il tentativo soltanto se la classe risulta definita: il
suo valore di ritorno non decide il successo. Una callback aggiunta in coda
può essere visitata nello stesso tentativo; una rimossa non viene più visitata.
Anche `prepend` e rimozione della callback corrente conservano il comportamento
osservato negli oracoli fissati, compreso l'avanzamento che può saltare la
callback successiva dopo un'auto-rimozione. Questi dettagli sono coperti da
programmi differenziali, non da un'ipotesi di lista immutabile.

Il tentativo implicito impedisce la ricorsione sullo stesso nome normalizzato,
ma permette un nome diverso. Il guard è rilasciato con `finally` dopo successo,
assenza della classe, eccezione o cancellazione. Non esiste una cache negativa
permanente: una lookup successiva può riprovare. `spl_autoload_call` è invece
esplicita, visita la coda anche per una classe già presente e non aggiunge lo
stesso guard di lookup implicita.

Il guard è condiviso dalle coroutine della richiesta. Il programma identico
su TrueAsync 0.10.0 verifica che una seconda `class_exists` sul nome già in
caricamento restituisca false mentre il primo loader è sospeso: non viene
inventato un waiter che cambierebbe tale contratto. Il test sincronizza le due
coroutine con channel, senza dipendere dalla durata di un timer.

## Continuazioni e ownership

`ClassLoading.SOURCE` contiene il controllo di flusso riprendibile, compilato
con lo stesso parser/IR/Bytecode DSL del resto delle librerie runtime.
`EnsureClass` e `DeclareClass` usano gli ordinari punti di sospensione.
Non viene aggiunto un interprete, un polling loop o un worker per l'autoload.

Il registro possiede esplicitamente receiver e ambiente delle callback. Un
pin aggiuntivo conserva l'entry selezionata anche se viene rimossa mentre
l'invocazione può ancora sospendersi. Gli argomenti di un callable differito
mantengono ownership, nomi e locazioni per riferimento; la chiamata finale
conserva lo scope del chiamante originario. I tentativi e le chiamate differite
sono anche risorse dell'activation, così il cleanup non dipende dal solo
ritorno normale. La chiusura della richiesta rilascia il registro prima degli
ultimi root globali.

Il test di reload blocca una richiesta **prima** del primo autoload, pubblica
una nuova versione del file e poi la riprende: l'include del loader legge
ancora la generazione originale. Una nuova richiesta riceve la nuova versione;
nessuna eredita le registrazioni della precedente. Gli include mantengono lo
scope locale del loader e le normali regole `include_once` della richiesta.

## Verifica ripetibile

`tests/graalphp/AutoloadTest.java` contiene **38 programmi** identici per
oracolo e runtime, con fixture separate per ogni caso. Coprono ordine e
mutazioni del registro, identità/lifetime delle callback, namespace, costruzione
dinamica, metodi/proprietà statici, riferimenti e named arguments, dipendenze
fra classi, include, errori, sospensioni e cancellazione. Il runner conserva
stdout/stderr di entrambe le esecuzioni e gli exit code; richiede PHP 8.6 e
non elimina i casi falliti. I risultati delle diverse piattaforme e le
versioni effettivamente usate sono distinti nel
[registro della verifica](validation/autoload-2026-09-26/results.txt).

Windows, dopo lo stesso setup del progetto:

```bat
build.bat autoload-test
build.bat verify
build.bat oracle
build.bat autoload-test native
build\graalphp.exe examples\autoload.php
```

`autoload-test native` ricostruisce il prodotto e usa quel binario. `verify`
comprende anche il nuovo isolamento/reload, oltre alle suite preesistenti;
non è una suite di benchmark.

Linux, con gli oracoli esplicitamente selezionati:

```sh
export PHP_ORACLE=/percorso/al/php-8.6
export TRUEASYNC_ORACLE=/percorso/al/trueasync-0.10.0/php
bash build.sh autoload-test
bash build.sh native
bash build.sh autoload-test native
```

Il target Linux `autoload-test native` usa `build/graalphp` già prodotto dal
comando precedente. Non sostituisce silenziosamente un PHP di versione diversa.
La validazione identifica quando TrueAsync/PHP 8.6 è usato anche come oracolo
per i casi sincroni, invece di un eseguibile stock separato.

## Confini ancora aperti

Il caricatore SPL predefinito (`spl_autoload`), le estensioni del caricatore,
`include_path` e la ricerca fuori dalla root non sono implementati.
`spl_autoload_register()` senza callback, o con null, fallisce esplicitamente
anziché fingere di installare un caricatore funzionante. I callback builtin
arbitrari non sono inclusi nel contratto delle callback utente qui verificato.
Il parametro storico `throw` viene validato e ignorato: il Notice PHP per
`throw: false` non è ancora riprodotto.

`class_exists` riconosce le classi utente installate e i nomi builtin già
modellati dal runtime, non l'intero catalogo PHP/SPL. Il blocco successivo
aggiunge interfacce, trait, query per categoria e composizione sospendibile;
`class_alias`, enum, reflection completa e la gerarchia Throwable completa
restano aperti. Le costruzioni dinamiche sono verificate per classi utente;
non completano i costruttori speciali di tutti gli errori/builtin.
I nomi binari non UTF-8 e tutte le coercizioni/diagnostiche
PHP richiedono ulteriore lavoro, come il frontend generale.

La root rimane precompilata da `CodeRepository`: autoload ritarda
**l'installazione/esecuzione** del file nella richiesta, non introduce un nuovo
indicizzatore lazy. File non supportati nella root possono ancora bloccare il
primo indice; gli include devono essere presenti nella generazione fissata.
Non viene quindi dichiarata funzionante un'applicazione Composer arbitraria.

`LANG-01` rimane aperto. I contratti di classe ora implementati e le forme di
dichiarazione mancanti sono distinti nel rapporto successivo; continuare a
coordinarli con valori, TrueAsync e reload. **PERF-01 e le misure di prestazioni sono rinviati** per
richiesta dell'utente, senza cancellare le evidenze storiche.
