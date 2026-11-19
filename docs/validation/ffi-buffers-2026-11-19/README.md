# Buffer FFI posseduti — 19 novembre 2026

`FFI\CData` rappresenta un buffer di byte nativo azzerato posseduto dal
grafo della richiesta. Le API di memoria controllano le dimensioni e
preservano i byte. Le dichiarazioni accettano puntatori in input a valori
primitivi e a void. Le chiamate trattengono il buffer fino al ritorno fisico
dal C, anche quando una callback sospesa perde l'ultimo alias PHP o viene
cancellata. La fixture controlla l'identità tramite WeakReference prima e
dopo il ritorno e verifica che il C completi il proprio lavoro.

| Piattaforma | JVM con 82 richieste di GC | Native Image CLI | Native Image CLI in modalità interprete |
| --- | --- | --- | --- |
| Windows | [Passa](windows-jvm.txt) | [Passa](windows-native.txt) | [Passa](windows-interpreter.txt) |
| Linux x64 WSL | [Passa](linux-jvm.txt) | [Passa](linux-native.txt) | [Passa](linux-interpreter.txt) |

Le modalità CLI verificano il lifetime attraverso sospensioni. Le richieste
esplicite di GC della JVM usano la callback host del runner dedicato.
Passano anche [integrazione e TrueAsync Windows](windows-regressions.txt)
e [Linux](linux-regressions.txt). Il corpo della prova comune ha lo stesso
output di PHP 8.6 RC2 con l'estensione FFI abilitata su Windows. Su Linux
si verifica il risultato della stessa prova contro la libc locale.
[SHA-256 dei prodotti](products.txt).

## Riproduzione

`build.bat ffi-bridge-test` e `bash build.sh ffi-bridge-test` costruiscono
anche la fixture locale con Xmake. `build.bat native` e `bash build.sh native`
producono gli eseguibili della tabella. Per il percorso CLI usare
`tests/php/ffi-bridge.php` con `FFI_TEST_LIBRARY` impostata sul file fixture
DLL o SO locale e `GRAALPHP_REACTOR=libuv`. Il programma comune è
`tests/php/ffi-buffer-common.php` con `FFI_TEST_CRT=ucrtbase.dll` su Windows
o `FFI_TEST_CRT=libc.so.6` su Linux.

L'entry Windows genera una copia CRLF sotto `build/` prima di eseguire
le subroutine. Questo risolve la deviazione osservata di `CALL :native_libraries`,
che con LF portava `ffi-bridge-test` al comando Native Image senza
inizializzare il toolchain. Il sorgente mantiene LF.

Mancano l'indicizzazione CData, i puntatori restituiti o forniti alle
callback, layout C complessi e memoria persistente. Non sono state eseguite
misure di throughput o latenza per questa consegna.
