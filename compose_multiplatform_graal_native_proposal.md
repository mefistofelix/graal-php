# Compose Multiplatform su GraalVM Native Image
## Proposta di porting / backend Graal per riutilizzare Compose su desktop e mobile

**Data:** 19 settembre 2026  
**Stato:** proposta architetturale / documento di fattibilità iniziale  
**Obiettivo:** valutare e definire un percorso per eseguire il massimo possibile dello stack Kotlin Compose/Compose Multiplatform dentro una singola GraalVM Native Image, riutilizzando il codice Kotlin comune e sostituendo soltanto gli strati platform-native con binding/FFI compatibili con Graal.

---

# 1. Obiettivo generale

L'obiettivo è evitare un'architettura con due runtime applicativi separati, ad esempio:

```text
Flutter/Dart runtime
      ↕ FFI
GraalPHP/Truffle runtime
```

e convergere invece verso:

```text
Kotlin/Compose
      +
GraalVM Native Image
      +
Truffle/GraalPHP
```

nello stesso runtime e nello stesso processo.

L'idea è:

1. compilare il codice Kotlin/Compose compatibile JVM normalmente;
2. includerlo nella GraalVM Native Image;
3. includere nello stesso Native Image anche il runtime PHP implementato su Truffle;
4. riutilizzare il più possibile il codice Compose comune;
5. sostituire esclusivamente gli adapter/platform backend che oggi dipendono da:
   - Kotlin/Native;
   - JNI;
   - Objective-C/Swift interop;
   - API specifiche Android;
   - API native desktop;
6. generare automaticamente gran parte dei binding necessari;
7. mantenere un singolo runtime applicativo e, quando possibile, un singolo main thread/UI loop.

---

# 2. Motivazione

Il vantaggio principale rispetto a Flutter non è semplicemente la performance.

È soprattutto la **riduzione dei confini tra runtime**.

Con Flutter:

```text
Dart VM / AOT runtime
        ↕
C ABI / FFI
        ↕
GraalVM Native Image + Truffle
```

Con Compose su Graal:

```text
Kotlin bytecode
Compose Runtime
Compose UI
GraalPHP/Truffle
        ↓
un'unica Native Image
```

Il bridge nativo rimane soltanto nel punto in cui è realmente necessario:

```text
Native Image
   ↓
OS / UI toolkit / graphics API / Skia / platform services
```

Quindi il confine FFI è verso **la piattaforma**, non tra il framework UI e il runtime applicativo PHP.

---

# 3. Principio fondamentale

Non vogliamo riscrivere Compose.

Vogliamo:

> **prendere tutto ciò che è Kotlin comune/JVM-compatible così com'è, e sostituire metodicamente soltanto il layer che entra nel mondo nativo.**

Schema:

```text
Compose common code
Compose Runtime
Compose Foundation
Compose UI
Material
layout
state
recomposition
scene logic
text/layout logic riutilizzabile
        │
        │ riuso quasi diretto
        ▼
Graal Native Image
        │
        ├── Truffle / GraalPHP
        │
        └── Platform Backend
              ├── Windows
              ├── macOS
              ├── Linux
              ├── Android
              └── iOS
```

---

# 4. Cosa possiamo riutilizzare direttamente

Tutto il codice che:

- è Kotlin puro;
- compila a bytecode JVM;
- non richiede runtime reflection dinamica non dichiarata;
- non dipende direttamente da Kotlin/Native intrinsics;
- non dipende da API platform-only non sostituibili;

è un candidato naturale per essere compilato dentro Native Image.

Aree tipicamente riutilizzabili:

- Compose Runtime;
- state management;
- snapshot system;
- recomposition;
- layout engine;
- modifier chain;
- coroutine integration compatibile;
- scene graph logico;
- Material;
- Foundation;
- gestures, salvo ingresso raw platform;
- focus model, salvo bridge platform;
- semantics tree, salvo accessibility backend;
- text layout logic comune;
- resource logic portabile;
- animation;
- scheduling logico;
- event dispatch sopra il platform adapter.

L'obiettivo è preservare il più possibile il codice upstream.

---

# 5. Cosa NON si riutilizza direttamente

Le parti da sostituire o adattare sono quelle che fanno il salto verso:

- UIKit/AppKit;
- Objective-C runtime;
- Android framework APIs;
- JNI;
- Win32;
- Cocoa;
- X11/Wayland;
- Metal;
- OpenGL/Vulkan/Direct3D quando usati dal backend;
- Skia native;
- clipboard;
- input method;
- IME;
- accessibility;
- drag & drop;
- window creation;
- frame clock / VSync;
- cursor;
- monitor/DPI;
- platform events;
- filesystem/platform services quando specifici.

