# ArrayAccess

Blocco funzionale del 27 settembre 2026, successivo ai
[generatori](generators.md). L'obiettivo è integrare l'overloading degli offset
nel normale Bytecode DSL senza trasformare le location native di array/stringhe
in callback e senza introdurre un secondo interprete.

## Interfaccia e firme

`ArrayAccess` è una builtin interface con le quattro firme PHP:

- `offsetExists(mixed $offset): bool`;
- `offsetGet(mixed $offset): mixed`;
- `offsetSet(mixed $offset, mixed $value): void`;
- `offsetUnset(mixed $offset): void`.

Le firme sono trattate come tentative return types, come Iterator/Countable:
una implementazione legacy può usare `#[ReturnTypeWillChange]` entro il
meccanismo di diagnostica già esistente.

## Dispatch dinamico

Un'espressione `$container[$key]` prepara un handle temporaneo che valuta
container e chiave una sola volta.

Se il container è una normale location PHP con array/stringa, l'handle conserva
la vera `PhpValues.Location`: COW, reference, string offset, append e
by-reference argument continuano quindi a usare il value model esistente.

Se il valore è un oggetto che implementa `ArrayAccess`, l'handle trattiene
receiver e chiave e usa il normale dispatcher di metodi PHP. Non c'è un path
sincrono separato: `offsetGet`, `offsetSet`, `offsetExists` e
`offsetUnset` possono sospendersi tramite TrueAsync e riprendono la stessa
continuation Truffle.

Gli handle vengono chiusi in `finally`, quindi receiver/key temporanei restano
vivi attraverso una sospensione ma vengono rilasciati anche in caso di errore o
cancellazione.

## Operazioni PHP coperte

Il lowering segue l'ordine osservabile di PHP 8.6:

- lettura: `offsetGet($key)`;
- scrittura: `offsetSet($key, $value)`;
- append: `offsetSet(null, $value)`;
- `unset`: `offsetUnset($key)`;
- `isset`: soltanto `offsetExists($key)`;
- `empty`: `offsetExists`, poi `offsetGet` solo se esiste;
- `??`: `offsetExists`, poi `offsetGet`; un valore null usa il fallback;
- `??=`: stessa probe, con `offsetSet` soltanto quando serve;
- compound assignment come `+=`: `offsetGet`, operazione, `offsetSet`;
- pre/post incremento su un offset overloaded by-value: `offsetGet`, notice
  `Indirect modification of overloaded element ... has no effect`, nessuna
  chiamata `offsetSet`, coerente con PHP.

Le scritture su receiver temporanei oggetto sono valide: l'identità dell'oggetto
viene trattenuta fino alla fine dell'operazione.

## Native Image

Il path di riconoscimento dinamico `implements ArrayAccess` è dietro una
`TruffleBoundary`: lasciare `RuntimeClass.isA()` entrare nella partial
evaluation guest faceva fallire Native Image. Anche la formattazione del notice
di modifica indiretta è confinata da una barriera locale per non rendere
raggiungibili gli helper StringBuilder blocklisted dal runtime compiler.

Queste sono barriere puntuali; JIT guest, compilation tier e default GC non sono
stati disabilitati o cambiati.

## Limiti

Questo blocco non chiude il return-by-reference generale di VALUE-01.
In particolare un `offsetGet()` dichiarato **by-reference** non espone ancora
la location restituita al chiamante. Di conseguenza restano aperti:

- modifica annidata che dipende da `&offsetGet`, per esempio
  `$a['nested']['x'] = ...` quando l'offset deve essere una reference;
- passaggio by-reference di un offset overloaded con la semantica completa di
  `&offsetGet`;
- altre combinazioni che richiedono return-by-reference dal frontend/runtime.

Le normali location array/stringa non hanno questo limite e sono incluse nel
corpus di regressione.

Le classi SPL concrete (`ArrayIterator`, `ArrayObject`, ecc.) non sono
implementate da questo blocco; sono il passo successivo.

## Verifica

`tests/graalphp/ArrayAccessTest.java` contiene 20 programmi: 18 confrontano
l'output con PHP 8.6 / TrueAsync 0.10.0 e due richiedono rifiuto semantico.
Coprono interfaccia/firme, read/write/append/unset, isset/empty/coalesce,
`??=`, compound assignment, incrementi overloaded, receiver temporanei,
valutazione unica della chiave, proprietà, nested read, regressioni array/string
offset e sospensioni TrueAsync in tutti e quattro i metodi.

Il corpus passa su Windows e Linux in JVM, Native Image e sullo stesso Native
Image con `--interpreter`: **120 esecuzioni** complessive, 108 confronti di
output e 12 rifiuti semantici.

Regressioni finali:

- Windows `verify`: 91 integrazioni, 53 scenari valori, 256 grafi / 1.280 fasi
  collector, 56 field-iteration, 52 metadata e 82/82 TrueAsync;
- Linux `verify`: 85 integrazioni, 53 scenari valori, 256 / 1.280, 56 e 52;
- Generator: 37/37 Windows e Linux JVM;
- Unpack: 46/46 Windows e Linux JVM.

Le evidenze sono in
[`docs/validation/arrayaccess-2026-09-27/`](validation/arrayaccess-2026-09-27/).
