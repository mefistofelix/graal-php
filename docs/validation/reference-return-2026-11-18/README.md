# Ritorni per riferimento e semantica degli offset PHP

Il blocco porta la cella oltre il frame che la restituisce e oltre una
sospensione. Verifica alias a un locale, forwarding, argomenti nominati,
snapshot per valore, coercione del tipo di ritorno, caricamento di callable,
foreach e rifiuto di una override incompatibile. Su ArrayAccess il corpus
copre incremento, assegnazione composta, modifiche annidate, argomenti e
`&offsetGet` sospendibile. [Contratto](../../arrayaccess.md).

La baseline pubblicata `10ccfd0` ha sei difformità nel corpus delle
stringhe. L'assegnazione scartava il risultato byte-oriented della location
e `empty` e `??` usavano le modalità di lettura errate. Il corpus generatori
mostrava inoltre una classe di eccezione errata per foreach by-reference
su un generatore che produce per valore. I risultati della baseline
sono archiviati insieme a quelli del candidato.

## Verifica

| Host | JVM | Native Image | Native Image `--interpreter` |
| --- | --- | --- | --- |
| Windows x64 | [99/99](windows-jvm.txt) | [99/99](windows-native.txt) | [99/99](windows-interpreter.txt) |
| Linux x64 (WSL) | [99/99](linux-jvm.txt) | [99/99](linux-native.txt) | [99/99](linux-interpreter.txt) |

Ogni modalità include 35 ArrayAccess, 27 string offsets e 37 generatori.
Sono 594 esecuzioni complessive. Le suite JVM aggiuntive coprono contratti
delle classi, SPL/iterazione, unpacking, integrazioni del runtime e collector.
TrueAsync Native Image passa 88/88 su [Windows](windows-native-trueasync.txt)
e [Linux](linux-native-trueasync.txt).

[Baseline](baseline-windows.txt), [regressioni Windows](windows-regressions.txt),
[regressioni Linux](linux-regressions.txt) e [hash dei prodotti](products.txt).

## Riproduzione

Su Windows eseguire `build.bat verify` per i test integrati e compilare
l'eseguibile con `build.bat native`. Su Linux usare `build.sh verify` e
`build.sh native`. I runner `graalphp.ArrayAccessTest`,
`graalphp.StringOffsetsTest` e `graalphp.GeneratorTest` accettano nell'ordine
i path degli oracoli PHP e TrueAsync. Per i confronti nativi passare come
terzo argomento il path del Native Image e aggiungere `--interpreter` per
la modalità interprete. Windows usa PHP 8.6.0RC2 e TrueAsync 0.10.0. Su
Linux il binario TrueAsync 0.10.0 include PHP 8.6-dev per entrambi gli usi.

Il benchmark Linux confronta la baseline e il candidato con lo stesso
programma PHP. Ogni processo esegue 32.768 o 524.288 chiamate e incrementi. La variante
`index` legge e scrive in un array annidato a due livelli. L'altra conserva
lo stato in una variabile scalare. Ogni processo produce un checksum.
Le misure includono startup, JIT e shutdown. Il runner alterna l'ordine,
usa il tempo monotono di `/proc/uptime` e registra CPU user+system e massimo
RSS con GNU time.

```bash
bash docs/validation/reference-return-2026-11-18/run-linux.sh build/graalphp-reference-before build/graalphp
```

Per il carico lungo impostare `REFERENCE_ITERATIONS=524288` prima del comando.

| Operazioni | Carico | Wall s prima → dopo | CPU s prima → dopo | Picco RSS MiB prima → dopo |
| ---: | --- | ---: | ---: | ---: |
| 32768 | `call` | 0.09 → 0.09 | 0.10 → 0.10 | 127.12 → 128.62 |
| 32768 | `index` | 0.10 → 0.11 | 0.11 → 0.11 | 127.12 → 128.50 |
| 524288 | `call` | 0.75 → 0.74 | 0.80 → 0.80 | 163.48 → 164.56 |
| 524288 | `index` | 0.88 → 0.92 | 0.99 → 1.02 | 177.06 → 184.81 |

Le celle sono mediane su cinque avvii per build e carico. Il carico lungo
mostra un costo aggiuntivo sul percorso annidato. La risoluzione temporale
è 10 ms e pesa maggiormente sui processi brevi.

[Dati brevi](linux-cold-short.csv) e [dati lunghi](linux-cold-long.csv).
