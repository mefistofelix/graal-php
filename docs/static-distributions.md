# Distribuzioni Linux e caricamento dinamico

La distribuzione attuale incorpora SQLite, PCRE2, zlib, libuv e la chiusura
curl-impersonate/BoringSSL/nghttp2/Brotli/zstd
nell'eseguibile GraalPHP. Il bridge `.so`/DLL rimane per JVM e test e non è
necessario al catalogo `builtin:*` del Native Image. Le librerie esterne
caricate esplicitamente dall'applicazione via FFI restano esterne.
Su Linux il binario verificato dipende da libc/libm di sistema; su Windows
restano le DLL del sistema e del CRT MSVC previsto dal progetto.

Il design mantiene due distribuzioni possibili, da completare in futuro:

| Profilo | Librerie incorporate | Librerie esterne |
| --- | --- | --- |
| Full-static | Runtime musl e librerie ufficiali nello stesso eseguibile | FFI verso built-in; caricamento dinamico disabilitato |
| Portable-FFI | Librerie applicative statiche; libc/libm del sistema | `dlopen` del sistema tramite NFI |

Solo il secondo comportamento è verificato oggi. Una futura build full-static
deve controllare l'assenza di PT_INTERP/DT_NEEDED, mantenere FFI verso i simboli
incorporati e rifiutare esplicitamente richieste di caricamento non supportate.
La [guida GraalVM](https://www.graalvm.org/dev/reference-manual/native-image/guides/build-static-executables/)
distingue il collegamento statico con musl dal profilo con libc dinamica.

## Candidato SoLo

Esaminato [pg83/solo](https://github.com/pg83/solo), commit
`f07af9b89676336c0fd059d225ee81d7b5473d3e`. Non è ancora una dipendenza della
build né una compatibilità verificata con GraalPHP.

SoLo fornisce un loader ELF e un ponte dall'ABI glibc al runtime musl già
presente nel processo. Può quindi rendere possibile una terza variante:
eseguibile musl statico che carica alcuni `.so` glibc senza caricare una
seconda libc. Il repository riporta prove Vulkan e caricamento di molti DSO;
questo non dimostra ancora che tutte le loro funzioni siano utilizzabili.

I vincoli rilevanti dichiarati nei suoi sorgenti e README sono:

- Linux x86-64/aarch64; `dlclose` non scarica le immagini.
- Le chiamate glibc non implementate raggiungono stub che abortiscono il
  processo: caricare correttamente una libreria non basta come verifica.
- TLS initial-exec usa un'arena di 16 KiB. Thread già esistenti al momento
  del caricamento vedono TLS inizializzato a zero per i nuovi moduli: occorre
  verificare i valori iniziali e l'ordine di preload rispetto ai worker.
- Il registro dei provider statici va completato prima dei thread e del
  primo caricamento, secondo `lib/dlfcn.h`.
- L'header redirige le chiamate con macro verso `stub_dlopen/stub_dlsym`.
  Includerlo nel nostro shim non modifica automaticamente il backend NFI
  precompilato: il percorso di risoluzione deve essere collegato esplicitamente.

Prima di adottarlo servono una ricetta Xmake limitata al loader, un Native
Image musl con NFI/JIT funzionante e una verifica combinata di chiamate reali,
callback C→PHP, thread C collegati a NFI, worker avviati prima/dopo il load,
TLS, allocator/errno e shutdown. I provider built-in esistenti sono un punto
di collegamento possibile. La selezione del loader deve restare sotto la
stessa API PHP FFI, senza cambiarne la semantica né creare un secondo runtime.
