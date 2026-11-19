# Cast scalari e literal PHP — 19 novembre 2026

Verifica funzionale dei cast canonici int, float, bool e string.
Il corpus copre prefissi numerici, valori fuori range, formattazione,
stringhe binarie, precedenza, costanti e warning. I literal usano esponenti,
punto iniziale o finale ed escape binari nelle stringhe interpolate.
Il metodo magico può essere ereditato o importato da un trait, rispetta
il tipo di ritorno effettivo e usa una continuation quando si sospende.

| Piattaforma | JVM | Native Image | Native Image in modalità interprete |
| --- | --- | --- | --- |
| Windows | [194](windows-jvm.txt) | [194](windows-native.txt) | [194](windows-interpreter.txt) |
| Linux x64 WSL | [194](linux-jvm.txt) | [194](linux-native.txt) | [194](linux-interpreter.txt) |

1.164 esecuzioni, 15 programmi nuovi e 179 regressioni per modalità.
Windows confronta i casi normali con PHP 8.6 RC2. Il test di sospensione
e le prove Linux usano TrueAsync 0.10.0 / PHP 8.6-dev.
Passano anche le [verifiche Windows](windows-verify.txt) e
[Linux](linux-verify.txt). [SHA-256 dei prodotti](products.txt).

Per ripetere i casi usare `build.bat scalar-cast-test` o
`bash build.sh scalar-cast-test`, con gli stessi argomenti Native Image
e `--interpreter` degli altri test di linguaggio.

Restano cast array e object, alias deprecati, altri formati di literal,
diagnostica completa dei magic methods e configurazione della precisione.
La formattazione usa la precisione PHP predefinita di 14 cifre significative.
Le prove di questo blocco riguardano solo la compatibilità funzionale.
