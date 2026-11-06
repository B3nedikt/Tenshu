package org.tenshu.core.common.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.fold
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * ⚠⚠ A stream whose **start throws** — not a start that answers a `Failure`: the call itself raises —
 * is a failed subscribe like any other (`ios-shared-store-sqlite.md` B.16.15, follow-up).
 *
 * The production shape, driven here the same way: the first in-process call mints the session, and
 * the session's initialisation throws (on iOS, `initFileService`'s write over the not-ready stand-in
 * or a fenced store). That throw comes out of the executor before `LocalTaskExecutor`'s catch-all, so
 * no `TResult` exists. `DefaultInteractorProvider` had already registered the stream's entry under
 * the callstack's deterministic id, and left it with no initial result: the throw escaped into the
 * collector (a K/N abort on a handler-less scope), and every later subscribe of the same chain found
 * the entry, sent no start, and waited for ever.
 */
class StreamStartThrowsTest {

    /** A proxy whose first session mint throws once its initialisation has run. */
    private fun proxy(scope: CoroutineScope): InProcessProxy {
        val throwOnce = MutableStateFlow(true)
        return InProcessProxy(
            clientId = "stream-start-throws-client",
            serviceScope = scope,
            syncCryptoManager = BootstrapSyncCryptoManager(),
            operationImplementationFactory = OperationImplementationFactory { id -> implementationFactory(id) },
            schemaRegistryFactory = { tContextProvider ->
                createDefaultSchemaRegistry(tContextProvider, extraConfigs = listOf(syntheticServiceModule))
            },
            initialization = {
                initSyntheticService()
                if (throwOnce.compareAndSet(expect = true, update = false)) {
                    throw IllegalStateException("made up: the shared store is not ready")
                }
            },
        )
    }

    @Test
    fun aStartThatThrowsIsReportedAsAFailureAndTheStreamRetriesIntoALiveRead() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        val results = MutableStateFlow<List<String>>(emptyList())
        val failures = MutableStateFlow(0)
        // ⚠ In backgroundScope: an exception escaping the collector fails this test.
        backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items.flow().collect { result ->
                result.fold(
                    onSuccess = { results.value = it.toList().map { d -> d.name.get() } },
                    onFailure = { failures.value++ },
                )
            }
        }

        await(failures) { it == 1 }
        // The session exists now (the mint published it before its initialisation threw).
        proxy.executionContext.saveData(SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1))
        await(results) { it == listOf("alpha") }
        proxy.executionContext.saveData(SyntheticTestData.Factory.create(proxy.executionContext.context, "beta", 2))
        await(results) { it.toSet() == setOf("alpha", "beta") }
        assertEquals(1, failures.value, "one failure run, one report")
    }

    @Test
    fun aResubscribeOfTheSameChainAfterAThrowingStartDoesNotHang() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        withContext(Dispatchers.Default) {
            val first = withTimeout(WAIT_MS) { proxy.client.syntheticService.items.flow().first() }
            assertIs<TResult.Failure<*>>(first, "the throwing start is a Failure, not an exception: $first")
            // The same chain, so the same stream id as the entry the throw left behind.
            val second = withTimeout(WAIT_MS) { proxy.client.syntheticService.items.flow().first() }
            assertIs<TResult.Success<*>>(second, "the re-subscribe is a fresh start: $second")
        }
    }

    /**
     * ⚠⚠ A start **cancelled** mid-flight (the user navigated away and back) — the one branch of the
     * fix that runs over Binder, UDS and EPC, whose executor folds every other throw into a `TResult`.
     * The consumer takes the interactor before its try/finally, so it never ends what it never
     * registered: the entry must be ended (an `EndStream` sent, so the proxy-side listener stops) and
     * dropped, or the next subscriber of the chain reuses it and is handed the cancelled start's
     * Failure.
     */
    @Test
    fun aStartCancelledMidFlightIsEndedAndTheNextSubscriberGetsALiveSuccess() = runTest(timeout = TIMEOUT) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holdNext = MutableStateFlow(true)
        val ends = MutableStateFlow(0)
        val proxy = InProcessProxy(
            clientId = "stream-start-cancelled-client",
            serviceScope = backgroundScope,
            syncCryptoManager = BootstrapSyncCryptoManager(),
            operationImplementationFactory = OperationImplementationFactory { id ->
                when (id) {
                    Stream.ID -> HeldStart(holdNext, started, release, implementationFactory(id) as AsyncOperationImplementation)
                    EndStream.ID -> CountingEnd(ends, implementationFactory(id) as SyncOperationImplementation)
                    else -> implementationFactory(id)
                }
            },
            schemaRegistryFactory = { tContextProvider ->
                createDefaultSchemaRegistry(tContextProvider, extraConfigs = listOf(syntheticServiceModule))
            },
            initialization = { initSyntheticService() },
        )
        proxy.executionContext.saveData(SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1))

        withContext(Dispatchers.Default) {
            val first = launch { proxy.client.syntheticService.items.flow().collect { } }
            withTimeout(WAIT_MS) { started.await() }
            first.cancel()
            release.complete(Unit)
            first.join()

            val next = withTimeout(WAIT_MS) { proxy.client.syntheticService.items.flow().first() }
            assertIs<TResult.Success<*>>(next, "the next subscriber was handed the cancelled start's seed: $next")
            // ⚠ And the abandoned entry was ended, not merely replaced: its proxy-side listener stops.
            // (The second subscriber's own end follows from `first()`, so at least two.)
            await(ends) { it >= 2 }
        }
    }

    /** The real start, except that the first one waits for [release] (and is cancelled there). */
    private class HeldStart(
        private val holdNext: MutableStateFlow<Boolean>,
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
        private val real: AsyncOperationImplementation,
    ) : AsyncOperationImplementation {
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            if (holdNext.compareAndSet(expect = true, update = false)) {
                started.complete(Unit)
                release.await()
            }
            return real.execute(executionContext, operationContext, implementationFactory, caller, params)
        }
    }

    private class CountingEnd(
        private val ends: MutableStateFlow<Int>,
        private val real: SyncOperationImplementation,
    ) : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            ends.update { it + 1 }
            return real.execute(executionContext, operationContext, implementationFactory, caller, params)
        }
    }

    private suspend fun <T> await(state: MutableStateFlow<T>, predicate: (T) -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (!predicate(state.value)) delay(POLL_MS) }
        }

    private companion object {
        val TIMEOUT = 60.seconds
        const val WAIT_MS = 20_000L
        const val POLL_MS = 10L
    }
}
