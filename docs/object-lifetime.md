# Lifetime degli oggetti e riferimenti deboli

`WeakReference::create(object: $object)` restituisce l'istanza debole già
esistente per quel target, finché l'istanza stessa ha proprietari. Il registro
appartiene alla richiesta ed è allocato alla prima creazione. Il collector
ignora i suoi collegamenti. `get()` acquisisce invece un proprietario
ordinario per il valore restituito, oppure restituisce null dopo lo scarto
del target.

Il percorso reference counting invalida il registro alla distruzione
effettiva. Il trial deletion invalida le entry di tutto il sottografo morto
prima di rilasciare gli archi verso i sopravvissuti. Le due direzioni del
registro permettono di eliminare anche un riferimento debole che muore
mentre il suo target è ancora vivo.

Gli accessi dinamici a proprietà conservano il ricevitore mentre la
location è in uso. Una lettura trasferisce ownership al valore prima di
chiudere la location. Argomenti e offset trasferiscono o duplicano la
radice mentre serve, anche attraverso una sospensione o l'autoload. Le
scritture studiate su una proprietà di variabile risolvono il ricevitore
dopo l'operando destro. Un ricevitore temporaneo già valutato resta vivo
fino al termine dell'operazione, anche in caso di errore.

## Raccolta esplicita e automatica

`gc_enabled`, `gc_enable` e `gc_disable` riguardano la policy automatica
della richiesta. `gc_collect_cycles` effettua comunque la raccolta esplicita.
Il suo conteggio distingue array e oggetti dalle celle reference interne.
L'automatismo verifica una soglia di 10.001 candidati nei loop e alle
chiusure degli scope. Non riproduce ancora l'adattamento dinamico della
soglia Zend. Alla chiusura della richiesta avviene un'ultima raccolta.

Un multi cURL ancora dentro un ciclo senza radici resta associato alle sue
easy fino alla raccolta di quel ciclo. Il caso integrato richiama
`gc_collect_cycles()` e verifica che l'easy venga staccata e torni utilizzabile.
Le chiusure acicliche seguono immediatamente l'ultimo proprietario.

## Limiti e dipendenze del prossimo blocco

Il contratto copre gli oggetti gestiti dal grafo di ownership della richiesta.
Le risorse e gli oggetti interop fuori dal grafo, WeakMap e le API di
statistica del collector restano aperti. Il lifetime nei casi ancora
incompleti va verificato anche con argomenti composti, riferimenti a
proprietà tipizzate e riassegnazione del ricevitore durante un `??=`.

Il binario PHP 8.6 conserva i weak reference mentre gira `__destruct` e li
invalida se l'oggetto muore, anche dopo un'eventuale resurrezione. TrueAsync
0.10.0 permette a un distruttore di sospendersi. Il prossimo blocco deve
perciò tenere viva una radice temporanea, completare la mutazione che
innesca il rilascio, invocare il codice PHP sul dispatcher e ricontrollare
gli owner dopo il ritorno. L'oracolo verifica separatamente ordine, errore
del distruttore, errore del costruttore, shutdown e grafi ciclici.