Non si tratta di riscrivere la logica di Compose.

Si tratta di sostituire il **binding boundary**.

---

# 6. Idea del porting

Il modello deve essere:

```text
existing Compose/Kotlin Native backend
        ↓
identify native-facing calls
        ↓
preserve surrounding Kotlin logic
        ↓
replace native invocation syntax
        ↓
Graal-compatible native binding
```

Per esempio, concettualmente:

```text
Kotlin/Native:
    platform.UIKit.SomeCall(...)

Graal backend:
    UIKitBindings.someCall(...)
```

La logica superiore:

```text
event handling
coordinate conversion
state management
scene update
frame scheduling
```

rimane per quanto possibile uguale.

---

# 7. Non è un “find/replace” puro

Il porting è metodico, ma non totalmente meccanico.

Esistono categorie diverse:

## A. Chiamate native leaf

Queste sono quasi meccaniche.

Esempio concettuale:

```text
native API call
→ generated Graal binding
```

## B. Callback

Richiedono:

- trampoline;
- lifetime;
- thread attach/enter;
- callback dispatch.

## C. Oggetti ObjC / Java framework

Richiedono wrapper/handle appropriati.

## D. Runtime-specific Kotlin/Native behavior

Può richiedere una piccola riscrittura.

## E. Memory ownership

ARC, native reference, pinning e GC Graal non sono lo stesso sistema.

Serve quindi un adapter esplicito per lifetime.

---

# 8. Strategia: backend Graal dedicato

Invece di cercare di far credere a Compose di essere “desktop JVM” o “Kotlin/Native iOS”, conviene introdurre un backend esplicito:

```text
compose-graal-platform
```

con un piccolo HAL.

Esempio:

```text
PlatformWindow
PlatformSurface
PlatformInput
PlatformClipboard
PlatformTextInput
PlatformAccessibility
PlatformFrameClock
PlatformCursor
PlatformMonitor
PlatformLifecycle
PlatformNativeHandle
```

Le implementazioni diventano:

```text
graal-windows
graal-macos
graal-linux
graal-android
graal-ios
```

Il codice Compose sopra questo livello rimane comune.

---

# 9. Obiettivo di uniformità

Idealmente:

```text
90%+ common Kotlin code
```

e soltanto il bordo finale cambia per piattaforma.

Non fissiamo oggi una percentuale reale.

Va misurata direttamente sul repository Compose/Skiko attuale.

L'obiettivo tecnico è evitare fork profondi.

---

# 10. Skia / Skiko

Skia rimane nativa.

Non vogliamo:

- riscrivere Skia;
- portare Skia in Java/Kotlin;
- aggiungere un livello di serializzazione.

Il modello rimane sostanzialmente quello già usato da Compose Desktop:

```text
Kotlin UI code
   ↓
Skiko-like adapter
   ↓
native Skia
   ↓
GPU / OS
```

Per il nostro backend:

```text
Kotlin UI code
   ↓
Graal Skia bindings
   ↓
native Skia
```

Quindi la pipeline grafica calda non introduce necessariamente un nuovo livello concettuale rispetto all'architettura corrente.

---

# 11. FFI Graal vs Kotlin/Native native call

Questa è una delle questioni prestazionali principali.

L'obiettivo è che una chiamata:

```text
Kotlin → native
```

dentro Native Image sia il più possibile vicina a:

```text
compiled native call
```

e non comporti:

- serializzazione;
- object graph copy;
- RPC;
- message passing;
- conversioni ridondanti.

Per call leaf e primitive il binding deve essere:

```text
Native Image compiled code
        ↓
C ABI / platform ABI
        ↓
native function
```

Il costo va misurato con benchmark.

Non assumiamo a priori né inferiorità né superiorità rispetto a Kotlin/Native.

---

# 12. Benchmark FFI obbligatorio

Prima di investire nel port completo:

```text
Benchmark A:
Kotlin/Native → C leaf call

Benchmark B:
Graal Native Image → C leaf call

Benchmark C:
JVM/JNI → C leaf call

Benchmark D:
callback C → Kotlin/Native

Benchmark E:
callback C → Native Image
```

Misurare:

- ns/call;
- throughput;
- callback latency;
- allocation;
- transition overhead;
- GC interaction.

---

# 13. Generazione automatica dei binding

Non vogliamo scrivere manualmente centinaia di binding.

La pipeline deve essere:

```text
upstream native definitions
        ↓
API extraction
        ↓
usage filtering
        ↓
binding model
        ↓
generated Graal bindings
```

---

# 14. Fonte delle signature

Le signature necessarie esistono già da qualche parte perché Kotlin/Native e i backend Compose devono poter chiamare le API native.

