package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.entity.DataId
import org.tenshu.core.fold
import org.tenshu.core.security.BootstrapSyncCryptoManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stream subscribed again must not be handed the **previous** subscription's elements.
 *
 * Stream ids are callstack hashes, so the same chain subscribed twice in a row reuses one id; and
 * in process the proxy's elements reach the client through one queue (`forwardStreamElementsInOrder`),
 * drained by a consumer that can fall behind. A subscription that ended with its snapshot still
 * queued left that snapshot behind under the id — and the next subscription of the same chain
 * registered under it in time to be routed it, ahead of its own. `TList.flow` merges every
 * `Value` it is given, so the dead snapshot came out as the read's first emission and — for a row
 * deleted in between — **stayed** in the list: the fresh snapshot merges rows, it does not remove
 * the ones it lacks.
 *
 * The fix stamps each element when the client **receives** it and drops, at dispatch, one received
 * before the interactor it would be routed to was registered: that interactor's own elements can
 * only follow its start, which is sent after it registers (`DefaultInteractorProvider`,
 * `StreamElementReceipts`). No hop is added to subscribe.
 *
 * ⚠ The forcing: the element consumer alone (`InProcessProxy`'s `streamScope`; the proxy's own
 * work runs free) is on [HeldDispatcher], held across the first subscription, the delete, and the
 * second subscription's start, so the ended subscription's snapshot is still queued when the second
 * one registers. [awaitSecondStart] waits for that registration before the gate opens.
 */
class StreamResubscribeStaleBacklogTest {

    /** Runs nothing while [held]; [open] lets the parked tasks and every later one through. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val parked = mutableListOf<Runnable>()
        private var held = false

        fun hold() = synchronized(this) { held = true }

        fun open() {
            val release = synchronized(this) {
                held = false
                parked.toList().also { parked.clear() }
            }
            release.forEach { Dispatchers.Default.dispatch(Dispatchers.Default, it) }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val park = synchronized(this) { if (held) parked.add(block) else Unit; held }
            if (!park) Dispatchers.Default.dispatch(context, block)
        }
    }

    @Test
    fun aResubscribeIsNotHandedTheEndedSubscriptionsQueuedSnapshot() = runBlocking {
        val gate = HeldDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val streamScope = CoroutineScope(SupervisorJob() + gate)
        try {
            val proxy = proxy(scope, streamScope)
            val context = proxy.executionContext
            context.saveData(SyntheticTestData.Factory.create(context.context, "alpha", 1))
            context.saveData(SyntheticTestData.Factory.create(context.context, "beta", 2))
            // Warm: one full subscription with the consumer running, so nothing below is first-use.
            assertEquals(setOf("alpha", "beta"), names(proxy).first())

            gate.hold()
            // The first subscription's snapshot {alpha, beta} is queued behind the held consumer;
            // the read gives up on it, which ends the subscription (as `first()` does once an
            // `onStart` seed answered it — the consent read's shape).
            assertEquals(null, withTimeoutOrNull(300) { names(proxy).first() })
            assertEquals(0, context.streamRegistry.activeStreamCount.value, "the first subscription ended")

            context.deleteData(DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, "alpha"))

            val seen = CopyOnWriteArrayList<Set<String>>()
            val second = launch(Dispatchers.Default) { names(proxy).collect { seen += it } }
            awaitSecondStart(context)
            gate.open()

            withTimeoutOrNull(5_000) { while (seen.lastOrNull() != setOf("beta")) delay(10) }
            delay(200) // anything still queued would land here
            // ⚠ Every emission, not the last: the ended subscription's snapshot shows up as an
            // emission holding the deleted row — first, and (merged, never removed) for good.
            assertEquals(setOf("beta"), seen.lastOrNull(), "the read settles on the store; every emission seen: $seen")
            assertTrue(
                seen.none { "alpha" in it },
                "the second subscription was handed the ended one's queued snapshot (alpha was deleted before it " +
                    "subscribed); every emission seen: $seen",
            )
            second.cancel()
        } finally {
            gate.open()
            streamScope.cancel()
            scope.cancel()
        }
    }

    /** Up to 1 s for the second subscription to register (it does at once — subscribe never waits on the queue). */
    private suspend fun awaitSecondStart(context: ExecutionContext) {
        withTimeoutOrNull(1_000) { while (context.streamRegistry.activeStreamCount.value == 0) delay(10) }
    }

    private fun names(proxy: InProcessProxy) = kotlinx.coroutines.flow.flow {
        proxy.client.syntheticService.items.flow().collect { result ->
            emit(result.fold(onSuccess = { list -> list.toList().map { it.name.get() }.toSet() }, onFailure = { emptySet() }))
        }
    }

    private fun proxy(scope: CoroutineScope, streamScope: CoroutineScope): InProcessProxy = InProcessProxy(
        clientId = "stale-backlog-client",
        serviceScope = scope,
        streamScope = streamScope,
        syncCryptoManager = BootstrapSyncCryptoManager(),
        operationImplementationFactory = OperationImplementationFactory { id -> implementationFactory(id) },
        schemaRegistryFactory = { tContextProvider ->
            createDefaultSchemaRegistry(tContextProvider, extraConfigs = listOf(syntheticServiceModule))
        },
        initialization = { initSyntheticService() },
    )
}
