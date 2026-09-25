# Contratto API TrueAsync

L'API pubblica asincrona segue [TrueAsync 0.10.0](https://github.com/true-async/releases/releases/tag/v0.10.0),
pubblicato il 12 settembre 2026 e verificato tramite l'endpoint GitHub `releases/latest`.
Il binario Windows riporta PHP 8.6.0-dev, estensione incorporata `true_async`
e ABI TrueAsync v0.26.0. Non richiede di caricare `php_async.dll`.

Gli stub e i PHPT usati nel confronto provengono dal commit
[`6acdd07ff500f5799ea83bbf333d686b5dacabbf`](https://github.com/true-async/php-async/tree/6acdd07ff500f5799ea83bbf333d686b5dacabbf),
corrispondente al tag `v0.10.0`. Le API documentate online possono anticipare
la release: il binario è l'oracolo per le differenze osservabili.

## Riproduzione

```bat
build.bat native-libs
build.bat verify
build.bat native
build.bat trueasync
```

`verify` e `trueasync` scaricano il runtime sotto `tools/trueasync-0.10.0`
e i sorgenti upstream sotto `build/reference/`. Tutti i programmi di prova
sono rigenerati in `build/trueasync-cases/`. Nessun percorso viene aggiunto
al PATH di sistema e non viene modificato `php.ini`.

| Download | SHA-256 |
| --- | --- |
| Windows x64 release ZIP | `c25923c3474c30c83d89719bf65d13b196b07e0a0774d6535ac9a33c64a1a56a` |
| Sorgenti del commit fissato, ZIP | `db06d553a98be1c63cf5819ef200f318c2f8e61f47fdb3dcdf64ad463dd56cc9` |

Il runner esegue lo stesso sorgente su entrambi i runtime e confronta stdout,
errori e codici d'uscita; impone un limite al processo di riferimento. Sono
inclusi 40 scenari mantenuti nel progetto e 42 PHPT upstream, estratti senza
modificare la sezione `--FILE--`. Un'API o una sintassi non implementata causa
un fallimento, non uno skip. Il corpus selezionato non rappresenta l'intera
suite upstream.

I sei casi cURL verificano errori immediati, alias, membership multi,
parametri per riferimento anche nominati, coda risultati, ordine delle chiavi,
close, opzioni e validazione con lo stesso script. Sul
binario Windows il runner carica `ext/php_curl.dll` con opzioni del processo:
`-n` da solo non abilita questa estensione. La suite di rete separata verifica
il backend multi/socket, HTTPS, cancellazione e riuso; non è un confronto
differenziale dell'intera API cURL. `curl_close` segue il comportamento PHP 8
di conservare l'oggetto; il warning di deprecazione resta da implementare.

Il binario TrueAsync Windows 0.10.0 presenta un problema riproducibile nella
sequenza multi close → re-add → exec → distruzione multi → curl_exec easy:
produce il risultato atteso e termina con access violation 0xC0000005.
Il riproduttore mantenuto è `tests/php/trueasync-multi-close-reuse.php`;
omettendo la distruzione esplicita è stata osservata una segnalazione di
timer libuv rimasto aperto. Questo caso non viene conteggiato fra gli
82 confronti passati: la suite integrata GraalPHP e quella di rete verificano
separatamente distruzione, riuso e shutdown corretto.

Per ripetere il confronto con l'eseguibile Native Image, dopo la compilazione
dei test:

```bat
tools\graalvm-25.4.4.1.1+1.1\bin\java.exe --enable-native-access=ALL-UNNAMED -cp "build\classes;build\test-classes;build\deps\25.4.4.1.1/*" graalphp.TrueAsyncTest tools\trueasync-0.10.0\php.exe build\graalphp.exe
```

## Copertura verificata

- `Async\spawn`, `await`, `delay`, `suspend`, `timeout`; coroutine con closure
  e catture per valore/reference, risultato e cancellazione con `finally`.
- `Scope::inherit()`, `new Scope()`, `spawn()`, `awaitCompletion(token)`:
  attesa dei discendenti e separazione tra scope ereditati e staccati.
- `current_context()` condiviso nello scope, lookup gerarchico, contesto
  privato restituito da `coroutine_context()`, `root_context()` e comportamento
  CLI di `request_context()`. Chiavi nulle, presenza, sostituzione e rimozione.
- `timeout()` annulla l'attesa senza annullare la coroutine attesa.
  `OperationCanceledException` conserva `TimeoutException` come precedente.
- `Channel`: buffer limitato, rendezvous, `send`, `recv`, `sendAsync`,
  chiusura con drenaggio, rimozione del ricevitore dopo timeout.
- `ThreadChannel`: API bufferizzata e chiusura/drenaggio confrontate con upstream;
  trasferimento di scalari tra worker verificato nei test integrati. Array e
  oggetti non sono ancora trasferibili: il target è heap condiviso, senza la
  copia profonda usata da TrueAsync upstream.
- Argomenti nominati nelle chiamate PHP utente, con reference e variadici;
  supporto nei combinatori e nelle funzioni await/delay/timeout/protect.
- `FutureState`, `Future::completed/failed`, completamento differito, attese
  ripetute con COW, `map/catch/finally` e catena delle eccezioni.
- I sei combinatori `await_any_or_fail`, `await_first_success`, `await_all_or_fail`,
  `await_all`, `await_any_of_or_fail` e `await_any_of` su array. Copertura di
  chiavi associative, ordine di input/completamento, errori parziali, quota di
  successi, array vuoti, future duplicati, risultati COW e timeout.
- `protect()` esegue la closure nella stessa coroutine, attraversa sospensioni,
  restituisce valori con ownership e consegna la cancellazione differita
  all'uscita. Verificati annidamento, eccezioni e finally.
  Il limite host `GRAALPHP_TIMEOUT_MS` continua a essere controllato nei loop
  CPU anche dentro protect; questo limite appartiene al runtime di embedding.
- Le attese di future già risolti non sospendono; lo stesso future come sorgente
  e token non si cancella da solo. Token completati o falliti producono
  `OperationCanceledException` con il precedente originale. La cancellazione
  esplicita conserva il motivo fornito dal chiamante.
- Gli scope rifiutano attese dalla propria coroutine o dai propri discendenti;
  `awaitAfterCancellation` verifica che la cancellazione sia avvenuta.

### Dettagli osservati nel binario 0.10.0

`await_first_success` restituisce `[valore, errori]`; `await_all` e `await_any_of`
restituiscono `[risultati, errori]`. `await_any_of(n, ...)` conta i successi.
`fillNull` inserisce i mancanti solo nei risultati, non nell'array degli errori.
`await_any_of_or_fail(0, ...)` restituisce un array vuoto, mentre
`await_any_of(0, ...)` attende tutti.

Dopo la selezione, `await_first_success` e `await_any_of` attendono anche le
coroutine ancora in esecuzione senza cancellarle; i relativi successi non
vengono aggiunti ai risultati selezionati. Questo comportamento è presente
nel sorgente fissato e verificato contro il binario. `await_any_or_fail` e
`await_any_of_or_fail` consentono invece alle altre coroutine di continuare
dopo il ritorno. Non si deve dedurre il comportamento dalla sola parola “any”.

Le registrazioni delle attese si disattivano al primo esito o alla cancellazione.
I callback tardivi non riprendono due volte la stessa continuation; le copie
dei valori destinate a riprese scartate vengono rilasciate anche durante shutdown.

Il confronto ha corretto due differenze reali: `Context::set()` su una chiave
locale esistente solleva `AsyncException`; la divisione esatta di due interi
PHP restituisce un intero.

## Parti ancora aperte

Non è dichiarata compatibilità completa con TrueAsync. Restano Traversable e
generatori nei combinatori, strategie e provider personalizzati, TaskGroup,
TaskSet, Pool, Thread/ThreadPool pubblici, grafi completi in ThreadChannel, gestione completa degli
errori e del disposal degli scope, diagnostica e priorità delle coroutine,
warning di future inutilizzati, iteratori dei channel, opzioni di deadlock,
watcher pubblico, segnali, networking e I/O trasparente.

Il lifetime dei future PHP non segue ancora il loro ultimo riferimento guest:
il runtime ne conserva i risultati fino alla fine della richiesta. Perciò
il disposal automatico dei recvAsync perdenti e i carichi con molte attese
temporanee richiedono ulteriore lavoro. Le guardie degli scope non sostituiscono
la policy completa degli errori e del disposal; in particolare resta aperta
la propagazione degli errori non osservati negli scope annidati o staccati.

Alcuni metodi di interrogazione sono implementati ma non coperti da tutto
il corpus upstream. Parametri nominati e sintassi PHP ancora mancanti impediscono
di eseguire molti PHPT senza adattamenti; questi adattamenti non vengono usati
per dichiarare conformità.

Le vecchie funzioni globali `sleep_ms`, `context_get/set`, `parallel` e le capsule
`shared_counter` restano strumenti del laboratorio. Non definiscono il contratto
TrueAsync. La condivisione esplicita di grafi prevista dal design deve essere
aggiunta mantenendo distinti i contratti di trasferimento e sincronizzazione;
oggi soltanto `SharedCounter` è promosso LOCAL→SHARED.
