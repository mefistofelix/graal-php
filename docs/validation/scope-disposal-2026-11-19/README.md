# Chiusura degli scope TrueAsync — 19 novembre 2026

GraalPHP verifica gli stati di uno scope e il drenaggio su TrueAsync 0.10.0.
Il corpus aggiunge sei programmi mantenuti e tre PHPT upstream intatti.
I programmi esercitano chiusura attiva, policy ereditata, lavoro che
prosegue in chiusura sicura, timeout, scope vuoti, notifiche e attese
già registrate. I file di ogni caso vengono generati in una directory
isolata per evitare che il caricamento della code root includa altri casi.

| Piattaforma | JVM | Native Image | Native Image in modalità interprete |
| --- | --- | --- | --- |
| Windows | [97](windows-jvm.txt) | [97](windows-native.txt) | [97](windows-interpreter.txt) |
| Linux x64 WSL | [97](linux-jvm.txt) | [97](linux-native.txt) | [97](linux-interpreter.txt) |

Totale 582 esecuzioni. Passano le regressioni integrate
[Windows](windows-verify.txt) e [Linux](linux-verify.txt).
[SHA-256 dei prodotti](products.txt).

Uno scope chiuso in sicurezza conserva il lavoro in corso fino al
drenaggio. Le attese di completamento e di drenaggio osservano fasi
distinte. Il timer di chiusura usa il prossimo risveglio del reactor,
conserva la prima deadline applicabile e viene sganciato alla chiusura.

Con TrueAsync 0.10.0, la prova sperimentale con due chiamate a
`disposeAfterTimeout()` pendenti sullo stesso scope ha stampato
`zend_mm_heap corrupted (double free)` su Windows e Linux. Il
confronto mantiene il caso di una deadline e conta soltanto le
esecuzioni terminate correttamente. I PHPT che invocano `var_dump`
restano fuori dal corpus perché il builtin è ancora assente.

Questo blocco verifica il comportamento funzionale. Restano aperti
handler di errore, finalizer, distruzione implicita degli scope e
warning. Misure cURL dedicate rinviate alla consegna della policy
degli errori.