Possibili fonti:

- `.def` Kotlin/Native;
- cinterop metadata;
- headers C;
- Objective-C headers;
- Clang AST;
- Android SDK classes/jars;
- generated bindings già presenti upstream;
- Skiko interop declarations;
- native library headers.

Non dobbiamo rigenerare l'intero SDK.

---

# 15. Usage-driven binding generation

Principio:

> generare solo ciò che Compose/Skiko usa realmente.

Pipeline:

```text
Compose/Skiko source
      ↓
symbol usage analysis
      ↓
required native symbols
      ↓
lookup signature in upstream metadata
      ↓
generate only those bindings
```

Questo riduce:

- binary size;
- generated source;
- build time;
- maintenance surface;
- compatibility burden.

---

# 16. Generator architecture

Proposta:

```text
compose-graal-bindgen/
    parser/
    kotlin-native-metadata/
    clang-model/
    android-model/
    usage-index/
    graal-codegen/
    verification/
```

Output:

```text
generated/
  ios/
  macos/
  windows/
  linux/
  android/
```

Ogni binding generato deve essere riproducibile.

Niente editing manuale del codice generato.

---

# 17. Regola di manutenzione

Quando upstream Compose cambia:

```text
git update
   ↓
run usage analyzer
   ↓
detect new native symbols
   ↓
regenerate bindings
   ↓
compile tests
```

Il CI deve fallire se il codice upstream usa una nuova API non ancora mappata.

---

# 18. API surface reale

Non assumiamo ora un numero definitivo.

La conversazione ha ipotizzato:

- qualche centinaio di signature raw;
- decine di migliaia di LOC se tutto fosse fatto manualmente;

ma il target corretto è:

> **misurare l'API surface reale dal repository, non stimarla.**

La prima task di discovery deve produrre:

```text
platform
native symbols used
callbacks used
classes/protocols used
manual adapters required
generated adapters possible
```

---

# 19. Desktop

## Windows

Pipeline:

```text
Compose
  ↓
Graal platform adapter
  ↓
Win32
  ↓
Skia / GPU
```

Possibili aree:

- HWND;
- message loop;
- raw input;
- IME;
- clipboard;
- accessibility;
- DPI;
- window lifecycle.

## macOS

Pipeline:

```text
Compose
  ↓
Graal platform adapter
  ↓
Cocoa/AppKit
  ↓
Metal/Skia
```

Necessario binding Objective-C o shim C/ObjC.

## Linux

Pipeline:

```text
Compose
  ↓
Graal platform adapter
  ↓
X11 / Wayland
  ↓
Skia
```

La scelta X11/Wayland deve poter essere modulare.

---

# 20. Android

Android è un caso particolare.

Il framework UI Android è Java/Kotlin-shaped.

Questo può essere un vantaggio concettuale perché il backend Compose Android usa già API Java/Kotlin.

Tuttavia una Native Image non vive automaticamente dentro ART come normale bytecode JVM.

Serve quindi un bridge verso il framework Android.

Possibile modello:

```text
Android app shell
     ↓
JNI / generated Java bridge
     ↓
Graal Native Image library
```

oppure, dove tecnicamente disponibile:

```text
Native Image
     ↓
JNI env / Android framework bridge
```

Il livello deve essere generato il più possibile.

Non vogliamo serializzare strutture.

Usiamo:

- object/global refs;
- primitive calls;
- direct buffers;
- callbacks.

---

# 21. Android: perché è comunque promettente

La logica del backend Android esistente è già modellata in termini:

- View;
- Surface;
- Window;
- input;
- lifecycle;
- frame callbacks.

Quindi il port concettuale può essere molto diretto:

```text
existing Android backend API call
         ↓
generated JNI wrapper
```

Il lavoro difficile è costruire il bridge robusto, non reinventare la semantica Android.

---

# 22. iOS

iOS richiede:

```text
Native Image library/framework
        ↓
ObjC / C ABI bridge
        ↓
UIKit
        ↓
Metal / native services
```

Il codice Kotlin/Native platform-specific non può essere semplicemente compilato come JVM bytecode.

Ma può essere usato come **specifica quasi eseguibile del comportamento**.

Il port deve preservare:

- sequencing;
- state transitions;
- callback logic;
- window/view lifecycle;
- keyboard/text input;
- gestures;
- accessibility integration;
- frame callbacks.

Sostituendo:

```text
Kotlin/Native ObjC interop
```

con:

```text
Graal native binding / Objective-C shim
```

---

# 23. Objective-C strategy

Due possibili livelli.

## A. Binding ObjC diretto

Generare binding verso runtime Objective-C.

