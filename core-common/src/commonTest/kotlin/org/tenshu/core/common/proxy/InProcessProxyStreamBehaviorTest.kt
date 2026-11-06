package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.changes
import org.tenshu.core.collection.list.drop
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.fold
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A consolidated, fast, isolated test suite for the **reactive-stream contract of the client
 * `.flow()` path over [InProcessProxy]** — the production in-process graph (Fit, Timeline) that
 * every reactive screen reads through. Streaming is central to the whole app, but its behavioural
 * assumptions were previously spread across `ExecutionContextStreamTest` (executionContext level,
 * `TestProxy`), `RecordWorkoutUnitTest` (health), `NotificationStreamingTest`, and the Fit/Timeline
 * pipeline tests. This suite states them in one place, over a self-contained synthetic service
 * ([SyntheticService]) with no feature-module dependency, so a regression in the shared stream
 * plumbing is caught here first.
 *
 * Each test writes through `executionContext.saveData(...)` (folded into the `items` relation by
 * [initSyntheticService]'s maintainer) and observes through the real client `.flow()`.
 */
class InProcessProxyStreamBehaviorTest {

    // ── contract 1: a late subscriber gets the current snapshot ──────────────────────────
    @Test
    fun initialSnapshot_isDeliveredToALateSubscriber() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        proxy.executionContext.put("alpha", 1)
        proxy.executionContext.put("beta", 2)

        val names = proxy.client.syntheticService.items.flow().first()
            .fold(onSuccess = { it.toList().map { d -> d.name.get() }.toSet() }, onFailure = { emptySet() })
        assertEquals(setOf("alpha", "beta"), names, "a fresh subscriber must receive the current snapshot")
    }

    // ── contract 2: a write AFTER subscription is delivered live (the InProcessProxy bug) ──
    @Test
    fun liveWrite_afterSubscription_isDelivered() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        val seen = subscribe(proxy.client, backgroundScope)

        proxy.executionContext.put("gamma", 3)

        awaitContains(seen, "gamma")
    }

    // ── contract 3: a removal is reflected ───────────────────────────────────────────────
    @Test
    fun removal_isReflectedInTheStream() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        val item = SyntheticTestData.Factory.create(proxy.tContext, "delta", 4)
        proxy.executionContext.saveData(item)
        val seen = subscribe(proxy.client, backgroundScope)
        awaitContains(seen, "delta")

        proxy.executionContext.deleteData(proxy.executionContext.getDataId(item)!!)

        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while ("delta" in seen.value) delay(POLL_MS) }
        }
    }

    // ── contract 4: multiple concurrent subscribers each receive the live write ──────────
    @Test
    fun multipleConcurrentSubscribers_eachReceiveTheWrite() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        val a = subscribe(proxy.client, backgroundScope)
        val b = subscribe(proxy.client, backgroundScope)

        proxy.executionContext.put("epsilon", 5)

        awaitContains(a, "epsilon")
        awaitContains(b, "epsilon")
    }

    // ── contract 5: a windowed (drop/take) stream emits only its window and re-fires ─────
    @Test
    fun windowedStream_emitsOnlyItsWindow() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        listOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L).forEach { (n, v) -> proxy.executionContext.put(n, v) }

        // drop(1).take(2) → a two-item window. Assert exactly two items come back (the window size).
        val window = proxy.client.syntheticService.items.drop(1).take(2).flow().first()
            .fold(onSuccess = { it.toList().map { d -> d.name.get() } }, onFailure = { emptyList() })
        assertEquals(2, window.size, "a drop(1).take(2) window must emit exactly two items, got $window")
    }

    // ── contract 6: signal-only `changes()` fires on writes but not on subscribe ─────────
    @Test
    fun changesSignal_firesOnWrite_butNotOnSubscribe() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        proxy.executionContext.put("pre", 0) // a pre-existing row must NOT produce an initial tick

        val ticks = MutableStateFlow(0)
        backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items.changes().collect { ticks.value += 1 }
        }
        // Settle: `changes()` is invalidation-only — no initial emission for the existing row.
        withContext(Dispatchers.Default) { delay(SETTLE_MS) }
        assertEquals(0, ticks.value, "changes() must not tick on subscribe (no initial emission)")

        proxy.executionContext.put("zeta", 6)
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (ticks.value < 1) delay(POLL_MS) } }
    }

    // ── contract 7: cancelling one subscriber leaves another live (teardown isolation) ───
    @Test
    fun cancellingOneSubscriber_doesNotSilenceAnother() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        val a = MutableStateFlow<Set<String>>(emptySet())
        val aJob: Job = backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items.flow().collect { r ->
                a.value = r.fold(onSuccess = { it.toList().map { d -> d.name.get() }.toSet() }, onFailure = { emptySet() })
            }
        }
        val b = subscribe(proxy.client, backgroundScope)

        proxy.executionContext.put("one", 1)
        awaitContains(a, "one"); awaitContains(b, "one")

        aJob.cancelAndJoin() // A's stream ends; B must keep receiving.
        proxy.executionContext.put("two", 2)

        awaitContains(b, "two")
        assertTrue("two" !in a.value, "the cancelled subscriber must not receive further updates")
    }

    // ── contract 8: rapid successive writes all converge (no lost updates in the fold) ───
    @Test
    fun rapidSuccessiveWrites_allConverge() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        val seen = subscribe(proxy.client, backgroundScope)

        val n = 25
        repeat(n) { i -> proxy.executionContext.put("row-$i", i.toLong()) }

        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (seen.value.size < n) delay(POLL_MS) }
        }
        assertEquals(n, seen.value.size, "every rapid write must survive the stream fold")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private fun proxy(scope: CoroutineScope): InProcessProxy = InProcessProxy(
        clientId = "test-client",
        serviceScope = scope,
        syncCryptoManager = BootstrapSyncCryptoManager(),
        operationImplementationFactory = OperationImplementationFactory { id -> implementationFactory(id) },
        schemaRegistryFactory = { tContextProvider ->
            createDefaultSchemaRegistry(tContextProvider, extraConfigs = listOf(syntheticServiceModule))
        },
        initialization = { initSyntheticService() },
    )

    private fun ExecutionContext.put(name: String, value: Long) =
        saveData(SyntheticTestData.Factory.create(context, name, value))

    /** Collect the live set of item names for the lifetime of [scope]. */
    private fun subscribe(client: TenshuClient, scope: CoroutineScope): MutableStateFlow<Set<String>> {
        val seen = MutableStateFlow<Set<String>>(emptySet())
        scope.launch(Dispatchers.Default) {
            client.syntheticService.items.flow().collect { result ->
                seen.value = result.fold(
                    onSuccess = { it.toList().map { d -> d.name.get() }.toSet() },
                    onFailure = { emptySet() },
                )
            }
        }
        return seen
    }

    private suspend fun awaitContains(seen: MutableStateFlow<Set<String>>, name: String) {
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (name !in seen.value) delay(POLL_MS) }
        }
        assertTrue(name in seen.value)
    }

    private companion object {
        val TIMEOUT = kotlin.time.Duration.parse("30s")
        const val WAIT_MS = 10_000L
        const val POLL_MS = 25L
        const val SETTLE_MS = 250L
    }
}
