# Riferimenti a proprietà tipizzate — 19 novembre 2026

Le proprietà tipizzate registrano i propri vincoli sulle celle reference
condivise. Le prove controllano inizializzazione implicita nullable,
coercizione debole e strict, binding senza effetti parziali, alias
multipli, clone, unset, raccolta dei cicli, proprietà statiche,
ritorno per riferimento e passaggio per riferimento sospendibile.

## Verifica funzionale

Sono passati 10 programmi nuovi e 108 regressioni per modalità,
per 708 esecuzioni su Windows e Linux. Windows usa PHP 8.6.0RC2 per i casi
sincroni e TrueAsync 0.10.0 per i casi asincroni. Linux usa il binario
TrueAsync 0.10.0, basato su PHP 8.6-dev.

| Piattaforma | JVM | Native Image | Native Image in modalità interprete |
| --- | --- | --- | --- |
| Windows | [118](windows-jvm.txt) | [118](windows-native.txt) | [118](windows-interpreter.txt) |
| Linux x64 WSL | [118](linux-jvm.txt) | [118](linux-native.txt) | [118](linux-interpreter.txt) |

Le regressioni integrate passano su [Windows](windows-verify.txt) e
[Linux](linux-verify.txt). [Prodotti e baseline SHA-256](products.txt).

## A/B CLI Linux senza warmup

Confronto Native Image contro `dcff758`. Cinque processi freschi per riga
e build, ordine alternato, 524.288 iterazioni a processo. L'intervallo
include startup, compilazione guest, elaborazione e uscita. I programmi
misurano il costo del percorso già disponibile dei ritorni per riferimento,
con una variabile scalare (`call`) o un array annidato (`index`). Le somme
di controllo coincidono. Le nuove operazioni tipizzate sono verificate
funzionalmente. Dati di rete e percentili per richiesta non raccolti.

| Carico | Wall mediano s prima → dopo | CPU mediana s prima → dopo | Picco RSS mediano MiB prima → dopo |
| --- | ---: | ---: | ---: |
| call | 0.77 → 0.81 | 0.86 → 0.90 | 179.06 → 179.73 |
| index | 0.96 → 1.00 | 1.11 → 1.16 | 194.63 → 194.27 |

[Misure per processo](linux-cold.csv) e
[runner](../reference-return-2026-11-18/run-linux.sh), invocato con
`REFERENCE_ITERATIONS=524288`. I campioni brevi mostrano un aumento
del costo mediano sul percorso scalare. Con questa serie non si separa
il costo a regime dalle differenze di compilazione e memoria del processo.