Pro:

- meno shim;
- più generico.

Contro:

- più complessità runtime;
- ABI e ownership più delicati.

## B. Thin ObjC/C shim

Esempio:

```text
Graal Kotlin
    ↓ C ABI
compose_graal_ios_shim.m
    ↓ ObjC
UIKit / Metal
```

Pro:

- ABI C semplice;
- ARC confinato nello shim;
- ownership più chiara;
- facile debug.

Per il primo port iOS, **thin ObjC/C shim** è probabilmente il percorso più prudente.

---

# 24. Main thread

Uno degli obiettivi importanti è:

> Compose UI e GraalPHP devono poter condividere lo stesso OS main thread quando semanticamente appropriato.

Quindi:

```text
OS main thread
    ├── platform UI loop
    ├── Compose frame/recomposition work
    └── GraalPHP cooperative scheduler
```

Il runtime PHP non deve bloccare il main thread con I/O o lavori CPU lunghi.

Le coroutine PHP devono:

```text
run
yield
return to UI loop
resume later
```

---

# 25. Un solo event loop logico

Non serve necessariamente che esista un singolo oggetto event-loop.

Serve che i due sottosistemi cooperino sullo stesso thread.

Possibile modello:

```text
Platform run loop owns main thread
        │
        ├── VSync → Compose frame
        │
        ├── native event → Compose input
        │
        └── GraalPHP runnable queue slices
```

Il reactor PHP può vivere:

- sullo stesso loop;
- oppure su backend OS separato con wakeup verso il main loop.

---

# 26. UI-thread affinity

Alcune API devono essere chiamate sul main thread.

Il backend deve codificare questo requisito.

Esempio:

```text
@MainThreadNative
UIKitBindings.createView(...)
```

Se chiamato da una coroutine PHP su worker thread:

```text
schedule to main
yield future
resume on completion
```

Nessuna serializzazione dell'object graph.

---

# 27. GraalPHP + Compose nello stesso runtime

Architettura finale desiderata:

```text
┌─────────────────────────────────────┐
│          Native Image Process       │
│                                     │
│   Kotlin / Compose                  │
│   ├─ Runtime                        │
│   ├─ UI                             │
│   ├─ Foundation                     │
│   └─ Material                       │
│                                     │
│   Truffle                           │
│   └─ GraalPHP                       │
│      ├─ PHP parser/runtime          │
│      ├─ COW / refs                  │
│      ├─ TrueAsync scheduler         │
│      └─ FFI                         │
│                                     │
│   Shared platform services          │
│   ├─ timers                         │
│   ├─ filesystem watcher             │
│   ├─ networking                     │
│   └─ native handles                 │
│                                     │
└───────────────┬─────────────────────┘
                │
         Platform bindings
                │
       OS / Skia / UIKit / Android
```

---

# 28. In-process direct API

Compose e GraalPHP non devono comunicare via JSON/protobuf.

Devono poter condividere:

- primitive;
- managed handles;
- immutable structures;
- direct buffers;
- callbacks/futures;
- host objects.

Esempio concettuale:

```text
PHP component
    ↓ direct polyglot/host call
Kotlin UI model
```

senza passare da C ABI se entrambi vivono già nella stessa Native Image.

---

# 29. Interoperabilità Kotlin ↔ PHP

Questo diventa uno dei vantaggi principali.

Possibile modello:

```text
Kotlin object
   ↔
Truffle InteropLibrary
   ↔
PHP object/proxy
```

Quindi una UI Compose può ricevere direttamente:

- model PHP;
- callback PHP;
- async future;
- observable state adapter.

Non è necessario serializzare.

---

# 30. Compose state ↔ PHP state

Possibile adapter:

```text
PHP observable/value
        ↓
Compose State<T>
```

e viceversa.

Da progettare con attenzione per:

- lifecycle;
- GC;
- invalidation;
- thread affinity;
- coroutine context.

Non deve essere implementato come polling.

---

# 31. Scheduler integration

Il runtime PHP TrueAsync può esporre:

```text
scheduleOnMain
scheduleOnWorker
awaitFrame
awaitUIEvent
```

Compose può esporre:

```text
frame clock
main dispatcher
lifecycle scope
```

L'integrazione deve evitare thread hopping inutile.

---

# 32. Coroutines Kotlin vs coroutine PHP

Non sono la stessa cosa.

Non tentiamo di fondere internamente gli stack/runtime.

Creiamo un bridge di scheduling.

Esempio:

```text
PHP Future
    ↔ host awaitable
    ↔ Kotlin suspend/Future adapter
```

Il bridge deve essere zero-copy per i risultati semplici.

---

# 33. Native handles

