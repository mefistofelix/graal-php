# Native Image JFR: traffic windows

Samples are observations, not exact CPU percentages. Windows Native Image uses a recurring-callback sampler. Traffic windows exclude warmup, parked-idle and shutdown. Allocation bytes are sampled estimates. Inclusive method counts overlap. Leaf tables skip the recurring sampler and safepoint prologue.

## graalphp-profile/1/1000/curl/64

Window: 2026-09-25T14:26:35.597566600Z to 2026-09-25T14:26:54.931233200Z.

Owner samples: 172; sampled allocation weight: 4782439264 bytes.

Java GC pauses: 191; total milliseconds: 281.8037.

### Sampled threads

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `main` | 172 | 77.48% |
| `JFR Periodic Tasks` | 19 | 8.56% |
| `JFR recorder` | 10 | 4.50% |
| `TruffleCompilerThread-69` | 5 | 2.25% |
| `TruffleCompilerThread-68` | 4 | 1.80% |
| `TruffleCompilerThread-82` | 3 | 1.35% |
| `TruffleCompilerThread-80` | 2 | 0.90% |
| `TruffleCompilerThread-60` | 1 | 0.45% |
| `TruffleCompilerThread-72` | 1 | 0.45% |
| `TruffleCompilerThread-61` | 1 | 0.45% |

### Owner leaf methods

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `java.util.IdentityHashMap$IdentityHashMapIterator.hasNext` | 54 | 31.40% |
| `graalphp.runtime.PhpValues$Heap.collectCycles` | 23 | 13.37% |
| `java.util.IdentityHashMap.forEach` | 12 | 6.98% |
| `java.util.ArrayList.iterator` | 5 | 2.91% |
| `graalphp.runtime.Scheduler.pump` | 5 | 2.91% |
| `graalphp.runtime.Execution$Activation.close` | 4 | 2.33% |
| `java.util.IdentityHashMap.put` | 3 | 1.74% |
| `java.lang.Iterable.forEach` | 3 | 1.74% |
| `com.oracle.truffle.api.library.LibraryFactory.getUncached` | 3 | 1.74% |
| `java.util.Map.putIfAbsent` | 3 | 1.74% |
| `graalphp.runtime.Scheduler$Resume.run` | 3 | 1.74% |
| `java.util.HashSet.remove` | 2 | 1.16% |
| `com.oracle.truffle.api.interop.InteropLibraryGen$UncachedDispatch.fitsInLong` | 2 | 1.16% |
| `java.util.HashMap.hash` | 2 | 1.16% |
| `java.util.ArrayDeque.copyElements` | 2 | 1.16% |
| `java.util.ArrayList$Itr.next` | 2 | 1.16% |
| `java.util.ArrayDeque.poll` | 2 | 1.16% |
| `java.util.ArrayList.clear` | 2 | 1.16% |
| `java.util.ImmutableCollections$AbstractImmutableList.iterator` | 2 | 1.16% |
| `graalphp.runtime.Scheduler.resumeLater` | 2 | 1.16% |

### Owner GraalPHP methods (inclusive)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `graalphp.runtime.Scheduler.run` | 172 | 100.00% |
| `graalphp.Main.main` | 172 | 100.00% |
| `graalphp.runtime.Scheduler.pump` | 172 | 100.00% |
| `graalphp.truffle.PhpContext.execute` | 172 | 100.00% |
| `graalphp.truffle.PhpLanguage$1.execute` | 172 | 100.00% |
| `graalphp.runtime.Scheduler.runUntil` | 172 | 100.00% |
| `graalphp.runtime.Scheduler.runAction` | 153 | 88.95% |
| `graalphp.runtime.Scheduler$Resume.run` | 144 | 83.72% |
| `graalphp.runtime.Scheduler.advance` | 140 | 81.40% |
| `graalphp.runtime.Execution$Activation.close` | 125 | 72.67% |
| `graalphp.runtime.PhpValues$Scope.close` | 116 | 67.44% |
| `graalphp.runtime.PhpValues$Heap.collectCycles` | 110 | 63.95% |
| `graalphp.runtime.Scheduler$$Lambda.0x41b6a95ad155edeb2ab571e0454413d00.run` | 8 | 4.65% |
| `graalphp.runtime.Scheduler.lambda$listen$1` | 8 | 4.65% |
| `graalphp.runtime.Scheduler$Listener.deliver` | 8 | 4.65% |
| `graalphp.runtime.Scheduler$WaitRegistration.<init>` | 8 | 4.65% |
| `graalphp.runtime.Scheduler.listen` | 7 | 4.07% |
| `graalphp.runtime.Execution$Request.pollIO` | 7 | 4.07% |
| `graalphp.runtime.LibuvReactor.step` | 7 | 4.07% |
| `graalphp.runtime.NativeAccess.call` | 7 | 4.07% |
| `graalphp.runtime.NativeAccess.normalize` | 4 | 2.33% |
| `graalphp.runtime.Scheduler$WaitRegistration.lambda$new$0` | 4 | 2.33% |
| `graalphp.runtime.Scheduler$WaitRegistration.settle` | 4 | 2.33% |
| `graalphp.runtime.Scheduler$Listener.close` | 4 | 2.33% |
| `graalphp.runtime.Scheduler$WaitRegistration$$Lambda.0x6f9930bcf69298ad9b6dff1c96b33f550.accept` | 4 | 2.33% |

### Owner allocated classes (sampled byte weight)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `[C` | 1352345512 | 28.28% |
| `[B` | 1244401632 | 26.02% |
| `[Ljava.lang.Object;` | 961443088 | 20.10% |
| `java.util.IdentityHashMap` | 276150360 | 5.77% |
| `sun.nio.cs.UTF_8$Decoder` | 150666376 | 3.15% |
| `java.util.LinkedHashMap$LinkedValueIterator` | 64549872 | 1.35% |
| `java.util.stream.ReferencePipeline$Head` | 59626192 | 1.25% |
| `java.util.IdentityHashMap$KeyIterator` | 52735536 | 1.10% |
| `java.util.ImmutableCollections$ListItr` | 52366280 | 1.09% |
| `java.util.Collections$SetFromMap` | 48135424 | 1.01% |
| `java.util.Spliterators$ArraySpliterator` | 42849656 | 0.90% |
| `com.oracle.svm.core.heap.AbstractPinnedObjectSupport$PinnedObjectImpl` | 36348992 | 0.76% |
| `java.util.stream.ReferencePipeline$3` | 35315792 | 0.74% |
| `java.util.ArrayList$Itr` | 33807512 | 0.71% |
| `[Lgraalphp.runtime.Execution$Argument;` | 32785560 | 0.69% |

