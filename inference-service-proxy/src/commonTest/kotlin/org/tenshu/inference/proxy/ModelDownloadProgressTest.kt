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
import org.tenshu.inference.downloadProgress
import org.tenshu.inference.proxy.catalog.InMemoryModelStore
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelCatalogSource
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressHolder
import org.tenshu.inference.proxy.catalog.ModelDownloader
import org.tenshu.inference.proxy.catalog.ModelInventory
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The `downloadProgress` **push stream** over the real wire (IN-2c, 1c-3) — client RO → streamId →
 * proxy executor → pushed elements → the client's `Flow`.
 *
 * ⚠ The holder is driven directly rather than by a real fetch: the subject is the wire shape and the
 * registration asymmetry, not the download. ⚠ The transport runs on real dispatchers, so these
 * collect on `Dispatchers.Default` and wait in real time rather than advancing virtual time.
 */
class ModelDownloadProgressTest {

    /** ⚠ `isDownloading` is the seam the whole asymmetry turns on, so a test states it outright. */
    private class FakeDownloader(private val inFlight: Boolean) : ModelDownloader {
        override suspend fun download(modelId: String): InstalledModelData = error("not used")
        override suspend fun isDownloading(modelId: String) = inFlight
    }

    private fun proxyWith(
        holder: ModelDownloadProgressHolder,
        downloading: Boolean,
        scope: CoroutineScope,
    ): TestProxy {
        val store = InMemoryModelStore { "x" }
        val delivery = ModelDeliveryComponents(
            catalogSource = ModelCatalogSource { null },
            store = store,
            downloader = FakeDownloader(downloading),
            progress = holder,
            inventory = ModelInventory(store),
        )
        return TestProxy(
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
                    delivery = delivery,
                )
            },
        )
    }

    private fun clientOf(proxy: TestProxy): TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
    )

    @Test
    fun ticksArePushedOverTheWireAndTheStreamEndsWithTheDownload() = runTest(timeout = STREAM_TIMEOUT) {
        val holder = ModelDownloadProgressHolder()
        val client = clientOf(proxyWith(holder, downloading = true, scope = backgroundScope))

        val seen = MutableStateFlow(emptyList<Long>())
        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.downloadProgress("siglip")
                .collect { seen.value = seen.value + it.downloadedBytes.get() }
            completed.value = true
        }

        withContext(Dispatchers.Default) { delay(SETTLE_MS) }
        holder.start("siglip", totalBytes = 383_000L)
        holder.update("siglip", 100_000L)
        holder.update("siglip", 383_000L)
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (383_000L !in seen.value) delay(POLL_MS) }
        }

        // ⚠ Finishing removes the entry → present-to-absent is the terminal signal.
        holder.finish("siglip")
        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!completed.value) delay(POLL_MS) } }

        assertTrue(383_000L in seen.value, "the latest tick was pushed over the wire")
        assertTrue(completed.value, "the stream completed when the download finished")
    }

    /**
     * ⚠⚠ Nothing to report and nothing coming must **END**, not wait. Reached when the proxy
     * restarted under a subscriber — its in-memory holder died with it — or when `downloadModel`
     * failed before registering. Without the bounded branch the client's flow stays open behind a
     * bar that never moves and never errors, which is the region twin's R3 regression.
     */
    @Test
    fun theStreamEndsWhenNoDownloadIsInFlight() = runTest(timeout = STREAM_TIMEOUT) {
        val client = clientOf(
            proxyWith(ModelDownloadProgressHolder(), downloading = false, scope = backgroundScope)
        )

        val completed = MutableStateFlow(false)
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.downloadProgress("siglip").collect { }
            completed.value = true
        }

        withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { while (!completed.value) delay(POLL_MS) } }

        assertTrue(completed.value, "an unregistered, not-in-flight download ended rather than hanging")
    }

    /**
     * ⚠⚠ The other half of the asymmetry, and the reason it is not a plain timeout. A download
     * registers its entry **after** the catalog fetch — and on a resume, after replaying staged
     * bytes through a digest, which for a partly-downloaded 280 MB tower is longer still. A
     * wall-clock cutoff would kill healthy downloads on a slow link.
     */
    @Test
    fun theStreamKeepsWaitingWhileADownloadIsActuallyInFlight() = runTest(timeout = STREAM_TIMEOUT) {
        val holder = ModelDownloadProgressHolder()
        val client = clientOf(proxyWith(holder, downloading = true, scope = backgroundScope))

        val seen = MutableStateFlow(emptyList<Long>())
        backgroundScope.launch(Dispatchers.Default) {
            client.inferenceService.downloadProgress("siglip")
                .collect { seen.value = seen.value + it.downloadedBytes.get() }
        }

        // ⚠ Past the registration grace, with the entry still absent — a bounded wait would have
        // given up by now.
        withContext(Dispatchers.Default) { delay(SLOW_START_MS) }
        holder.start("siglip", totalBytes = 383_000L)
        holder.update("siglip", 7_000L)

        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MS) { while (7_000L !in seen.value) delay(POLL_MS) }
        }

        assertTrue(7_000L in seen.value, "a slow-to-register download was still streamed")
    }

    private companion object {
        val STREAM_TIMEOUT = 30.seconds
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 200L

        /** ⚠ Comfortably past `ModelDownloadProgressImplementation`'s 3 s registration grace. */
        const val SLOW_START_MS = 4_000L
    }
}