Per oggetti che appartengono al sistema operativo:

```text
UIView*
CAMetalLayer*
ANativeWindow*
HWND
NSWindow*
```

usare handle opachi.

Regole:

- ownership esplicita;
- retain/release esplicito quando necessario;
- no accidental GC ownership inversion;
- main-thread rule encoded;
- no implicit serialization.

---

# 34. Memory management

Abbiamo almeno tre domini:

```text
Graal managed heap
native heap
platform object runtime
```

Su iOS:

```text
ARC / ObjC objects
```

Su Android:

```text
ART Java objects referenced through JNI
```

Su desktop:

```text
native pointers
```

Serve un `NativeHandle` framework comune con backend platform-specifico.

---

# 35. Callback model

Tipi:

```text
native → Kotlin
native → PHP
Compose → PHP
PHP → Compose
```

Tutte devono avere:

- lifetime;
- thread affinity;
- cancellation;
- exception boundary;
- ownership.

Il runtime deve evitare callback native arbitrariamente eseguite su thread non attached.

---

# 36. Exception boundaries

Mai lasciare attraversare direttamente una exception managed attraverso un ABI nativo.

Modello:

```text
native callback
    ↓
enter runtime
    ↓
invoke managed
    ↓
catch
    ↓
convert to managed error/event
```

Il crash nativo rimane diverso da un'exception Kotlin/PHP.

---

# 37. Reflection e Native Image

Compose/Kotlin libraries eventualmente basate su reflection devono essere analizzate.

Native Image usa closed-world analysis.

Quindi:

- reflection statica conosciuta → configurabile/generabile;
- dynamic class loading arbitrario → non disponibile come su una JVM piena;
- serialization libraries reflection-heavy → da valutare;
- Compose compiler-generated paths dovrebbero essere preferiti.

Serve un report automatico durante i PoC.

---

# 38. Dynamic proxies

Se qualche parte usa:

- Java dynamic proxy;
- service loader;
- reflection lookup;
- resource scanning;

va resa Native Image friendly.

Possibili soluzioni:

- build-time generation;
- feature registration;
- metadata generation;
- sostituzioni mirate.

---

# 39. Resources

Compose resources devono essere incorporabili nella Native Image/app bundle.

Serve un abstraction layer:

```text
ResourceResolver
```

che su mobile sappia leggere dal bundle/APK e non assuma un filesystem desktop tradizionale.

---

# 40. Font

Font loading è una delle aree native da includere nel port.

Richiede:

- system font discovery;
- bundled fonts;
- fallback;
- shaping;
- locale;
- DPI.

Dove possibile si lascia a Skia/Skiko e si sostituisce soltanto il platform discovery layer.

---

# 41. Text input / IME

Questa è una delle aree più difficili del port.

Non è soltanto:

```text
keypress → character
```

Include:

- composition;
- selection;
- marked text;
- candidate window;
- RTL;
- dead keys;
- mobile keyboard;
- autocorrect;
- input connection;
- clipboard;
- accessibility interaction.

Conviene portare fedelmente la logica platform-specific esistente, non inventarne una nuova.

---

# 42. Accessibility

Altra area non banale.

Compose produce un semantics tree.

Il backend deve proiettarlo su:

- UIAccessibility;
- Android Accessibility;
- Windows accessibility;
- macOS accessibility;
- Linux accessibility stack.

Questo è codice platform-specifico, ma è già concettualmente separato dal core Compose.

---

# 43. Rendering architecture

Obiettivo:

```text
Compose scene
    ↓
Skia canvas/surface
    ↓
native GPU backend
```

Non definiamo qui quale backend GPU usare per default.

Si riusa quello più vicino all'upstream corrente.

---

# 44. VSync / frame clock

Il backend deve fornire:

```text
requestFrame(callback)
cancelFrame(...)
frameTimestamp
```

mappato alle primitive di frame scheduling della piattaforma.

Compose sopra continua a vedere il proprio frame clock.

---

# 45. Windowing

Desktop:

- multi-window;
- resize;
- DPI;
- fullscreen;
- minimize/maximize;
- decorations;
- pointer capture;
- drag & drop.

Mobile:

- app lifecycle;
- view controller/activity lifecycle;
- safe area;
- orientation;
- keyboard insets;
- touch.

La logica di porting è platform-boundary work, non modifica del core Compose.

---

# 46. Build model

Proposta repository:

```text
compose-graal/
  runtime-adapter/
  platform-api/
  bindings-generator/
  bindings/
    windows/
    macos/
    linux/
    android/
    ios/
  skia/
  integration/
  samples/
  benchmarks/
  tests/
```

---

# 47. Upstream strategy

Obiettivo massimo:

