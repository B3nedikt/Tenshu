package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.changes
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.entity.DataId
import org.tenshu.core.fold
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A **late** subscriber to an already-live stream must see the list as it is *now*
 * (`core-client-resubscription.md` R3 / Q2).
 *
 * `streamId` is derived deterministically from the callstack, so two collectors of the same
 * relation share one `StreamInteractorImpl` — the property R1's replay is built on. The second one
 * registers after the subscribe, so it cannot be sent a fresh snapshot; it is caught up from the
 * interactor's buffered `elements` instead.
 *
 * That buffer is an *event log*, and it has to be a faithful one. Buffering only the additions
 * would replay a history in which nothing was ever deleted.
 */
class StreamLateSubscriberTest {

    @Test
    fun aLateSubscriberDoesNotSeeRowsThatWereAlreadyDeleted() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)

        val first = subscribe(proxy.client, backgroundScope)
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1)
        )
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "beta", 2)
        )
        await(first) { it == setOf("alpha", "beta") }

        proxy.executionContext.deleteData(
            DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, "alpha")
        )
        await(first) { it == setOf("beta") }

        // Same callstack ⇒ same streamId ⇒ this attaches to the live interactor rather than
        // opening its own stream, and is caught up from the buffer.
        //
        // ⚠ Await the asserted set, not merely "something arrived". The catch-up is now folded into
        // one emission (`aLateSubscribersFirstEmissionIsTheBuffersState`), but a removal still in
        // the element queue when this registers is not in the buffer yet: it arrives after, as
        // its own emission, so `{alpha, beta}` can still be seen for an instant first. The timeout
        // keeps the assertion's diagnostic: it fails with the last set observed.
        val second = subscribe(proxy.client, backgroundScope)
        await(second, "the late subscriber's caught-up list to be exactly {beta}") { it == setOf("beta") }

        assertEquals(
            setOf("beta"),
            second.value,
            "the late subscriber replayed a history with no deletions in it",
        )
    }

    /**
     * A joiner's catch-up is presented as ONE emission — the list as the interactor's buffer has it —
     * never the log replayed step by step. The log opens with the snapshot from when the stream
     * *first* subscribed, so a joiner's first emission used to be that old snapshot: in Photos the
     * consent read re-subscribed after a write (the Models screen does after every press) showed
     * `realm=false` over a stored yes (`PhotosModelDownloadTest`). Red with the catch-up delivered
     * per element: the joiner's emissions are `[{}, {alpha}, {alpha, beta}]`.
     *
     * ⚠ The buffer's state, not the store's: a write still in the element queue when the joiner
     * registers reaches it afterwards, as its own emission. The window is narrowed, not closed.
     */
    @Test
    fun aLateSubscribersFirstEmissionIsTheBuffersState() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)

        val first = record(proxy.client, backgroundScope)
        // ⚠ The first subscription's snapshot (the empty relation) must be in the buffer before the
        // writes, or the buffer holds one snapshot of both rows and the test passes without the fix.
        await(first, "the first subscriber's snapshot") { it.isNotEmpty() }
        assertEquals(listOf(emptySet()), first.value, "the first subscriber's snapshot is the empty relation")
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", 1)
        )
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "beta", 2)
        )
        await(first) { it.lastOrNull() == setOf("alpha", "beta") }

        // Same callstack ⇒ joins the live interactor ⇒ caught up from its buffer.
        val joiner = record(proxy.client, backgroundScope)
        await(joiner, "the joiner's first emission") { it.isNotEmpty() }
        assertEquals(
            setOf("alpha", "beta"),
            joiner.value.first(),
            "a joiner's first emission must be the buffer's state, not its oldest snapshot; every emission: ${joiner.value}",
        )
    }

    /** Every emission of a `flow()` collector, in order. */
    private fun record(client: TenshuClient, scope: CoroutineScope): MutableStateFlow<List<Set<String>>> {
        val emissions = MutableStateFlow<List<Set<String>>>(emptyList())
        scope.launch(Dispatchers.Default) {
            client.syntheticService.items.flow().collect { result ->
                val names = result.fold(
                    onSuccess = { it.toList().map { d -> d.name.get() }.toSet() },
                    onFailure = { setOf("<failure>") },
                )
                emissions.value = emissions.value + listOf(names)
            }
        }
        return emissions
    }

    @Test
    fun theReplayBufferIsBoundedByRowsNotByChanges() = runTest(timeout = TIMEOUT) {
        val proxy = proxy(backgroundScope)

        // A signal-only subscriber emits exactly one tick per replayed element, which makes the
        // buffer's size observable without reaching into it.
        val first = ticks(proxy.client, backgroundScope)

        // ⚠ A signal-only stream has no initial emission, so there is nothing to wait *for* to know
        // it is live — a write that lands before the subscribe is simply missed and never resent.
        // Give it a moment, then prove liveness with one write before the burst.
        withContext(Dispatchers.Default) { delay(750) }
        proxy.executionContext.saveData(
            SyntheticTestData.Factory.create(proxy.executionContext.context, "seed", 0)
        )
        await(first) { it >= 1 }

        // ⚠ Spaced, not a burst: the store's write mailbox dedupes queued writes by hash, so
        // back-to-back edits of one row collapse before they ever become stream elements — which
        // would make this test pass against any implementation.
        repeat(UPDATES) { i ->
            proxy.executionContext.saveData(
                SyntheticTestData.Factory.create(proxy.executionContext.context, "alpha", i.toLong())
            )
            withContext(Dispatchers.Default) { delay(200) }
        }
        await(first) { it >= 1 + UPDATES }

        val late = ticks(proxy.client, backgroundScope)
        // Let the replay land and settle; the assertion is about how much of it there was.
        withContext(Dispatchers.Default) { delay(1_000) }

        // Two rows exist, so two replayed elements is the whole correct history.
        assertTrue(
            late.value <= 3,
            "a late subscriber replayed $UPDATES edits of ONE row as ${late.value} elements — the " +
                "buffer is keeping a history rather than a per-row latest",
        )
    }

    private fun ticks(client: TenshuClient, scope: CoroutineScope): MutableStateFlow<Int> {
        val count = MutableStateFlow(0)
        scope.launch(Dispatchers.Default) {
            client.syntheticService.items.changes().collect { count.value++ }
        }
        return count
    }

    /** Polls until [predicate] holds; a timeout fails with the LAST observed value, not a bare clock. */
    private suspend fun <T> await(
        state: MutableStateFlow<T>,
        what: String = "the awaited state",
        predicate: (T) -> Boolean,
    ) = withContext(Dispatchers.Default) {
        try {
            withTimeout(WAIT_MS) { while (!predicate(state.value)) delay(POLL_MS) }
        } catch (e: TimeoutCancellationException) {
            fail("Timed out after $WAIT_MS ms waiting for $what; last state = ${state.value}")
        }
    }

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

    private fun proxy(scope: CoroutineScope): InProcessProxy = InProcessProxy(
        clientId = "late-subscriber-client",
        serviceScope = scope,
        syncCryptoManager = BootstrapSyncCryptoManager(),
        operationImplementationFactory = OperationImplementationFactory { id ->
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
        const val WAIT_MS = 15_000L
        const val POLL_MS = 10L
        const val UPDATES = 5
    }
}
