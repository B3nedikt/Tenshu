package org.tenshu.inference.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.peopleIndexChanges
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * The `peopleIndexChanges` **push stream** over the real wire (`photos-pets.md` §10.19): the
 * baseline, every later move, and — the part only a long-lived stream owes — the proxy's feeder
 * stopping when the subscriber ends the stream. `MediaIndexProgressTest`'s shape and dispatchers.
 */
class PeopleIndexChangesTest {

    private fun proxyWith(revision: StateFlow<Long>?, scope: CoroutineScope): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        coroutineScope = scope,
        implementationFactoryOverride = { id, _ ->
            inferenceServiceProxyImplementationFactory(
                id = id,
                registry = InferenceBackendRegistry(emptyList()),
                embeddings = InMemoryEmbeddingStore(),
                deviceId = "device-a",
                peopleRevision = revision,
            )
        },
    )

    private fun clientOf(proxy: TestProxy): TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
    )

    private suspend fun until(what: () -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!what()) delay(POLL_MS) } }

    @Test
    fun theBaselineThenEveryMoveCrossesTheWire() = runTest(timeout = STREAM_TIMEOUT) {
        val revision = MutableStateFlow(4L)
        val client = clientOf(proxyWith(revision, backgroundScope))

        val seen = MutableStateFlow(emptyList<Long>())
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.peopleIndexChanges().collect { seen.value = seen.value + it }
        }

        until { seen.value == listOf(4L) }
        revision.value = 5L
        until { 5L in seen.value }
        revision.value = 6L
        until { 6L in seen.value }

        assertEquals(listOf(4L, 5L, 6L), seen.value, "the baseline, then each move, in order")
    }

    /**
     * ⚠⚠ The stream never ends by itself, so the leak `MediaIndexProgressImplementation`'s KDoc
     * names is this op's to avoid: a subscriber that stops collecting must stop the proxy's feeder,
     * or every Collections screen ever opened leaves one behind for the session.
     */
    @Test
    fun endingTheStreamStopsTheProxysFeeder() = runTest(timeout = STREAM_TIMEOUT) {
        val revision = MutableStateFlow(0L)
        val client = clientOf(proxyWith(revision, backgroundScope))

        val seen = MutableStateFlow(0)
        val collector = backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.peopleIndexChanges().collect { seen.value++ }
        }
        until { seen.value == 1 }
        until { revision.subscriptionCount.value == 1 }

        collector.cancel()

        until { revision.subscriptionCount.value == 0 }
        assertEquals(0, revision.subscriptionCount.value, "the proxy stopped collecting")
    }

    /**
     * ⚠ Two collectors of one chain share ONE stream id, interactor and proxy feeder (a stream's id is
     * its callstack's hash): ending one must leave the other fed, not take the shared feeder with it.
     */
    @Test
    fun oneCollectorsEndLeavesAnotherFed() = runTest(timeout = STREAM_TIMEOUT) {
        val revision = MutableStateFlow(0L)
        val client = clientOf(proxyWith(revision, backgroundScope))

        val first = MutableStateFlow(emptyList<Long>())
        val second = MutableStateFlow(emptyList<Long>())
        val one = backgroundScope.launch(Dispatchers.Default) { client.inferenceService.peopleIndexChanges().collect { first.value = first.value + it } }
        until { first.value.isNotEmpty() }
        backgroundScope.launch(Dispatchers.Default) { client.inferenceService.peopleIndexChanges().collect { second.value = second.value + it } }
        until { second.value.isNotEmpty() }
        assertEquals(1, revision.subscriptionCount.value, "one feeder for one chain")

        one.cancel()
        revision.value = 1L
        until { 1L in second.value }

        assertEquals(listOf(0L, 1L), second.value, "the late collector was caught up, then fed")
        assertEquals(1, revision.subscriptionCount.value, "the shared feeder outlived the first collector")
    }

    /**
     * ⚠⚠ A Control without the op (an older one, or a proxy with no runner) ends the stream with an
     * error, and the reader must SEE an error: a quietly completed flow would read as a library
     * that never changes again.
     */
    @Test
    fun aProxyWithoutTheOpFailsRatherThanFallingSilent() = runTest(timeout = STREAM_TIMEOUT) {
        val client = clientOf(proxyWith(null, backgroundScope))

        val thrown = MutableStateFlow<Throwable?>(null)
        val ended = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            try {
                client.inferenceService.peopleIndexChanges().collect { }
            } catch (e: Throwable) {
                thrown.value = e
            }
            ended.value = true
        }

        until { ended.value }
        assertNotNull(thrown.value, "an absent op ended the stream without an error")
    }

    private companion object {
        val STREAM_TIMEOUT = 30.seconds
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
