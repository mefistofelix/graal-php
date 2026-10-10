# ArrayIterator — primo sottoinsieme SPL, 10 ottobre 2026

Questo blocco estende il precedente `EmptyIterator` con `SeekableIterator` e
`ArrayIterator` **parziale**. Il runtime continua a compilare i metodi PHP nel
Truffle Bytecode DSL, con backing sul modello `PhpValues` e senza un nuovo
scheduler o un secondo heap. Le classi SPL complete non sono ancora disponibili.

## Evidenze e verifiche

- `build.bat iteration-test`: **89/89** programmi su Windows/JVM, di cui 83
  confronti output e 6 rifiuti semantici; 12 test nuovi per `ArrayIterator`.
  [Risultati JVM](results-jvm.txt).
- `build.bat verify`: 91 integrazioni, 53 casi semantici/valori,
  256 grafi e 1.280 fasi collector, 56 controlli field-iteration,
  52 controlli degli attributi e 82/82 scenari differenziali TrueAsync 0.10.0.
- L'esempio `examples/arrayiterator.php` stampa `first:1`, `second:2`,
  `0:3`, `3` sia con l'oracolo PHP 8.6 sia con il JAR; il launcher JVM emette
  inoltre un avviso del runtime Truffle su stderr.
- `build.bat native`: **Native Image Windows completata** con GraalVM
  25.4.4.1.1, `-O3` e guest JIT disponibile; 89,55 MiB di dimensione file.
- Il medesimo corpus passa 89/89 sul prodotto Native Image e 89/89 sullo stesso
  eseguibile con `--interpreter` (83 output + 6 rifiuti per modalità):
  **267 esecuzioni totali** in tre modalità, zero fallimenti.
  [Risultati Native Image](results-native.txt) e
  [risultati interpreter](results-interpreter.txt).
- [Hash SHA-256 dei prodotti](hashes.json) calcolati sui binari finali.
- L'esempio passa anche su `build/graalphp.exe` con la stessa sequenza di
  righe dell'oracolo PHP 8.6.

Oracoli: PHP 8.6.0RC2 e TrueAsync 0.10.0 fissati nel progetto.
Nessun warmup escluso perché **non sono stati eseguiti benchmark**. Per i
limiti del blocco vedere [docs/spl.md](../../spl.md).

## Dispatch Windows

Il vecchio label `:native` condivideva il prefisso con vari label `native*`.
Alcune invocazioni di `build.bat native` terminavano erroneamente con
`tests\\graalphp\\.java` non trovato, senza avviare il Native Image compiler.
Le chiamate e gli `goto` ora puntano a `:build_native_image` e il build nativo
finale riesce dal singolo entrypoint `build.bat native`, senza nuovi script.
Il picco RSS del **processo di build** (5,38 GiB) non è la memoria di esecuzione
runtime. Linux non è stato riverificato e non sono stati eseguiti benchmark.