- non forkare Compose Runtime;
- non forkare Material;
- non forkare Foundation se evitabile;
- minimizzare il fork di Compose UI;
- creare nuovi platform actual/backend.

Più il delta verso upstream è piccolo, più il progetto è sostenibile.

---

# 48. Possibile utilizzo di `expect/actual`

Dove il codice upstream già usa `expect/actual`, aggiungere:

```text
actual Graal
```

è la soluzione ideale.

Dove invece il backend è codificato per target specifici:

- estrarre adapter;
- mantenere patch minime;
- proporre eventualmente abstraction upstreamable.

---

# 49. Non modificare Kotlin compiler

Idealmente non serve un nuovo backend Kotlin.

Usiamo:

```text
Kotlin/JVM frontend/backend
      ↓
JVM bytecode
      ↓
Graal Native Image
```

Quindi il progetto non è:

> “creare un nuovo compilatore Kotlin”.

È:

> “rendere Compose JVM code e i suoi platform adapter compatibili con Native Image e i target desiderati”.

Questo riduce enormemente lo scope.

---

# 50. Mobile caveat

Questo punto va marcato chiaramente.

Il percorso:

```text
Kotlin/JVM bytecode
→ Graal Native Image
→ Android/iOS
```

non equivale oggi a una configurazione Compose Multiplatform ufficiale già pronta.

Perciò:

> **desktop è molto più vicino ad un porting incrementale; mobile richiede una validazione toolchain end-to-end.**

Non dobbiamo basare il progetto su assunzioni non provate.

---

# 51. PoC ladder

## P0 — Desktop hello

- Kotlin/JVM;
- Native Image;
- trivial UI/native window.

## P1 — Truffle cohabitation

- Kotlin main;
- Truffle guest language;
- same process;
- same main thread.

## P2 — Skia

- create native surface;
- draw frame;
- resize;
- input.

## P3 — Compose Runtime

- recomposition;
- state;
- no full UI yet.

## P4 — Compose Scene

- render simple controls.

## P5 — Desktop backend

- input;
- IME;
- window lifecycle;
- clipboard.

## P6 — Android shell

- Native Image library embedded;
- Surface;
- frame callback;
- input;
- JNI bridge.

## P7 — iOS shell

- framework/static lib;
- UIView/Metal surface;
- display link;
- touch;
- keyboard.

## P8 — Full basic Compose sample

- button;
- text field;
- scroll;
- image;
- navigation.

## P9 — GraalPHP + Compose

- PHP callback updates Compose state;
- UI event calls PHP;
- async PHP task resumes UI;
- same process;
- no serialization.

---

# 52. Binding generator PoC

Prima ancora del port mobile completo:

1. scan del backend iOS upstream;
2. estrazione dei symbol reference;
3. risoluzione nelle definizioni Kotlin/Native/cinterop;
4. generazione wrapper Graal;
5. compilazione automatica;
6. confronto ABI.

Fare lo stesso per Android.

---

# 53. Metriche del discovery tool

Il tool deve produrre:

```text
iOS:
  native functions:
  ObjC classes:
  selectors:
  protocols:
  callbacks:
  structs:
  enums:
  manually handled:

Android:
  framework classes:
  methods:
  fields:
  callbacks:
  JNI signatures:
  manually handled:

Desktop:
  C symbols:
  callbacks:
  platform handles:
```

Questo ci darà finalmente numeri reali.

---

# 54. Quantità di codice

Non fissiamo oggi “20K LOC” come fatto.

Separiamo:

```text
generated LOC
manual platform adapter LOC
patched upstream LOC
```

Il KPI importante è soprattutto:

> **patched upstream LOC deve rimanere minimo.**

Generated LOC può anche essere grande se totalmente riproducibile.

---

# 55. Performance target

La pipeline ideale:

```text
Compose Kotlin compiled AOT
        ↓
direct managed call
        ↓
thin native binding
        ↓
Skia / OS
```

Nessun:

- JSON;
- protobuf;
- IPC;
- socket;
- serialization;
- isolate crossing.

---

# 56. Main-loop performance

PHP sul main thread deve essere cooperativo.

Regola:

```text
no unbounded PHP work on UI thread
```

Operazioni:

- I/O → yield;
- timer → yield;
- blocking FFI → worker;
- CPU-heavy work → optional worker;
- UI callback breve → main thread.

---

# 57. Shared thread scheduler

Possibile API interna:

```text
MainLoopScheduler
    post(Runnable)
    postAt(...)
    requestFrame(...)
    wake()
```

usata sia da:

- Compose integration;
- GraalPHP scheduler.

Non necessariamente sostituisce il loop OS: lo integra.

---

