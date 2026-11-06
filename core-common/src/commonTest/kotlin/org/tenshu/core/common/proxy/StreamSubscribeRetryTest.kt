package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.changes
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.fold
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A *failed* subscribe must not be a one-way door
 * (`docs/proposals/core-client-resubscription.md` — the half R1 does not cover).
 *
 * `resubscribeAll` deliberately skips streams whose original subscribe failed, and it has to: by
 * then the interactor is gone. `setInitialResult(Failure)` delivers `start(error)` **and**
 * `end(error)`, so the consumer's `finally` calls `endStream`, which drops the provider's entry.
 * Both ends of the subscription are torn down, so nothing the transport does afterwards can bring
 * the read back — before this, only restarting the *client process* did. Observed as a Drive file
 * listing that stayed empty after Control restarted, long after Drive's session had been
 * re-admitted.
 *
 * Recovery therefore has to build a *fresh* subscription, which is why it lives in [changes] and
 * [flow] themselves rather than in the provider. Both are covered here: the invalidation stream
 * failed *silently*, the state stream at least reported it, and neither could come back.
 */
class StreamSubscribeRetryTest {

    @Test
    fun changes_recoversAfterTheSubscribeFailed() = runTest(timeout = TIMEOUT) {
        // Stands in for "the proxy is down, or refused this session": the subscribe cannot be
        // dispatched at all, so it comes back as a failure rather than an empty stream.
        val subscribeFails = MutableStateFlow(true)
        val proxy = proxy(backgroundScope, subscribeFails)

        val ticks = MutableStateFlow(0)
        backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items.changes().collect { ticks.value++ }
        }

        // Give the first attempt time to fail and the retry loop time to spin at least once.
        withContext(Dispatchers.Default) { delay(1_500) }
        assertEquals(0, ticks.value, "a failed subscribe must not fabricate change signals")

        subscribeFails.value = false

        // The retry that lands re-reads on its own — the consumer missed arbitrary changes while
        // the read was down, so it cannot assume the next write will wake it.
        await(ticks) { it >= 1 }

        // ...and the recovered subscription is live, not a one-shot.
        val before = ticks.value
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1)
        )
        await(ticks) { it > before }
    }

    @Test
    fun flow_recoversAfterTheSubscribeFailed() = runTest(timeout = TIMEOUT) {
        val subscribeFails = MutableStateFlow(true)
        val proxy = proxy(backgroundScope, subscribeFails)
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1)
        )

        val results = MutableStateFlow<List<String>>(emptyList())
        val failures = MutableStateFlow(0)
        backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items.flow().collect { result ->
                result.fold(
                    onSuccess = { results.value = it.toList().map { d -> d.name.get() } },
                    onFailure = { failures.value++ },
                )
            }
        }

        // The failure still reaches the consumer — a state stream that went quiet instead would be
        // strictly worse than the terminal behaviour it replaces.
        await(failures) { it == 1 }

        subscribeFails.value = false
        await(results) { it == listOf("alpha") }

        // ...and it is reported once per failure *run*, not once per attempt: several retries have
        // been and gone by now.
        assertEquals(1, failures.value, "a retry storm must not re-notify the consumer each time")

        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "beta", 2)
        )
        await(results) { it.toSet() == setOf("alpha", "beta") }
    }

    /**
     * ⚠⚠ A subscribe that lands and is then ended with an error before any element arrives — the
     * shape an emission too large for the transport takes (`core-client-resubscription.md` §4.8:
     * Control ends the stream with `InternalError` in place of the snapshot it could not send) — is
     * ONE failure run: it backs off (1, 2, 4 s …) and reports once. Resetting on the landed
     * subscribe put it on a 1 s loop with a fresh Failure each second, for as long as it was
     * collected.
     */
    @Test
    fun flow_backsOffWhenEverySubscribeEndsBeforeItsFirstElement() = runTest(timeout = TIMEOUT) {
        val refuse = MutableStateFlow(true)
        val subscribes = MutableStateFlow(0)
        val proxy = InProcessProxy(
            clientId = "subscribe-refused-client",
            serviceScope = backgroundScope,
            syncCryptoManager = BootstrapSyncCryptoManager(),
            operationImplementationFactory = OperationImplementationFactory { id ->
                if (id == Stream.ID) RefusingStream(refuse, subscribes, implementationFactory(id)!!)
                else implementationFactory(id)
            },
            schemaRegistryFactory = { tContextProvider ->
                createDefaultSchemaRegistry(tContextProvider, extraConfigs = listOf(syntheticServiceModule))
            },
            initialization = { initSyntheticService() },
        )
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1)
        )

        val results = MutableStateFlow<List<String>>(emptyList())
        val failures = MutableStateFlow(0)
        backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items.flow().collect { result ->
                result.fold(
                    onSuccess = { results.value = it.toList().map { d -> d.name.get() } },
                    onFailure = { failures.value++ },
                )
            }
        }

        // 1 s backoff each time would be ~8 subscribes in this window; 1, 2, 4 s is 4 (t = 0, 1, 3, 7).
        withContext(Dispatchers.Default) { delay(7_500) }
        val attempts = subscribes.value
        assertTrue(attempts in 3..5, "expected a backing-off retry, saw $attempts subscribes in 7.5 s")
        assertEquals(1, failures.value, "one failure run, one report")

        // It still recovers once the snapshot can be delivered, and a later run is reported again.
        refuse.value = false
        await(results) { it == listOf("alpha") }
    }

    /** The real list stream, except that while [refuse] holds the snapshot is refused with an end. */
    private class RefusingStream(
        private val refuse: MutableStateFlow<Boolean>,
        private val subscribes: MutableStateFlow<Int>,
        private val real: org.tenshu.core.common.proxy.executor.OperationImplementation,
    ) : org.tenshu.core.common.proxy.executor.AsyncOperationImplementation {
        override suspend fun <T : org.tenshu.core.Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, org.tenshu.core.Data>?,
        ): T {
            subscribes.value++
            if (!refuse.value) {
                @Suppress("UNCHECKED_CAST")
                return (real as org.tenshu.core.common.proxy.executor.AsyncOperationImplementation)
                    .execute(executionContext, operationContext, implementationFactory, caller, params)
            }
            val streamId = (params!![Stream.Params.STREAM_ID] as org.tenshu.core.number.TInteger).get()
            executionContext.clientConnection.send(
                org.tenshu.core.common.impl.StreamElementEndDto(
                    streamId,
                    org.tenshu.core.common.impl.InternalErrorDto(internalDetail = "IPC: refused"),
                )
            )
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    private suspend fun <T> await(state: MutableStateFlow<T>, predicate: (T) -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (!predicate(state.value)) delay(POLL_MS) }
        }

    private fun proxy(scope: CoroutineScope, subscribeFails: MutableStateFlow<Boolean>) =
        InProcessProxy(
            clientId = "subscribe-retry-client",
            serviceScope = scope,
        syncCryptoManager = BootstrapSyncCryptoManager(),
            operationImplementationFactory = OperationImplementationFactory { id ->
                if (id == Stream.ID && subscribeFails.value) null else implementationFactory(id)
            },
            schemaRegistryFactory = { tContextProvider ->
                createDefaultSchemaRegistry(
                    tContextProvider,
                    extraConfigs = listOf(syntheticServiceModule)
                )
            },
            initialization = { initSyntheticService() },
        )

    private companion object {
        val TIMEOUT = 60.seconds
        const val WAIT_MS = 20_000L
        const val POLL_MS = 10L
    }
}
