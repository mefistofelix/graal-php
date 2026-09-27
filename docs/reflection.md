# Reflection e semantica runtime degli attributi

Blocco funzionale del 26 settembre 2026, successivo a
[unpacking e spread](unpacking.md). I metadati degli attributi erano già
conservati dall'IR; questo blocco li rende osservabili attraverso oggetti
Reflection e completa il percorso lazy di filtro, validazione e istanziazione.

## Oggetti Reflection implementati

Sono modellati `Reflector`, `ReflectionClass`, `ReflectionObject`,
`ReflectionFunctionAbstract`, `ReflectionFunction`, `ReflectionMethod`,
`ReflectionProperty`, `ReflectionClassConstant`, `ReflectionParameter` e
`ReflectionAttribute`. Gli oggetti sono viste del metadato immutabile della
generazione corrente; non duplicano definizioni PHP in un registro globale.

Il nucleo coperto comprende nomi e dichiaranti, namespace, categoria/finalità
delle classi, padre/interfacce, query di metodi/proprietà/costanti, parametri,
default, variadici, informazioni sorgente e attributi. ReflectionClass e le
forme che accettano un nome di classe usano il normale autoload sospendibile.

## Attributi

`getAttributes()` senza filtro e il filtro per nome esatto non richiedono che
la classe attributo esista. Il metadato sconosciuto resta quindi lazy.
`ReflectionAttribute::IS_INSTANCEOF` carica invece le classi necessarie,
perché deve verificarne l'ereditarietà.

`getArguments()` valuta le espressioni costanti nel contesto dichiarativo e
preserva chiavi nominate e ordine. `newInstance()` carica la classe attributo,
verifica che sia marcata con `#[Attribute]`, controlla target e ripetibilità e
invoca il costruttore con gli argomenti originali. Autoload e costruttore possono
sospendersi: il percorso usa le stesse continuation del guest e non un loop o
un worker speciale.

La validazione di target/ripetibilità è intenzionalmente lazy, come PHP: una
dichiarazione con attributo sconosciuto, non-attribute, target errato o duplicato
può essere riflessa; l'errore emerge quando si richiede `newInstance()`.
Sono supportiti i flag di `Attribute` e il filtro `IS_INSTANCEOF`, incluso
l'uso combinato con operatori bitwise nelle espressioni PHP.

## Verifica

`tests/graalphp/ReflectionTest.java` contiene 25 programmi identici per
riferimento e target. Coprono classi/oggetti, funzioni/closure, metodi,
proprietà, costanti, parametri, alias/namespace, filtri esatti e instanceof,
autoload, lazy validation, named arguments, espressioni costanti e due casi
TrueAsync con sospensione.

Passano su Windows e Linux in JVM e Native Image; passano inoltre sullo stesso
Native Image con `--interpreter`: 150 esecuzioni complessive. Le regressioni
`verify` restano verdi: Windows 91 scenari integrati, Linux 85, 53 scenari
valori, 256 grafi/1.280 fasi collector, 56 controlli field-iteration, 52 controlli
metadata e 82/82 TrueAsync sulla verifica Windows.

Esempio:

```text
build\graalphp.exe examples\reflection.php
Handler:demo:value
```

## Limiti

Non è ancora l'intera estensione Reflection di PHP. Restano in particolare
ReflectionType/NamedType/UnionType/IntersectionType, ReflectionEnum e casi enum,
API complete di invoke/newInstanceArgs/getValue/setValue, filtri completi dei
modifier, catalogo completo dei tipi interni e diversi dettagli diagnostici.
Gli attributi builtin ulteriori e la verifica di ogni combinazione di target
restano da estendere insieme al catalogo linguistico.

Il blocco non chiude LANG-01. I generatori sono stati completati nel
[blocco successivo](generators.md); Fiber è stato escluso dagli obiettivi di
compatibilità, mentre lifetime, loader SPL predefinito/include_path e typing dei
builtin rimangono tracciati nel TODO. Nessuna misura prestazionale è stata
eseguita o reinterpretata.