# 58. UI API per PHP

Una volta integrato Compose, PHP potrebbe vedere una API di alto livello.

Esempio concettuale:

```php
ui(function () {
    Column(
        Text("Hello"),
        Button("Click", fn() => ...)
    );
});
```

Oppure un layer dichiarativo dedicato.

Questo documento non definisce ancora l'API PHP.

Definisce solo l'infrastruttura necessaria affinché sia possibile senza bridge esterni.

---

# 59. Hot reload

L'integrazione è particolarmente interessante con GraalPHP.

Il watcher PHP già progettato può:

```text
file change
→ reparse PHP
→ new CodeGeneration
→ Compose state/component callback updated
→ next recomposition uses new code
```

Senza riavviare:

- UI runtime;
- Compose runtime;
- process.

Questo è un vantaggio rispetto a un puro AOT transpilation model.

---

# 60. Native Image size

Compose + Skia + Truffle + PHP non sarà un binario minimale.

Serve monitorare separatamente:

```text
Graal runtime
Truffle runtime compiler
Compose
Skia
platform bindings
PHP stdlib
native libs
```

Possibile packaging:

```text
app executable
+
platform-native bundled libraries
```

se il “single binary” puro non conviene per mobile.

---

# 61. Mobile packaging

## Android

Possibile:

```text
APK/AAB
 ├─ Java/Kotlin minimal shell
 └─ libgraalcomposephp.so
```

## iOS

Possibile:

```text
App
 └─ embedded static/dynamic framework
      └─ Graal Compose PHP runtime
```

Il formato esatto dipenderà dalla toolchain effettivamente supportata.

---

# 62. Kotlin standard library

La stdlib Kotlin/JVM necessaria viene analizzata da Native Image e inclusa.

Non serve Kotlin/Native runtime per il codice comune che compiliamo come JVM bytecode.

Questo è uno dei punti centrali della proposta.

---

# 63. Compose Compiler

Il codice Compose rimane elaborato dal Compose compiler plugin.

Il risultato JVM viene poi passato a Native Image.

Da verificare:

- generated classes;
- metadata;
- reflection assumptions;
- resources;
- static initialization.

Non dovrebbe essere necessario inventare un Compose compiler nuovo.

---

# 64. Coroutines Kotlin

Se usate dal framework:

- devono funzionare sotto Native Image;
- dispatcher platform-specifici vanno adattati;
- Main dispatcher deve puntare al nostro platform main loop;
- worker dispatcher deve usare executor compatibili.

Questo è un'area di integrazione importante ma metodica.

---

# 65. Main dispatcher

Obiettivo:

```text
Dispatchers.Main
    ↓
ComposeGraalMainDispatcher
    ↓
OS/UI main loop
```

Così:

- Compose;
- Kotlin coroutine;
- PHP UI interaction;

possono convergere sullo stesso main thread.

---

# 66. Thread pool

Worker pool condivisibile per:

- PHP blocking FFI;
- CPU tasks;
- image decode;
- platform background work;
- Kotlin coroutine background dispatcher.

Non è obbligatorio avere pool separati per runtime.

---

# 67. Avoid duplicate infrastructure

Uno dei vantaggi della soluzione è poter condividere:

```text
thread pools
timers
event wakeups
native memory arenas
logging
filesystem watcher
metrics
```

fra Compose host e GraalPHP.

---

# 68. Testing strategy

## Unit

- generated binding ABI;
- handle lifetime;
- callback trampoline;
- thread affinity;
- error conversion.

## Integration

- window;
- frame;
- input;
- text;
- accessibility;
- resize;
- suspend/resume;
- lifecycle.

## Cross-runtime

- PHP → Compose callback;
- Compose → PHP;
- async PHP → UI;
- thread worker → main UI;
- cancellation.

---

# 69. Golden rendering tests

Per assicurare che il backend Graal non cambi il rendering:

```text
upstream Compose backend
vs
Graal backend
```

stesso component tree → screenshot comparison con tolleranza definita.

---

# 70. CI matrix

```text
Linux x64
Linux arm64
Windows x64
Windows arm64 se supportato
macOS arm64
macOS x64 se necessario
Android arm64
Android x64 emulator
iOS arm64
iOS simulator target compatibile
```

Ogni build deve verificare almeno una sample UI.

---

# 71. Cosa NON vogliamo fare

- fork completo di Compose;
- nuovo Kotlin compiler backend;
- reimplementare Skia;
- reimplementare Material;
- serializzare UI tree tra runtime;
- mettere GraalPHP in un processo separato;
- generare binding per tutto UIKit/Android SDK;
- usare reflection dinamica ovunque;
- scrivere manualmente migliaia di signature native.

