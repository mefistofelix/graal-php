# Stack C sospendibili e callback PHP

Il confine C è un requisito centrale. `FFI::cdef` integra ora callback PHP
sospendibili tramite stack C separati. Le continuation stackless della
Bytecode DSL conservano le attivazioni PHP, mentre il bridge conserva i frame C.

## Limite verificato nella VM installata

Nella Oracle GraalVM 25.4.4.1.1+1.1 installata, il sorgente
`lib/svm/builder/svm.src.zip`, voce
`com/oracle/svm/core/thread/Target_jdk_internal_vm_Continuation.java`, righe
94–115, controlla il `JavaFrameAnchor` e restituisce `FREEZE_PINNED_NATIVE`
quando la continuation attraversa un frame nativo. `doYield` propaga quel
risultato. Non basta quindi sostituire le continuation PHP con quelle dei
virtual thread per conservare liberamente uno stack misto.
Il [sorgente pubblico corrispondente](https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/thread/Target_jdk_internal_vm_Continuation.java)
è un riferimento aggiuntivo; la verifica della versione installata usa lo ZIP locale.

Anche l'API di continuation di Espresso documenta il
[limite dei frame non Java](https://www.graalvm.org/latest/reference-manual/espresso/continuations/#limitations).
Espresso è un componente diverso: non va confuso con la Bytecode DSL PHP o
considerato una soluzione già pronta per questo requisito.

## Bridge integrato

`FFI::cdef` usa ora il bridge per le funzioni con parametri callback. La callback
può sospendersi senza un driver scritto in PHP dall'utente. La funzione interna
`native_invoke` conserva la stessa coroutine, scope e contesto; non crea una
coroutine PHP separata per la callback. Chiamate senza callback conservano il
percorso NFI diretto.

Sono coperti i tipi già accettati dal parser: interi di 8/16/32/64 bit, float,
double, void, stringhe in ingresso, typedef e più parametri callback. Firme e
closure sono costruite con il libffi 3.4.8 incluso nella distribuzione GraalVM
fissata dal progetto, riutilizzando lo stesso ABI di NFI. Non si aggiunge una
seconda versione libffi al link. Il codice mantiene un massimo di 64 parametri
per firma e uno stack C da 1 MiB per chiamata attiva; Windows usa le guardie dei
fiber, Linux mmap con guard page ai due estremi e ucontext.

Gli argomenti C sono posseduti dalla chiamata, le stringhe vengono copiate e
le closure trattengono esplicitamente receiver e ambiente PHP. Le librerie
esterne rimangono radicate nel contesto NFI. Stack, stringhe e closure vengono
liberati al ritorno C, inclusi i percorsi di errore, senza accumularli fino al
termine della richiesta.

Con `async: true` un singolo job del pool nominato conserva il thread C per
tutta la chiamata. Tra due passi attende una future con safepoint Truffle; lo
scheduler esegue la callback sulla coroutine PHP originale. Non viene usato
polling. `max: 1` serializza le chiamate C, comprese le loro attese. Una callback
che attende un'altra chiamata nello stesso pool già saturo può creare un
deadlock: l'annidamento sul percorso sincrono non richiede quel worker.

Se PHP solleva un'eccezione o riceve cancellazione, il finally protetto drena
la chiamata C: la callback corrente e quelle successive ricevono zero (o void),
senza eseguire altro PHP; il codice C ritorna normalmente prima di liberare lo
stack. L'errore originale torna al chiamante dopo il drain. Questo non annulla
effetti già prodotti e zero non equivale a una cancellazione specifica di ogni
libreria. Codice C che non ritorna richiede il proprio protocollo di arresto.

Le callback di questo percorso hanno durata limitata alla chiamata e devono
essere invocate dal suo thread C. Non possono essere registrate in C per uso
successivo; una chiamata da un thread nativo diverso viene rilevata, restituisce
zero e fa fallire l'invocazione con `FFI\\Exception`. Handle persistenti di
registrazione/unregistrazione restano da implementare. Il percorso sperimentale
`ffi_call`/`ffi_callback` conserva le sue regole precedenti per callback NFI
sincrone, incluso il dispatch da thread esterni.

Restano fuori dal type system attuale CData, strutture, puntatori generici,
variadiche e callback che ricevono altre callback. Non è supporto di stack
misti Java/C arbitrari, né una validazione di tutte le DLL o di macOS/mobile.

Verifica riproducibile dell'API ordinaria, con GC forzato a ogni checkpoint:

```text
build.bat ffi-bridge-test
build.bat ffi-bridge-test native
bash build.sh ffi-bridge-test
bash build.sh ffi-bridge-test native
```

La fixture esterna è una DLL/SO di test; il bridge è collegato staticamente nel
prodotto. La fixture verifica variabili locali in 33/65 frame C, affinità,
annidamento, ABI scalari e pulizia dopo eccezioni e cancellazione. Comprende
anche una callback da SQLite built-in. Il benchmark opzionale `/callback`
usa la stessa fixture C e lo stesso PHP in GraalPHP e TrueAsync.

La verifica passa su Windows/Linux x64, JVM e Native Image, con **80 Full GC
effettivi per configurazione**. Passano anche chiamate cancellate in coda,
più callback nella stessa firma e cancellazione dello scope durante
`Async\protect`. Il job nativo non viene cancellato separatamente dallo scope:
il finally della coroutine mantiene la responsabilità del drain. La suite
passa sui due eseguibili del prodotto; su Windows anche nel pacchetto isolato
da Java e `graalphp-native.dll`. [Risultati, hash e log GC](validation/native-bridge-2026-09-25/results.txt).

### Sequenza dello switch

La parte C esegue su uno stack nativo separato, sullo stesso thread OS.
Sullo stack separato non vengono eseguiti Java, Truffle o closure NFI managed.

1. PHP entra nel bridge, che avvia o riprende lo stack C.
2. La libreria chiama un trampoline C fornito dal bridge come callback.
3. Il trampoline conserva argomenti e punto di ritorno, poi cede allo stack
   principale. Il bridge ritorna a Java con un evento di callback pendente.
4. Lo scheduler esegue la callback PHP; questa può attendere I/O e sospendersi
   usando le continuation già esistenti. Lo stack C resta conservato.
5. Il risultato riattiva il trampoline sullo stesso thread; la callback ritorna
   normalmente alla libreria, che continua con i suoi frame e variabili locali.

Una callback PHP può aprire un'altra chiamata C con un altro stack. La catena
logica PHP → C → PHP → C resta quindi rappresentabile senza dover catturare
frame Java e C insieme. Questa soluzione richiede il controllo di ogni confine
FFI: non rende sospendibile qualunque frame host esterno al bridge.

## Prova isolata iniziale

`tests/native/native_stack_probe.c` mantiene stack C con Win32 Fibers su Windows
e `ucontext` su Linux/glibc x64. Ogni stack conserva fino a 33 frame ricorsivi
con variabili locali volatile verificate dopo la ripresa. Una callback tramite
function pointer C produce tre eventi, conservando l'intera catena C aperta.

`tests/graalphp/NativeStackProbe.java` esegue PHP reale tramite Truffle: otto
chiamate concorrenti, poi una catena annidata con tre chiamate C interne. Le
callback PHP usano `Async\delay`, conservano valori PHP e richiedono `System.gc()`
mentre gli stack C sono sospesi. Il codice C e il driver PHP controllano
l'affinità al thread; i risultati attesi sono `520:272`, per 12 stack C e
36 sospensioni di callback. Il bridge non crea worker OS.

La prova usa un protocollo esplicito `create/step/value/close`, visibile nel
PHP del test. Non modifica `NativeCallback`, non aggiunge ancora callback
sospendibili all'API FFI ordinaria e non dimostra un bridge libffi generico.
Non sposta NFI o codice managed su uno stack non registrato con la VM.

Build esclusivamente Xmake, target helper non predefinito; nessuna nuova
dipendenza scaricata e nessuna modifica all'eseguibile prodotto:

```bat
build.bat native-stack-probe
build.bat native-stack-probe native
```

```sh
bash build.sh native-stack-probe
bash build.sh native-stack-probe native
```

Il secondo comando crea un runner Native Image separato. DLL/SO della prova
servono a verificare il confine FFI esterno; non sono nuove dipendenze del prodotto.

### Risultati del 25 settembre 2026

La prova passa su Windows x64 e Linux/glibc x64, sia JVM sia Native Image:
quattro configurazioni, ciascuna con risultato `520:272`, 12 stack C, 36 callback
sospese e controllo di affinità. Le esecuzioni con logging GC confermano
**36 Full GC effettivi per configurazione**, non solo richieste a `System.gc()`.
Il logging usa `-Xlog:gc` su JVM e `-XX:+PrintGC` sul runner Native Image.

[Risultati](validation/native-stack-probe-2026-09-25/results.txt),
[hash](validation/native-stack-probe-2026-09-25/hashes.json) e log GC filtrati
nella stessa directory conservano l'evidenza. I sorgenti della prova coincidono
tra Windows e Linux. Gli eseguibili del prodotto restano invariati:
Windows `82abc20ab25a9fe56f5ebed9e5bcf83bbf32ade535352289917a87797e9d6390`,
Linux `7bae4460b11e75db27fa7bad566b8ce1e56b0b9bd5ce6687dffd14298329e48a`.

Questo dimostra il meccanismo circoscritto sopra, non un supporto già completo
di stack misti arbitrari, librerie reali con ogni tipo FFI o callback sospendibili
trasparenti nel prodotto. Non è un benchmark né una prova di stabilità prolungata.

## Requisiti individuati dalla prova iniziale

Questa sezione conserva le motivazioni del prototipo precedente. Lo stato
dell'implementazione corrente e i limiti residui sono descritti sopra.

- Un dispatcher libffi generico deve creare trampoline C, marshaling di
  argomenti/risultati e nodi di continuation al confine PHP. Non basta passare
  una closure NFI attuale alla libreria sullo stack alternativo.
- Le durate NFI ordinarie non bastano: una downcall ritorna prima che la
  chiamata C logica finisca. Argomenti, stringhe, array e riferimenti a oggetti
  devono essere posseduti dal bridge, copiati o mantenuti validi esplicitamente.
  Nessun puntatore transitorio alla heap Java può sopravvivere senza quel contratto.
- La cancellazione deve far terminare la chiamata C in modo compatibile con
  l'API della libreria. Liberare uno stack sospeso non esegue cleanup C/C++,
  distruttori o rilascio di lock. La prova consente close solo dopo il ritorno.
- Affinità, TLS, rientranza, callback da thread esterni e librerie che mantengono
  lock durante una callback richiedono regole esplicite e test dedicati.
- Una syscall bloccante continua a bloccare il thread finché non ritorna:
  lo stack separato cede nei trampoline, non interrompe automaticamente codice C.
  Restano quindi necessari gli adattatori libuv/cURL e i pool per C bloccante.
- Il backend `ucontext` della prova non costituisce una scelta portabile per
  macOS/mobile o una verifica con sanitizers, segnali, eccezioni native e tutti
  i collector. Guard page, overflow, guest JIT e deoptimization sotto carico
  non sono verificati dalla prova. La compatibilità va dimostrata per ogni
  target supportato; il successo di questo caso non è garanzia per DLL arbitrarie.

L'alternativa che conserva uno stack C su un worker è più semplice, ma occupa
un thread durante l'attesa e richiede di gestire chiamate annidate e pool saturi.
Per il percorso su un solo thread, il bridge a stack C separati è la strada
da verificare prima di ipotizzare modifiche al garbage collector o a SubstrateVM.
