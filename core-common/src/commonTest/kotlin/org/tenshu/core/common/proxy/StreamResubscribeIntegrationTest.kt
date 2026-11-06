package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.fold
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * `resubscribeAll()` driven over the **real** stream stack
 * (`docs/proposals/core-client-resubscription.md` R1).
 *
 * The unit-level contract lives in `StreamResubscribeTest`; what this adds is that replaying a live
 * subscription end-to-end is *safe* — the flow survives it, keeps its identity, and still delivers
 * subsequent writes. That is the property the whole design rests on: because `streamId` is derived
 * deterministically from the callstack, the replayed subscribe re-binds to the *same* client-side
 * interactor and its callbacks, so no consumer ever learns a reconnect happened.
 *
 * ⚠ An [InProcessProxy] cannot reconnect (proxy and client share a process — the very reason iOS
 * route-1 and the web runtime are immune), so the transport-level trigger is not exercised here.
 * That half is Android framework code; its gate is the on-device `am force-stop` reproduction
 * described in the proposal §5.
 */
class StreamResubscribeIntegrationTest {

    @Test
    fun resubscribe_keepsALiveFlowWorking_andStillDeliversLaterWrites() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)
        proxy.executionContext.put("alpha", 1)

        val seen = subscribe(proxy.client, backgroundScope)
        awaitNames(seen, setOf("alpha"))

        // Stand in for the transport's reconnect callback.
        proxy.interactorProvider.resubscribeAll()

        // The pre-existing row must come back (the replayed subscribe answers with a fresh
        // snapshot), and the stream must still be live afterwards — a resubscribe that silently
        // detached the listener would look identical until the next write never arrived.
        awaitNames(seen, setOf("alpha"))

        proxy.executionContext.put("beta", 2)
        awaitNames(seen, setOf("alpha", "beta"))
    }

    @Test
    fun resubscribe_withNoLiveStreams_isANoOp() = runTest(timeout = TIMEOUT) {
        // Fires on every reconnect regardless of what the client is doing, so the empty case has to
        // be free of side effects rather than merely tolerable.
        val proxy = proxy(backgroundScope)
        proxy.interactorProvider.resubscribeAll()

        val names = proxy.client.syntheticService.items.flow().first()
            .fold(onSuccess = { it.toList().map { d -> d.name.get() }.toSet() }, onFailure = { emptySet() })
        assertEquals(emptySet(), names)
    }

    /**
     * The collector runs on real threads while `runTest` drives virtual time, so a bare
     * `withTimeout` fires instantly. Poll on a real dispatcher instead — the idiom
     * `InProcessProxyStreamBehaviorTest` already uses.
     */
    private suspend fun awaitNames(seen: MutableStateFlow<Set<String>>, expected: Set<String>) =
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (seen.value != expected) delay(POLL_MS) }
        }

    private fun proxy(scope: CoroutineScope): InProcessProxy = InProcessProxy(
        clientId = "resubscribe-client",
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

    private companion object {
        val TIMEOUT = 30.seconds
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