---

# 72. Rischi principali

## R1 — Mobile Native Image toolchain

Il più importante.

Va provato immediatamente.

## R2 — Native Image compatibility di Compose stack

Possibili punti:

- reflection;
- resources;
- dynamic initialization;
- service loading.

## R3 — iOS ObjC interop

Richiede un bridge robusto.

## R4 — Android framework access

Serve una strategia JNI/host concreta.

## R5 — Text input

Molto platform-specifico.

## R6 — Accessibility

Platform-specific e ampia.

## R7 — Skia integration

Va mantenuta ABI/performance-friendly.

## R8 — GC ↔ native lifetime

Particolarmente importante con callback e ObjC/JNI handles.

## R9 — Binary size

Compose + Skia + Truffle può essere consistente.

## R10 — Upstream churn

Il binding generator deve minimizzare il lavoro manuale.

---

# 73. Criterio go/no-go

La proposta diventa la strada principale se i PoC dimostrano:

1. Kotlin/JVM → Native Image funziona sui target richiesti;
2. Truffle può convivere nella stessa build;
3. Skia può essere chiamata con overhead accettabile;
4. almeno un backend mobile minimal funziona;
5. Compose Runtime/Scene funziona senza fork profondo;
6. il main loop può essere integrato correttamente;
7. binding generation copre la maggioranza del platform surface;
8. il delta manuale upstream è sostenibile.

---

# 74. Criterio di successo

Architettura finale:

```text
ONE PROCESS
ONE NATIVE IMAGE
ONE KOTLIN/COMPOSE RUNTIME
ONE TRUFFLE/PHP RUNTIME
NO SERIALIZATION BETWEEN UI AND PHP
NATIVE BINDINGS ONLY AT THE PLATFORM EDGE
```

e, quando la piattaforma lo permette:

```text
ONE OS MAIN THREAD
    ├─ Compose
    └─ cooperative GraalPHP
```

---

# 75. Confronto sintetico con Flutter

## Flutter

```text
Dart runtime
↕ FFI
Graal runtime
```

Vantaggi:

- mobile mature;
- tooling;
- UI stack pronto.

Svantaggi per il nostro obiettivo:

- due runtime;
- FFI sempre fra UI e PHP;
- lifecycle cross-runtime;
- due GC;
- duplicate async/runtime infrastructure.

## Compose + Graal

```text
Kotlin/Compose + Truffle/PHP
inside same Native Image
```

Vantaggi potenziali:

- direct managed calls;
- scheduler integrabile;
- main thread condivisibile;
- nessuna serializzazione UI↔PHP;
- più facile condividere servizi runtime.

Svantaggi:

- mobile backend non pronto ufficialmente;
- lavoro di porting;
- toolchain da dimostrare;
- maintenance cost.

---

# 76. Conclusione

La proposta non consiste nel “portare Kotlin/Native dentro Graal”.

Consiste in qualcosa di più semplice concettualmente:

> **usare il codice Compose/Kotlin comune come JVM bytecode, compilarlo con Graal Native Image e sostituire soltanto i confini nativi con un backend Graal generato e mantenibile.**

La maggior parte del valore di Compose rimane riutilizzata.

Il punto chiave è che:

```text
native-facing code
```

è già separato in larga misura per piattaforma.

Quindi possiamo usare il codice platform upstream come riferimento preciso e trasformare:

```text
Kotlin/Native interop / JNI / platform binding
```

in:

```text
Graal Native Image binding
```

senza reinventare:

- layout;
- recomposition;
- scene graph;
- Material;
- state;
- rendering model;
- event semantics.

La parte più promettente è automatizzare il binding a partire dalle **definizioni native già esistenti** e filtrare soltanto le API realmente usate da Compose/Skiko.

La parte da dimostrare subito è invece il supporto end-to-end della toolchain mobile.

---

# 77. Primo lavoro concreto consigliato

Creare un tool di discovery prima ancora del port:

```text
compose-native-surface-scan
```

che prende il repository upstream e produce:

```text
platform     used native symbols     source location
--------     -------------------     ---------------
iOS         ...                     ...
Android     ...                     ...
macOS       ...                     ...
Windows     ...                     ...
Linux       ...                     ...
```

più una classificazione:

```text
GENERATABLE
MANUAL_ADAPTER
CALLBACK
LIFETIME_SENSITIVE
MAIN_THREAD_ONLY
```

Da questo report possiamo sapere con precisione:

- quante API sono coinvolte;
- quante possono essere generate;
- quanti LOC manuali servono;
- quali file vanno realmente portati;
- quali sono i rischi tecnici veri.

Questo deve essere il **primo deliverable** della proposta.
