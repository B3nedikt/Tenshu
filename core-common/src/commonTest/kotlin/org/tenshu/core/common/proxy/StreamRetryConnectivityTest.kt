package org.tenshu.core.common.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.ProxyConnectivity
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.changes
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A retry that cannot possibly succeed should wait for the reason it is failing to change, not for
 * a clock (`docs/proposals/core-client-resubscription.md` R5).
 *
 * R1b made a failed subscribe retry instead of being a one-way door, on a 1s → 2s → 4s → 8s → 15s
 * backoff. That is right when the proxy is *there* and refusing (an announce-gate skew persists
 * until the user updates an app — there is no event to wait for). It is pure waste when the
 * **channel** is down: every attempt is a guaranteed-failing IPC round trip, and a proxy can stay
 * unreachable indefinitely, so a collected read cost one round trip per 15 s forever.
 *
 * ⚠ The discriminating assertion is the *attempt count*, not the recovery: recovery worked before
 * this too, just on a timer. What changed is that no attempt is made while the channel is known
 * down, and that the reconnect edge — not the next tick of the backoff — is what wakes the retry.
 */
class StreamRetryConnectivityTest {

    /** A channel the test opens and closes by hand. */
    private class TestConnectivity : ProxyConnectivity {
        val connected = MutableStateFlow(false)
        private var reconnected = CompletableDeferred<Unit>()

        override val isConnected: Boolean get() = connected.value

        override suspend fun awaitConnected() {
            if (connected.value) return
            reconnected.await()
        }

        fun reconnect() {
            connected.value = true
            reconnected.complete(Unit)
        }
    }

    @Test
    fun aDownChannelIsWaitedOnRatherThanRetriedOnATimer() = runTest(timeout = TIMEOUT) {
        val subscribeFails = MutableStateFlow(true)
        val attempts = MutableStateFlow(0)
        val proxy = proxy(backgroundScope, subscribeFails, attempts)
        val connectivity = TestConnectivity()

        val ticks = MutableStateFlow(0)
        backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items
                .changes(context = proxy.tContext.withConnectivity(connectivity))
                .collect { ticks.value++ }
        }

        // One attempt is made regardless — the read has to try before it can know. What must NOT
        // happen is the backoff walking on: by 2.5s the old timer had fired at 1s and at 3s was
        // pending, i.e. at least two attempts.
        await(attempts) { it >= 1 }
        withContext(Dispatchers.Default) { delay(2_500) }
        assertEquals(1, attempts.value, "no attempt may be made while the channel is known down")
        assertEquals(0, ticks.value, "a failed subscribe must not fabricate change signals")

        // The channel comes back and the proxy is serving again: the retry wakes on the edge.
        subscribeFails.value = false
        connectivity.reconnect()
        await(ticks) { it >= 1 }
        assertTrue(attempts.value >= 2, "the reconnect edge woke the retry")

        // ...and the recovered subscription is live, not a one-shot.
        val before = ticks.value
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1)
        )
        await(ticks) { it > before }
    }

    @Test
    fun aConnectedProxyThatRefusesStillRetriesOnTheTimer() = runTest(timeout = TIMEOUT) {
        // The announce-gate skew case: the channel is up, so there is no edge to wait for. If this
        // read waited on connectivity it would never retry at all — the failure mode R5 must not
        // introduce while fixing the other one.
        val subscribeFails = MutableStateFlow(true)
        val attempts = MutableStateFlow(0)
        val proxy = proxy(backgroundScope, subscribeFails, attempts)
        val connectivity = TestConnectivity().also { it.connected.value = true }

        backgroundScope.launch(Dispatchers.Default) {
            proxy.client.syntheticService.items
                .changes(context = proxy.tContext.withConnectivity(connectivity))
                .collect { }
        }

        await(attempts) { it >= 2 }
    }

    private fun TContext.withConnectivity(connectivity: ProxyConnectivity): TContext {
        val base = this
        return object : TContext {
            override val components: Map<String, TComponent>
                get() = base.components + (ProxyConnectivity.ID to connectivity)
        }
    }

    private suspend fun <T> await(state: MutableStateFlow<T>, predicate: (T) -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (!predicate(state.value)) delay(POLL_MS) }
        }

    private fun proxy(
        scope: CoroutineScope,
        subscribeFails: MutableStateFlow<Boolean>,
        attempts: MutableStateFlow<Int>,
    ) = InProcessProxy(
        clientId = "retry-connectivity-client",
        serviceScope = scope,
        syncCryptoManager = BootstrapSyncCryptoManager(),
        operationImplementationFactory = OperationImplementationFactory { id ->
            if (id == Stream.ID) {
                attempts.value += 1
                if (subscribeFails.value) return@OperationImplementationFactory null
            }
            implementationFactory(id)
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
