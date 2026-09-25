# Variante intermedia

Controllo: cleanup/stringhe/shim e collector ottimizzati, Native Image -O1.
Candidato: modifiche al dispatch e Native Image -O3.
Due ripetizioni, 65.536 richieste misurate per prova.

Questo candidato includeva anche un percorso diretto Float/Double in
NativeAccess.normalize. Prima del prodotto finale è stato rimosso per
conservare il comportamento generico preesistente dei numeri floating point.
Il benchmark cURL esercita ritorni scalari interi; non verifica ABI FFI double.
Le campagne finali usano un eseguibile ricompilato dai sorgenti corretti,
con SHA256 distinto riportato nei rispettivi metadati.
