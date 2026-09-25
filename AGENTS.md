# Istruzioni per lavorare su GraalPHP

## Prima di modificare il progetto

Leggere nell'ordine:

1. [DEV_PREF.md](DEV_PREF.md): preferenze di sviluppo e vincoli operativi.
2. [Stato di consegna](docs/handoff.md): ultimo risultato verificato, limiti,
   comandi, artefatti e riferimenti utili per riprendere senza la conversazione.
3. [TODO.md](TODO.md): attività aperte, dipendenze e criteri di completamento.
4. [Copertura del design](docs/design-coverage.md) e i contratti dell'area toccata.

Il [design PHP/TrueAsync](graalphp_trueasync_design_requirements.md) descrive il
**target**, non certifica funzionalità implementate. Le istruzioni dell'utente
nella sessione corrente prevalgono su questi file. Le decisioni più recenti
riportate qui aggiornano le proposte storiche: non ripartire dai primi spike
solo perché sono ancora descritti nel design.

## Decisioni da preservare

- Target semantico **PHP 8.6**, implementato direttamente su **Truffle Bytecode
  DSL**. La pipeline frontend/IR/backend è già in uso; non introdurre un runtime
  provvisorio da trasporre successivamente su Truffle.
- Priorità: linguaggio (compreso autoload e le feature usate da Composer),
  TrueAsync, threading, FFI e librerie C. Composer come applicazione è rinviato;
  **Compose e mobile per ultimi**. Considerare insieme ownership, sospensioni,
  condivisione e codice dinamico quando si estende il linguaggio.
- API async basata su TrueAsync: confrontare stub, sorgenti e binari della
  release fissata, senza dedurre i dettagli dai nomi delle funzioni. Distinguere
  estensioni nostre e API upstream. Vedere [contratto](docs/trueasync-compatibility.md).
- Lo spawn su thread previsto condivide **runtime e heap PHP**; non deve creare
  un'altra istanza PHP né sostituire la condivisione con copie. Il vecchio
  `parallel` è un laboratorio. LOCAL→SHARED deve preservare identità, COW,
  reference e integrità di ownership/GC. La sincronizzazione applicativa è
  esplicita: mutex e channel non implicano lock globali a ogni accesso.
- FFI dinamica con `async` opzionale, pool nominato e worker su richiesta;
  `FFI::definePool` configura min/max/coda. `max: 1` serializza soltanto le
  chiamate dello stesso pool. Servono librerie DLL/SO esterne e catalogo statico
  `builtin:*`. [Contratto threading/FFI](docs/threading-ffi.md).
- Conservare le continuation PHP e il bridge degli stack C separati. Il bridge
  consente callback PHP sospendibili ai confini controllati; **non** rende
  sospendibile qualunque stack misto Java/C. Non eseguire managed/NFI su stack C
  non registrati. [Stato e limiti](docs/native-stack-bridge.md).
- PHP e callback libuv eseguono sul thread proprietario. CLI senza thread libuv
  separato; embedding con helper di sola readiness, callback sul dispatcher host.
  Patch Electron applicata da Xmake, wakeup e timer one-shot per vere deadline:
  niente tick periodico artificiale. [Reactor](docs/reactor.md).
- Le semantiche PHP di distruzione e cicli vanno implementate esplicitamente;
  non confondere GC della VM, ownership guest e compatibilità dei distruttori.
- Non aggiornare il curl interno di curl-impersonate indipendentemente dalla sua
  release. cURL standard è un provider parallelo, con simboli/TLS isolati.
- Obiettivo: eseguibile Native Image con librerie applicative statiche. Oggi
  Linux dipende da libc/libm/loader e Windows dal CRT MSVC. Full-static e SoLo
  restano lavoro futuro, non capacità già dimostrate. [Distribuzioni](docs/static-distributions.md).

## Build e verifica

- Xmake è l'unico grafo di build nativo. Ingressi: root `build.bat` e `build.sh`.
  Niente CMake o script paralleli di setup. Per le ricette native leggere
  [native-build.md](docs/native-build.md) e le istruzioni/documenti del checkout
  php-xmake di riferimento, se presente. L'adattamento Linux è richiesto
  esplicitamente dall'utente anche dove upstream descrive soltanto Windows.
- Toolchain e dipendenze sono fissate nei build script e in `dependencies.lock`;
  aggiornare insieme versioni, checksum, sorgenti e verifiche pertinenti.
- `src/`, `tests/`, `examples/`, root e `docs/` contengono lavoro mantenuto.
  `build/` e `tools/` sono ricreabili: nessun sorgente, patch o decisione unica lì.
  Non modificare a mano sorgenti scaricati; mantenere le trasformazioni in Xmake.
- Completare blocchi coerenti prima di testare. Eseguire controlli pertinenti al
  rischio, senza suite a ogni microstep. Non ampliare automaticamente una
  campagna cURL a WebSocket/SQLite/FFI; farlo quando la modifica lo richiede.
- **Nessun warmup separato o scartato nei benchmark.** Contare JIT e prime
  richieste; riportare confini temporali, startup, CPU, RAM e latenze. Processi
  nuovi, ordine alternato, dati grezzi e fallimenti conservati. I vecchi rapporti
  con warmup sono storici, non baseline confrontabili con un metodo diverso.
- Separare controlli senza cronometro e raccolta delle latenze; RSS non è private
  commit né dimensione dell'eseguibile. Documentare picchi e compromessi del p99.
- Nessuna build o altra suite durante le misure prestazionali; una sola build
  Native Image alla volta. Preservare e identificare con SHA-256 il binario
  prima di sovrascriverlo per un'A/B. Consegnare prodotti coerenti con i sorgenti
  quando si modifica il runtime; una modifica solo documentale non li richiede.
- UTF-8 e LF; `.gitignore` a inclusioni. Non introdurre `.gitattributes` o cambiare
  impostazioni Git globali. Non pubblicare percorsi personali nei log archiviati.

## Prima di consegnare

Aggiornare [TODO.md](TODO.md) e [handoff.md](docs/handoff.md) con stato reale,
prossimo passo, decisioni cambiate, verifiche eseguite e non eseguite. Aggiornare
copertura/contratti se cambia il comportamento. Collegare evidenze persistenti
in `docs/`; non sostituire le prove storiche né dichiarare tutto compatibile
perché passa un sottoinsieme. Questo file contiene regole stabili: risultati,
versioni dell'ultima build e dettagli della sessione appartengono all'handoff.
