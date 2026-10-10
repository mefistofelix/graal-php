# Primo blocco SPL: EmptyIterator — Windows, 10 ottobre 2026

Il codice introduce `EmptyIterator` come tipo builtin con metodi PHP compilati
nel normale Truffle Bytecode DSL e corregge la classe di eccezione emessa da
`foreach` by-reference su iteratori non-generatori. Il target è PHP 8.6;
TrueAsync 0.10.0 è l'oracolo dei casi async già inclusi nella suite.

## Risultati

| Modalità | Esito | Output confrontati | Rifiuti semantici |
| --- | --- | ---: | ---: |
| Windows JVM | 77/77 | 71 | 6 |
| Windows Native Image con JIT disponibile | 77/77 | 71 | 6 |
| Windows Native Image `--interpreter` | 77/77 | 71 | 6 |

Totale: **231 esecuzioni**, 0 fallimenti. I rifiuti semantici controllano la
categoria diagnostica richiesta e l'exit code, non il testo esatto del fatal.
Nessuna verifica Linux o benchmark prestazionale in questa sessione.

- [Output JVM](results-jvm.txt)
- [Output Native Image](results-native.txt)
- [Output `--interpreter`](results-interpreter.txt)
- [Hash dei prodotti](hashes.json)

## Procedura

- `build.bat iteration-test`: compila JAR e test, confronta 77 programmi.
- `build.bat native`: produce `build/graalphp.exe` tramite Xmake e Native Image,
  con GraalVM 25.4.4.1.1, `-O3`, target compatibility. Completato senza errori.
- Riutilizzando la stessa image, `graalphp.IterationTest` è eseguito con
  `build/graalphp.exe` e poi con lo stesso eseguibile più `--interpreter`.
- `certutil -hashfile build/graalphp.exe SHA256` e analogo comando per il JAR.

Il passaggio di `cmd.exe` è stato fornito come argomento unico `build.bat native`
per avviare correttamente il target. Le stampe di tracing aggiunte al batch
solo per diagnosticare un invocazione non riuscita sono state rimosse prima
della consegna; **`build.bat` mantenuto identico all'originale**. Le due
invocazioni iniziali del comando tramite argv separati si sono fermate nella
compilazione del runner linguistico senza produrre un nuovo eseguibile; non
sono considerate verifiche Native Image.

L'output Native Image riporta 89,32 MiB di file e 5,96 GiB di picco RSS durante
la build. Queste non sono misure della memoria del programma in esecuzione.

Le eccezioni SPL sono ancora rappresentate tramite `PhpError`: non dichiarare
implementati il modello generale di Throwable o le altre classi SPL concrete.
