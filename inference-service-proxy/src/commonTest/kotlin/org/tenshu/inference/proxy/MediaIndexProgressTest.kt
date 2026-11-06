package org.tenshu.inference.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.tenshu.core.get
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.indexProgress
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The `indexProgress` **push stream** over the real wire (IN-D6, 1e-3) — client RO → streamId →
 * proxy executor → pushed elements → the client's `Flow`.
 *
 * ⚠ The holder is driven directly rather than by a real pass: the subject is the wire shape and the
 * termination rule, not the indexer. ⚠ The transport runs on real dispatchers, so these collect on
 * `Dispatchers.Default` and wait in real time rather than advancing virtual time — the shape
 * `ModelDownloadProgressTest` uses, and for the same reason.
 *
 * ⚠⚠ These deliberately do **not** collect `holder.progress` and assert on what they saw: that is
 * the conflation trap §29.4 records. What is asserted here is what crossed the *wire*, which is a
 * different fact and the one this slice adds.
 */
class MediaIndexProgressTest {

    private fun proxyWith(holder: IndexProgressHolder, scope: CoroutineScope): TestProxy = TestProxy(
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
                indexProgress = holder,
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

    @Test
    fun ticksArePushedOverTheWireAndTheStreamEndsWithThePass() = runTest(timeout = STREAM_TIMEOUT) {
        val holder = IndexProgressHolder()
        // ⚠ Started BEFORE subscribing: a `StateFlow` replays its current value, which is what lets a
        // screen opened mid-pass show the count it is already at rather than waiting for a tick.
        holder.start(total = 9_000)
        holder.advance(300)
        val client = clientOf(proxyWith(holder, backgroundScope))

        val seen = MutableStateFlow(emptyList<Pair<Boolean, Int>>())
        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.indexProgress()
                .collect { seen.value = seen.value + (it.indexing.get() to it.done.get().toInt()) }
            completed.value = true
        }

        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while ((true to 300) !in seen.value) delay(POLL_MS) }
        }
        holder.advance(301)
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while ((true to 301) !in seen.value) delay(POLL_MS) }
        }

        // ⚠ `finish()` clears the entry → present-to-absent is the terminal signal.
        holder.finish()
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!completed.value) delay(POLL_MS) } }

        assertTrue((true to 301) in seen.value, "the latest tick was pushed over the wire")
        // ⚠⚠ The terminal tick is an EXPLICIT `indexing = false`, not merely a completion: a UI that
        // watched a pass to its end has to be told it ended, and inferring that from a closed flow
        // makes "finished" and "the proxy went away" the same observation.
        assertEquals(false to 0, seen.value.last(), "the stream's last word was an explicit idle tick")
        assertTrue(completed.value, "the stream completed when the pass finished")
    }

    /**
     * ⚠⚠ An idle device is answered, not left waiting. There is no `isDownloading` equivalent for
     * indexing — a pass is scheduled, not user-initiated — so an absent holder entry is not the
     * ambiguity `downloadProgress` has to wait out; it means exactly "no pass is running", which is
     * a fact worth one tick. Waiting instead would be the region twin's R3 regression: a client
     * hanging behind a bar that never moves and never errors.
     */
    @Test
    fun anIdleDeviceIsToldSoAndTheStreamEnds() = runTest(timeout = STREAM_TIMEOUT) {
        val client = clientOf(proxyWith(IndexProgressHolder(), backgroundScope))

        val seen = MutableStateFlow(emptyList<Boolean>())
        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.indexProgress().collect { seen.value = seen.value + it.indexing.get() }
            completed.value = true
        }

        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!completed.value) delay(POLL_MS) } }

        assertEquals(listOf(false), seen.value, "exactly one tick, saying nothing is running")
        assertTrue(completed.value, "an idle device ended the stream rather than hanging")
    }

    /**
     * ⚠⚠ **The proxy-side collector stops too, and that needs its own test.**
     *
     * `endElement` is sent from the `collect` block, so the CLIENT terminates correctly whether or
     * not `transformWhile` ever stops — which is why a mutant making the stream run forever passed
     * every other case in this file. What it breaks is invisible from the client: the proxy's
     * collector lives for the session, and each later pass pushes ticks at a streamId nobody is
     * listening to. That leak is the whole reason the predicate exists (see the implementation's
     * KDoc), so it is asserted on the one thing that can see it — the holder's subscription count.
     */
    @Test
    fun theProxySideCollectorStopsWhenTheStreamEnds() = runTest(timeout = STREAM_TIMEOUT) {
        val holder = IndexProgressHolder()
        val client = clientOf(proxyWith(holder, backgroundScope))

        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.indexProgress().collect { }
            completed.value = true
        }

        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!completed.value) delay(POLL_MS) } }
        // ⚠ The client's End can arrive before the proxy's collector has unwound, so this waits
        // rather than reading once — a bare assertion here would be a race, not a fact.
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (holder.subscriberCount.value > 0) delay(POLL_MS) }
        }

        assertEquals(0, holder.subscriberCount.value, "the proxy stopped collecting")
    }

    /**
     * ⚠⚠ A pass over a library with **no images** is a real state and is not idleness: `start(0)` is
     * what `MediaIndexer` does when the walk finds nothing. That is why `indexing` is a field rather
     * than something inferred from `total == 0` — the two would otherwise be the same tick.
     */
    @Test
    fun aPassOverAnEmptyLibraryIsNotReportedAsIdle() = runTest(timeout = STREAM_TIMEOUT) {
        val holder = IndexProgressHolder()
        holder.start(total = 0)
        val client = clientOf(proxyWith(holder, backgroundScope))

        val seen = MutableStateFlow(emptyList<Pair<Boolean, Int>>())
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.indexProgress()
                .collect { seen.value = seen.value + (it.indexing.get() to it.total.get().toInt()) }
        }

        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (seen.value.isEmpty()) delay(POLL_MS) }
        }

        assertEquals(true to 0, seen.value.first(), "an empty-library pass reports as running")
    }

    private companion object {
        val STREAM_TIMEOUT = 30.seconds
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
