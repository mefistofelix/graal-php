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

## Distruttori PHP

Il runtime registra il distruttore dopo la costruzione o il clone
completati. Quando il conteggio arriva a zero, il collector conserva
una radice temporanea e accoda l'oggetto. Le operazioni guest scaricano
la coda sul dispatcher dopo la transizione dello slot e possono sospendere.
Alla conclusione si rilascia la radice. Un oggetto risorto mantiene gli
stessi riferimenti deboli e il distruttore resta già invocato.

Per i cicli il trial deletion conserva temporaneamente il sottografo
dei finalizzandi. La raccolta esplicita esegue le finalizzazioni e
verifica di nuovo la raggiungibilità prima di contare i nodi raccolti.
Un callback può quindi salvare un oggetto del ciclo. Un ciclo ancora
raggiungibile da quel callback rimane nel grafo.

I casi verificati comprendono l'uscita normale dai frame, errori di
costruzione, eccezioni dal distruttore di un unset, visibilità
privata, ordine genitore/figlio alla distruzione e shutdown normale.
Lo shutdown accoda i distruttori registrati in ordine inverso, con gli
oggetti ancora accessibili. Una coda di pari livello viene differita
durante il callback in corso. Le nuove finalizzazioni provocate dal
callback possono essere eseguite prima di riprendere quella coda.

## Limiti

Il contratto copre gli oggetti gestiti dal grafo di ownership della richiesta.
Le risorse e gli oggetti interop fuori dal grafo, WeakMap e le API di
statistica del collector restano aperti. Il lifetime nei casi ancora
incompleti va verificato anche con argomenti composti, riferimenti a
proprietà tipizzate e riassegnazione del ricevitore durante un `??=`.

Restano aperti tutti i percorsi di shutdown fatale e hosted, le eccezioni
multiple concatenate, il punto preciso di distruzione in tutte le
espressioni e l'ordine completo tra coroutine concorrenti. Il caso di
sospensione dalla coroutine che rilascia l'oggetto passa contro TrueAsync.
Il rilascio delle risorse C conserva i suoi confini di drenaggio. I
distruttori sospendibili usano lo scheduler PHP.
