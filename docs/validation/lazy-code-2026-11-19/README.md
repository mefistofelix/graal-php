# Caricamento su richiesta — 19 novembre 2026

In assenza di watch si esegue l'entry già compilata dal frontend. Il
primo include di un file compila l'unità. Successivi accessi dal medesimo
contesto riusano il codice compilato. Le root ammesse e le esclusioni dei
file inclusi rispecchiano l'indice precedente. In modalità watch restano
la compilazione della root, la pubblicazione atomica del batch e lo
snapshot per richiesta, anche quando il primo autoload avviene dopo
l'aggiornamento. La verifica integrata include inoltre un file adiacente
sintatticamente invalido e include dinamici annidati in modalità normale.

| Piattaforma | JVM | Native Image | Native Image in modalità interprete |
| --- | --- | --- | --- |
| Windows | [38](windows-jvm.txt) | [38](windows-native.txt) | [38](windows-interpreter.txt) |
| Linux x64 WSL | [38](linux-jvm.txt) | [38](linux-native.txt) | [38](linux-interpreter.txt) |

228 confronti autoload. Passano anche [verifica Windows](windows-verify.txt)
e [Linux](linux-verify.txt), compresi i controlli sui reload.
[SHA-256 e dimensioni dei prodotti](products.txt).

## Avvio CLI Linux

A/B Native Image rispetto a `e436b6b`. Ogni processo esegue un entry file,
risolve un include e termina. Due root contengono, rispettivamente, zero
o 512 moduli PHP aggiuntivi validi che l'entry non usa. Dieci processi
freschi per riga, ordine alternato, output verificato. Il wall time include
lancio e uscita. CPU di processo con risoluzione di 10 ms, quindi uno zero
indica una misura sotto risoluzione. RSS è il picco di ogni processo.

| File inutilizzati | Build | Wall mediano ms | Min–max ms | CPU mediana ms | Mediana del picco RSS MiB |
| ---: | --- | ---: | ---: | ---: | ---: |
| 0 | Prima | 5.84 | 5.48–17.77 | 0 | 26.50 |
| 0 | Dopo | 5.76 | 5.25–6.58 | 0 | 26.12 |
| 512 | Prima | 46.39 | 45.42–62.12 | 40 | 51.75 |
| 512 | Dopo | 6.55 | 5.38–7.09 | 0 | 26.12 |

[Runner](run-linux.sh) e [40 misure](linux-cold.csv).

## cURL standard Windows da processo fresco

Stesso A/B, 24 processi nuovi verificano 786.432 body. 64 client,
512 richieste ciascuno, peer HTTP/1.1 locale. Tutte le misure includono
startup e prime richieste. Throughput e CPU provengono dai processi senza
cronometro per richiesta. Memoria campionata esternamente ogni 25 ms.
I percentili di latenza usano 6.144 campioni per riga. L'avvio termina
al primo messaggio di controllo e il wall time totale al messaggio
di completamento, prima dell'uscita del processo.

| Sospese | Build | Avvio ms | Req/s | CPU µs/req | RSS p50 MiB | Picco RSS MiB | p20 ms | p50 ms | p90 ms | p95 ms | p99 ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | Prima | 34.760 | 23775.432 | 49.114 | 108.969 | 200.887 | 2.489 | 2.671 | 3.246 | 3.574 | 5.486 |
| 1000 | Dopo | 29.423 | 23554.809 | 50.068 | 127.074 | 158.543 | 2.429 | 2.572 | 3.052 | 3.332 | 5.803 |
| 10000 | Prima | 31.931 | 21183.532 | 55.790 | 160.070 | 186.348 | 2.419 | 2.543 | 2.950 | 3.178 | 4.011 |
| 10000 | Dopo | 30.757 | 21091.949 | 53.883 | 160.219 | 185.148 | 2.385 | 2.507 | 2.946 | 3.188 | 4.065 |

Con 1.000 sospese l'avvio mediano migliora di 5,34 ms e il p50 RSS
aumenta di 18,11 MiB. Il picco osservato diminuisce e il p99 aumenta
da 5,49 a 5,80 ms. Con 10.000 sospese i valori cambiano poco.
Queste prove brevi non isolano la causa delle differenze di tail e RSS.

[Ambiente e hash](curl/environment.txt), [prove](curl/results.csv),
[campioni per processo](curl/samples.zip).
