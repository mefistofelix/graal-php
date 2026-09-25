# Native Image JFR: traffic windows

Samples are observations, not exact CPU percentages. Windows Native Image uses a recurring-callback sampler. Windows include WebSocket connection setup; warmup, parked-idle and shutdown are excluded. Allocation bytes are sampled estimates. Inclusive method counts overlap. Leaf tables skip the recurring sampler and safepoint prologue.

## graalphp-profile/1/1000/echo/64

Window: 2026-09-25T11:01:15.683719400Z to 2026-09-25T11:01:25.810681200Z.

Owner samples: 24; sampled allocation weight: 6657759000 bytes.

### Sampled threads

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `main` | 24 | 42.11% |
| `JFR recorder` | 10 | 17.54% |
| `JFR Periodic Tasks` | 10 | 17.54% |
| `TruffleCompilerThread-65` | 5 | 8.77% |
| `TruffleCompilerThread-70` | 4 | 7.02% |
| `TruffleCompilerThread-60` | 1 | 1.75% |
| `TruffleCompilerThread-66` | 1 | 1.75% |
| `TruffleCompilerThread-62` | 1 | 1.75% |
| `TruffleCompilerThread-63` | 1 | 1.75% |

### Owner leaf methods

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `graalphp.runtime.TcpConnection.read` | 6 | 25.00% |
| `java.util.concurrent.CompletableFuture.uniWhenCompleteStage` | 2 | 8.33% |
| `java.util.HashMap.put` | 2 | 8.33% |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.release` | 2 | 8.33% |
| `graalphp.runtime.Scheduler.advance` | 2 | 8.33% |
| `com.oracle.svm.core.code.FactoryMethodHolder.Scheduler$WaitRegistration_YLo679OYNXFwuMdfcpcoiE` | 1 | 4.17% |
| `graalphp.runtime.Scheduler.runAction` | 1 | 4.17% |
| `graalphp.runtime.Scheduler$WaitRegistration.settle` | 1 | 4.17% |
| `java.util.concurrent.CompletableFuture.newIncompleteFuture` | 1 | 4.17% |
| `java.util.ImmutableCollections$MapN.probe` | 1 | 4.17% |
| `graalphp.runtime.Scheduler.enqueue` | 1 | 4.17% |
| `graalphp.runtime.Scheduler.resumeLater` | 1 | 4.17% |
| `java.util.ArrayList.iterator` | 1 | 4.17% |
| `java.util.concurrent.CompletableFuture.unipush` | 1 | 4.17% |
| `java.util.concurrent.locks.ReentrantLock$Sync.lock` | 1 | 4.17% |

### Owner GraalPHP methods (inclusive)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `graalphp.runtime.Scheduler.run` | 24 | 100.00% |
| `graalphp.runtime.Scheduler.pump` | 24 | 100.00% |
| `graalphp.truffle.PhpContext.execute` | 24 | 100.00% |
| `graalphp.truffle.PhpLanguage$1.execute` | 24 | 100.00% |
| `graalphp.runtime.Scheduler.runUntil` | 24 | 100.00% |
| `graalphp.runtime.Scheduler.runAction` | 23 | 95.83% |
| `graalphp.Main.main` | 18 | 75.00% |
| `graalphp.runtime.Scheduler$Resume.run` | 10 | 41.67% |
| `graalphp.runtime.Scheduler.advance` | 9 | 37.50% |
| `graalphp.truffle.PhpRootGen$CachedBytecodeNode.continueAt` | 7 | 29.17% |
| `graalphp.runtime.NetworkApi.function` | 7 | 29.17% |
| `graalphp.runtime.Operations.builtin` | 7 | 29.17% |
| `graalphp.truffle.PhpRootGen$Invoke_Node.execute` | 7 | 29.17% |
| `graalphp.truffle.PhpRootGen.continueAt` | 7 | 29.17% |
| `graalphp.truffle.PhpRoot$Invoke.run` | 7 | 29.17% |
| `graalphp.runtime.Operations.invoke` | 7 | 29.17% |
| `graalphp.truffle.PhpRootGen$CachedBytecodeNode.handleInvoke_` | 7 | 29.17% |
| `graalphp.runtime.TcpConnection.readHeaders` | 6 | 25.00% |
| `graalphp.runtime.Operations.invokeFunction` | 6 | 25.00% |
| `graalphp.truffle.PhpRootGen.execute` | 6 | 25.00% |
| `graalphp.runtime.Scheduler$$Lambda.0xfa45654a8c09e24fb93ac345752777fc0.run` | 6 | 25.00% |
| `graalphp.runtime.Scheduler.lambda$spawn$0` | 6 | 25.00% |
| `graalphp.runtime.Operations.executeChild` | 6 | 25.00% |
| `graalphp.runtime.TcpConnection.read` | 6 | 25.00% |
| `graalphp.runtime.Scheduler.listen` | 6 | 25.00% |

### Owner allocated classes (sampled byte weight)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `com.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo` | 2097039520 | 31.50% |
| `[J` | 1288946096 | 19.36% |
| `com.oracle.svm.core.code.FrameInfoQueryResult` | 1125836336 | 16.91% |
| `com.oracle.svm.core.meta.DirectSubstrateObjectConstant` | 393525032 | 5.91% |
| `[Lcom.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo;` | 325700440 | 4.89% |
| `[B` | 325165176 | 4.88% |
| `com.oracle.svm.core.code.CodeInfoDecoder$FrameInfoState` | 139731536 | 2.10% |
| `com.oracle.svm.core.monitor.JavaMonitor` | 137279992 | 2.06% |
| `java.util.ArrayList$Itr` | 90812560 | 1.36% |
| `[Ljava.lang.Object;` | 90343720 | 1.36% |
| `java.lang.String` | 63156416 | 0.95% |
| `com.oracle.truffle.nfi.backend.libffi.NativeArgumentBuffer$Pointer` | 57364200 | 0.86% |
| `java.util.concurrent.CompletableFuture$UniWhenComplete` | 56357496 | 0.85% |
| `java.util.concurrent.CompletableFuture` | 53761328 | 0.81% |
| `com.oracle.svm.core.code.CodeInfoQueryResult` | 39665960 | 0.60% |

## graalphp-profile/1/1000/relay/64

Window: 2026-09-25T11:01:25.813680100Z to 2026-09-25T11:01:35.913024300Z.

Owner samples: 197; sampled allocation weight: 3191939328 bytes.

### Sampled threads

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `main` | 197 | 93.36% |
| `JFR Periodic Tasks` | 10 | 4.74% |
| `JFR recorder` | 4 | 1.90% |

### Owner leaf methods

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `java.util.IdentityHashMap.clear` | 107 | 54.31% |
| `java.util.IdentityHashMap$IdentityHashMapIterator.hasNext` | 65 | 32.99% |
| `java.util.concurrent.LinkedBlockingQueue.poll` | 2 | 1.02% |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.release` | 2 | 1.02% |
| `graalphp.runtime.Scheduler.finished` | 2 | 1.02% |
| `graalphp.runtime.PhpValues$Heap.collectCycles` | 2 | 1.02% |
| `java.util.concurrent.CompletableFuture.uniWhenCompleteStage` | 1 | 0.51% |
| `java.util.concurrent.locks.ReentrantLock.unlock` | 1 | 0.51% |
| `com.oracle.truffle.runtime.OptimizedCallTarget.call` | 1 | 0.51% |
| `graalphp.runtime.Scheduler.enqueue` | 1 | 0.51% |
| `java.util.concurrent.locks.ReentrantLock.lock` | 1 | 0.51% |
| `graalphp.runtime.Scheduler.pump` | 1 | 0.51% |
| `java.util.HashMap.hash` | 1 | 0.51% |
| `graalphp.runtime.Scheduler.listen` | 1 | 0.51% |
| `graalphp.runtime.Scheduler$Resume.run` | 1 | 0.51% |
| `java.util.AbstractList$RandomAccessSpliterator.forEachRemaining` | 1 | 0.51% |
| `java.util.Arrays.copyOf` | 1 | 0.51% |
| `java.lang.Iterable.forEach` | 1 | 0.51% |
| `graalphp.runtime.Scheduler$WaitRegistration.<init>` | 1 | 0.51% |
| `graalphp.runtime.Scheduler.updateScope` | 1 | 0.51% |

### Owner GraalPHP methods (inclusive)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `graalphp.runtime.Scheduler.run` | 197 | 100.00% |
| `graalphp.runtime.Scheduler.pump` | 197 | 100.00% |
| `graalphp.truffle.PhpContext.execute` | 197 | 100.00% |
| `graalphp.truffle.PhpLanguage$1.execute` | 197 | 100.00% |
| `graalphp.runtime.Scheduler.runUntil` | 197 | 100.00% |
| `graalphp.Main.main` | 196 | 99.49% |
| `graalphp.runtime.Scheduler.runAction` | 190 | 96.45% |
| `graalphp.runtime.Scheduler$Resume.run` | 183 | 92.89% |
| `graalphp.runtime.Scheduler.advance` | 178 | 90.36% |
| `graalphp.runtime.Execution$Activation.close` | 175 | 88.83% |
| `graalphp.runtime.PhpValues$Heap.collectCycles` | 174 | 88.32% |
| `graalphp.runtime.PhpValues$Scope.close` | 174 | 88.32% |
| `graalphp.truffle.PhpRootGen$CachedBytecodeNode.continueAt` | 4 | 2.03% |
| `graalphp.truffle.PhpRootGen.continueAt` | 4 | 2.03% |
| `graalphp.runtime.Scheduler$$Lambda.0x41b6a95ad155edeb2ab571e0454413d00.run` | 3 | 1.52% |
| `graalphp.runtime.Scheduler.lambda$listen$1` | 3 | 1.52% |
| `graalphp.runtime.Scheduler$WaitRegistration.lambda$new$0` | 3 | 1.52% |
| `graalphp.truffle.PhpRootGen$ContinuationRootNodeImpl.execute` | 3 | 1.52% |
| `graalphp.truffle.PhpRootGen$Invoke_Node.execute` | 3 | 1.52% |
| `graalphp.runtime.Scheduler$WaitRegistration.settle` | 3 | 1.52% |
| `graalphp.truffle.PhpRoot$Invoke.run` | 3 | 1.52% |
| `graalphp.runtime.Scheduler$Listener.deliver` | 3 | 1.52% |
| `graalphp.runtime.Scheduler$WaitRegistration.<init>` | 3 | 1.52% |
| `graalphp.runtime.Scheduler$WaitRegistration$$Lambda.0x6f9930bcf69298ad9b6dff1c96b33f550.accept` | 3 | 1.52% |
| `graalphp.runtime.Scheduler.updateScope` | 3 | 1.52% |

### Owner allocated classes (sampled byte weight)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `com.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo` | 497286984 | 15.58% |
| `[J` | 452263072 | 14.17% |
| `com.oracle.svm.core.code.FrameInfoQueryResult` | 410681080 | 12.87% |
| `[B` | 337230288 | 10.57% |
| `[Ljava.lang.Object;` | 317053352 | 9.93% |
| `com.oracle.svm.core.meta.DirectSubstrateObjectConstant` | 244731944 | 7.67% |
| `[C` | 185928992 | 5.82% |
| `[Lcom.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo;` | 131512760 | 4.12% |
| `jdk.vm.ci.meta.PrimitiveConstant` | 40706032 | 1.28% |
| `graalphp.runtime.PhpError` | 38632240 | 1.21% |
| `java.util.ArrayList$Itr` | 34447536 | 1.08% |
| `java.util.concurrent.CompletableFuture$UniApply` | 30794712 | 0.96% |
| `java.util.concurrent.CompletableFuture$UniWhenComplete` | 21392048 | 0.67% |
| `[[Lcom.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo;` | 21389152 | 0.67% |
| `com.oracle.svm.core.code.CodeInfoDecoder$FrameInfoState` | 19309360 | 0.60% |

## graalphp-profile/1/10000/echo/64

Window: 2026-09-25T11:01:47.271899400Z to 2026-09-25T11:01:57.372677900Z.

Owner samples: 46; sampled allocation weight: 6089717408 bytes.

### Sampled threads

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `main` | 46 | 63.01% |
| `JFR Periodic Tasks` | 11 | 15.07% |
| `TruffleCompilerThread-70` | 4 | 5.48% |
| `JFR recorder` | 4 | 5.48% |
| `TruffleCompilerThread-62` | 4 | 5.48% |
| `TruffleCompilerThread-60` | 1 | 1.37% |
| `TruffleCompilerThread-64` | 1 | 1.37% |
| `TruffleCompilerThread-65` | 1 | 1.37% |
| `TruffleCompilerThread-63` | 1 | 1.37% |

### Owner leaf methods

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `graalphp.runtime.TcpConnection.read` | 7 | 15.22% |
| `graalphp.runtime.Scheduler.listen` | 6 | 13.04% |
| `java.util.concurrent.LinkedBlockingQueue.offer` | 3 | 6.52% |
| `com.oracle.truffle.runtime.OptimizedCallTarget.call` | 3 | 6.52% |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer.release` | 3 | 6.52% |
| `java.util.ArrayList$Itr.next` | 3 | 6.52% |
| `java.util.concurrent.CompletableFuture.newIncompleteFuture` | 2 | 4.35% |
| `java.util.concurrent.locks.ReentrantLock.lock` | 2 | 4.35% |
| `graalphp.runtime.Scheduler.pump` | 2 | 4.35% |
| `graalphp.runtime.Scheduler.runAction` | 1 | 2.17% |
| `java.util.HashSet.remove` | 1 | 2.17% |
| `com.oracle.truffle.api.interop.InteropLibraryGen$UncachedDispatch.fitsInLong` | 1 | 2.17% |
| `graalphp.runtime.Scheduler.enqueue` | 1 | 2.17% |
| `java.util.AbstractQueue.add` | 1 | 2.17% |
| `java.util.IdentityHashMap.clear` | 1 | 2.17% |
| `graalphp.runtime.Scheduler.resumeLater` | 1 | 2.17% |
| `java.util.HashMap.hash` | 1 | 2.17% |
| `graalphp.runtime.Scheduler$Resume.run` | 1 | 2.17% |
| `com.oracle.truffle.api.library.LibraryFactory.getUncached` | 1 | 2.17% |
| `java.util.concurrent.CompletableFuture.whenComplete` | 1 | 2.17% |

### Owner GraalPHP methods (inclusive)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `graalphp.runtime.Scheduler.run` | 46 | 100.00% |
| `graalphp.runtime.Scheduler.pump` | 46 | 100.00% |
| `graalphp.truffle.PhpContext.execute` | 46 | 100.00% |
| `graalphp.truffle.PhpLanguage$1.execute` | 46 | 100.00% |
| `graalphp.runtime.Scheduler.runUntil` | 46 | 100.00% |
| `graalphp.Main.main` | 39 | 84.78% |
| `graalphp.runtime.Scheduler.runAction` | 36 | 78.26% |
| `graalphp.runtime.Scheduler$Resume.run` | 14 | 30.43% |
| `graalphp.runtime.Scheduler$$Lambda.0x41b6a95ad155edeb2ab571e0454413d00.run` | 10 | 21.74% |
| `graalphp.runtime.Scheduler.lambda$listen$1` | 10 | 21.74% |
| `graalphp.runtime.Scheduler$WaitRegistration.lambda$new$0` | 10 | 21.74% |
| `graalphp.runtime.Scheduler$WaitRegistration$$Lambda.0x6f9930bcf69298ad9b6dff1c96b33f550.accept` | 10 | 21.74% |
| `graalphp.runtime.Scheduler$WaitRegistration.settle` | 10 | 21.74% |
| `graalphp.runtime.Scheduler$Listener.deliver` | 10 | 21.74% |
| `graalphp.runtime.Scheduler.advance` | 10 | 21.74% |
| `graalphp.runtime.Scheduler.listen` | 9 | 19.57% |
| `graalphp.runtime.Scheduler$WaitRegistration.<init>` | 9 | 19.57% |
| `graalphp.runtime.Scheduler.resumeLater` | 8 | 17.39% |
| `graalphp.runtime.TcpConnection.readHeaders` | 7 | 15.22% |
| `graalphp.runtime.Operations.invokeFunction` | 7 | 15.22% |
| `graalphp.truffle.PhpRootGen$CachedBytecodeNode.continueAt` | 7 | 15.22% |
| `graalphp.truffle.PhpRootGen.execute` | 7 | 15.22% |
| `graalphp.runtime.Scheduler$$Lambda.0xfa45654a8c09e24fb93ac345752777fc0.run` | 7 | 15.22% |
| `graalphp.runtime.NetworkApi.function` | 7 | 15.22% |
| `graalphp.runtime.Scheduler.lambda$spawn$0` | 7 | 15.22% |

### Owner allocated classes (sampled byte weight)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `com.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo` | 1644875776 | 27.01% |
| `com.oracle.svm.core.code.FrameInfoQueryResult` | 1085199296 | 17.82% |
| `[J` | 1069549336 | 17.56% |
| `com.oracle.svm.core.meta.DirectSubstrateObjectConstant` | 506261432 | 8.31% |
| `[Lcom.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo;` | 328316512 | 5.39% |
| `[B` | 219237424 | 3.60% |
| `[Ljava.lang.Object;` | 185306320 | 3.04% |
| `java.util.concurrent.CompletableFuture$UniWhenComplete` | 185280600 | 3.04% |
| `com.oracle.truffle.nfi.backend.libffi.NativeArgumentBuffer$Pointer` | 162255736 | 2.66% |
| `com.oracle.svm.core.code.CodeInfoDecoder$FrameInfoState` | 138146568 | 2.27% |
| `[[Lcom.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo;` | 120573936 | 1.98% |
| `graalphp.runtime.NativeAccess$Symbol` | 74117104 | 1.22% |
| `java.util.concurrent.CompletableFuture` | 38105048 | 0.63% |
| `com.oracle.svm.core.code.CodeInfoQueryResult` | 36017104 | 0.59% |
| `java.util.HashMap$Node` | 26100816 | 0.43% |

## graalphp-profile/1/10000/relay/64

Window: 2026-09-25T11:01:57.373678700Z to 2026-09-25T11:02:07.516514400Z.

Owner samples: 263; sampled allocation weight: 1370638920 bytes.

### Sampled threads

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `main` | 263 | 95.64% |
| `JFR Periodic Tasks` | 10 | 3.64% |
| `TruffleCompilerThread-70` | 1 | 0.36% |
| `JFR recorder` | 1 | 0.36% |

### Owner leaf methods

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `java.util.IdentityHashMap.clear` | 163 | 61.98% |
| `java.util.IdentityHashMap$IdentityHashMapIterator.hasNext` | 89 | 33.84% |
| `java.util.concurrent.CompletableFuture.uniWhenCompleteStage` | 2 | 0.76% |
| `graalphp.runtime.Scheduler$Resume.run` | 2 | 0.76% |
| `java.util.concurrent.LinkedBlockingQueue.offer` | 1 | 0.38% |
| `com.oracle.truffle.api.interop.InteropLibraryGen$UncachedDispatch.isNull` | 1 | 0.38% |
| `graalphp.runtime.Scheduler$WaitRegistration.<init>` | 1 | 0.38% |
| `graalphp.runtime.TcpConnection.read` | 1 | 0.38% |
| `graalphp.runtime.PhpValues$Heap.collectCycles` | 1 | 0.38% |
| `java.util.HashMap.newNode` | 1 | 0.38% |
| `graalphp.runtime.Scheduler.listen` | 1 | 0.38% |

### Owner GraalPHP methods (inclusive)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `graalphp.runtime.Scheduler.run` | 263 | 100.00% |
| `graalphp.runtime.Scheduler.pump` | 263 | 100.00% |
| `graalphp.truffle.PhpContext.execute` | 263 | 100.00% |
| `graalphp.truffle.PhpLanguage$1.execute` | 263 | 100.00% |
| `graalphp.runtime.Scheduler.runUntil` | 263 | 100.00% |
| `graalphp.Main.main` | 262 | 99.62% |
| `graalphp.runtime.Scheduler.runAction` | 262 | 99.62% |
| `graalphp.runtime.Scheduler$Resume.run` | 260 | 98.86% |
| `graalphp.runtime.Scheduler.advance` | 258 | 98.10% |
| `graalphp.runtime.PhpValues$Heap.collectCycles` | 253 | 96.20% |
| `graalphp.runtime.PhpValues$Scope.close` | 253 | 96.20% |
| `graalphp.runtime.Execution$Activation.close` | 253 | 96.20% |
| `graalphp.runtime.Scheduler$WaitRegistration.<init>` | 5 | 1.90% |
| `graalphp.runtime.Scheduler.listen` | 4 | 1.52% |
| `graalphp.runtime.TcpConnection.readHeaders` | 1 | 0.38% |
| `graalphp.runtime.LibuvReactor.step` | 1 | 0.38% |
| `graalphp.runtime.Operations.invokeFunction` | 1 | 0.38% |
| `graalphp.truffle.PhpRootGen$CachedBytecodeNode.continueAt` | 1 | 0.38% |
| `graalphp.truffle.PhpRootGen.execute` | 1 | 0.38% |
| `graalphp.runtime.Scheduler.resumeLater` | 1 | 0.38% |
| `graalphp.runtime.Scheduler$$Lambda.0xfa45654a8c09e24fb93ac345752777fc0.run` | 1 | 0.38% |
| `graalphp.runtime.NetworkApi.function` | 1 | 0.38% |
| `graalphp.runtime.NativeAccess.call` | 1 | 0.38% |
| `graalphp.runtime.NativeAccess.normalize` | 1 | 0.38% |
| `graalphp.runtime.Scheduler$$Lambda.0x41b6a95ad155edeb2ab571e0454413d00.run` | 1 | 0.38% |

### Owner allocated classes (sampled byte weight)

| Name | Count / weight | Share |
| --- | ---: | ---: |
| `com.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo` | 280712736 | 20.48% |
| `com.oracle.svm.core.code.FrameInfoQueryResult` | 189400736 | 13.82% |
| `[J` | 161434400 | 11.78% |
| `[B` | 134727392 | 9.83% |
| `[Ljava.lang.Object;` | 105942808 | 7.73% |
| `[C` | 82554328 | 6.02% |
| `com.oracle.svm.core.meta.DirectSubstrateObjectConstant` | 75128592 | 5.48% |
| `[Lcom.oracle.svm.core.code.FrameInfoQueryResult$ValueInfo;` | 51662496 | 3.77% |
| `java.util.stream.ReferencePipeline$3` | 19300472 | 1.41% |
| `java.util.ArrayList$Itr` | 13570304 | 0.99% |
| `com.oracle.svm.core.code.CodeInfoQueryResult` | 12524208 | 0.91% |
| `com.oracle.svm.core.code.CodeInfoDecoder$FrameInfoState` | 10949232 | 0.80% |
| `java.util.concurrent.CompletableFuture$UniWhenComplete` | 9912448 | 0.72% |
| `java.util.concurrent.CompletableFuture` | 8869296 | 0.65% |
| `java.util.stream.ReferencePipeline$Head` | 7829280 | 0.57% |

