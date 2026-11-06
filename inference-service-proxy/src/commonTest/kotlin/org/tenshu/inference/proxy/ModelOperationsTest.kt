package org.tenshu.inference.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.get
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InferenceService
import org.tenshu.inference.ModelUnavailable
import org.tenshu.inference.availableModels
import org.tenshu.inference.capabilityIds
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.deleteModel
import org.tenshu.inference.downloadModel
import org.tenshu.inference.installedModels
import org.tenshu.inference.proxy.catalog.InMemoryModelStore
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelCatalogData
import org.tenshu.inference.proxy.catalog.InstalledIndex
import org.tenshu.inference.proxy.catalog.ModelCatalogSource
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressHolder
import org.tenshu.inference.proxy.catalog.ModelDownloader
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelInventory
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreChanges
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four model operations end-to-end over the op wire — RO → param map → proxy → returned
 * composites, and every typed refusal.
 *
 * ⭐ No network and no key material: the catalog source and the downloader are seams, which is what
 * they were built as. What is real is the **wire** — a type that is registered wrongly fails here
 * and nowhere else (§18's `Schema not found` lesson).
 */
class ModelOperationsTest {

    private fun entry(
        modelId: String = "siglip",
        name: String = "SigLIP (multilingual)",
    ) = ModelEntryData(
        modelId = modelId,
        name = name,
        modelVersion = 3,
        capabilities = listOf(
            InferenceCapability.SEMANTIC_IMAGE_SEARCH,
            InferenceCapability.CONTENT_TAGGING,
        ),
        labelSetVersion = 2,
        runtime = "onnx",
        artifactVersion = 1,
        artifacts = listOf(
            ModelArtifactData(ModelArtifactRole.VISION, "m/vision.onnx", "aa", 100),
            ModelArtifactData(ModelArtifactRole.TEXT, "m/text.onnx", "bb", 280),
            ModelArtifactData(ModelArtifactRole.VOCABULARY, "m/vocab.bin", "cc", 3),
        ),
        licence = "Apache-2.0",
    )

    /** A downloader seam — no HTTP, so a test states the outcome rather than staging one. */
    private class FakeDownloader(
        private val store: ModelStore,
        private val onDownload: (String) -> Unit = {},
    ) : ModelDownloader {
        override suspend fun download(modelId: String) = run {
            onDownload(modelId)
            store.installed().first { it.modelId == modelId }
        }
        override suspend fun isDownloading(modelId: String) = false

        /** What the store held when a removal was handed over, per model. */
        val removalsHandedOver = ArrayList<Pair<String, Int>>()
        override suspend fun <T> withoutDownloadOf(modelId: String, changes: ModelStoreChanges, remove: suspend () -> T): T {
            removalsHandedOver += modelId to store.installed().size
            // ⚠ The interface's own default, which is what Android and desktop run.
            return super.withoutDownloadOf(modelId, changes, remove)
        }
    }

    private fun delivery(
        catalog: ModelCatalogData?,
        store: ModelStore = InMemoryModelStore { "aa" },
        downloader: ModelDownloader? = null,
        storeChanges: ModelStoreChanges = ModelStoreChanges.Direct,
    ) = ModelDeliveryComponents(
        catalogSource = ModelCatalogSource { catalog },
        store = store,
        downloader = downloader ?: FakeDownloader(store),
        progress = ModelDownloadProgressHolder(),
        inventory = ModelInventory(store),
        storeChanges = storeChanges,
    )

    private fun proxy(delivery: ModelDeliveryComponents): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
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

    private fun service(proxy: TestProxy): InferenceService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(InferenceService.ID)
    }

    private fun catalogOf(vararg entries: ModelEntryData) =
        ModelCatalogData(schemaVersion = 1, buildDate = "2026-09-04", models = entries.toList())

    @Test
    fun aVerifiedCatalogCrossesTheWireAsRowsAScreenCanRender() = runTest {
        val result = service(proxy(delivery(catalogOf(entry())))).availableModels()

        val rows = (result as TResult.Success).value!!.toList()
        val row = rows.single()
        assertEquals("siglip", row.modelId.get())
        assertEquals("SigLIP (multilingual)", row.name.get())
        // ⚠⚠ The SUM of every artifact — what installing actually costs, not the largest file.
        assertEquals(383L, row.byteSize.get())
        assertEquals("Apache-2.0", row.licence.get())
        assertEquals(
            listOf(
                InferenceCapability.SEMANTIC_IMAGE_SEARCH.id,
                InferenceCapability.CONTENT_TAGGING.id,
            ),
            row.capabilityIds(),
        )
    }

    /** ⚠ A nameless entry stays installable — a missing display string must not cost a model. */
    @Test
    fun anEntryWithNoNameFallsBackToItsId() = runTest {
        val result = service(proxy(delivery(catalogOf(entry(name = ""))))).availableModels()

        assertEquals("siglip", (result as TResult.Success).value!!.toList().single().name.get())
    }

    /**
     * ⚠⚠ Today's state on every device: no release key, so no catalog. It must arrive as a typed
     * `NO_CATALOG`, never as an empty list — "we publish no models" is a false sentence about us in
     * place of a true one about the device.
     */
    @Test
    fun noTrustedCatalogIsATypedFailureNotAnEmptyList() = runTest {
        val result = service(proxy(delivery(catalog = null))).availableModels()

        assertEquals(
            ModelUnavailable.Reasons.NO_CATALOG,
            ((result as TResult.Failure).error as ModelUnavailable).reason.get(),
        )
    }

    /** ⚠ An empty list means something different, and is reachable: the catalog offers nothing here. */
    @Test
    fun aCatalogWithNothingForThisDeviceIsAnEmptySuccess() = runTest {
        val result = service(proxy(delivery(catalogOf()))).availableModels()

        assertTrue((result as TResult.Success).value!!.toList().isEmpty())
    }

    @Test
    fun installedModelsReadsLocalStateAndNeverNeedsACatalog() = runTest {
        val store = InMemoryModelStore { it.decodeToString() }
        store.add(
            entry().copy(
                artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100)),
            ),
            mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()),
        )

        // ⚠ Catalog deliberately null — this op must still answer.
        val result = service(proxy(delivery(catalog = null, store = store))).installedModels()

        val row = (result as TResult.Success).value!!.toList().single()
        assertEquals("siglip", row.modelId.get())
        assertEquals(3L, row.modelVersion.get())
        assertEquals(2L, row.labelSetVersion.get())
        assertEquals("Apache-2.0", row.licence.get())
    }

    /**
     * ⚠⚠ Bytes that are no index are not *nothing installed*. Answered as an empty list, a screen
     * shows every model as absent and offers a download whose commit the store then refuses.
     */
    @Test
    fun whatIsNoIndexIsAFailedReadAndNeverAnEmptyList() = runTest {
        val held = InMemoryModelStore { it.decodeToString() }
        held.add(
            entry().copy(artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100))),
            mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()),
        )
        val notAnIndex = object : ModelStore by held {
            // What `DefaultModelStore` answers over such bytes: no records, by decision.
            override fun installed(): List<org.tenshu.inference.proxy.catalog.InstalledModelData> = emptyList()
            override fun holdsAnIndex() = false
            override fun read(): InstalledIndex = super<ModelStore>.read()
        }
        val unreadable = object : ModelStore by held {
            override fun read(): InstalledIndex = InstalledIndex.Unreadable("the index is there and could not be read")
        }

        val overNoIndex = service(proxy(delivery(catalog = null, store = notAnIndex))).installedModels()
        val overUnreadable = service(proxy(delivery(catalog = null, store = unreadable))).installedModels()

        val noIndex = (overNoIndex as TResult.Failure).error as ModelUnavailable
        assertEquals(ModelUnavailable.Reasons.TRANSPORT_FAILED, noIndex.reason.get())
        assertEquals(INSTALLED_NOT_AN_INDEX, noIndex.message.get())
        val failed = (overUnreadable as TResult.Failure).error as ModelUnavailable
        assertEquals(ModelUnavailable.Reasons.TRANSPORT_FAILED, failed.reason.get())
        assertEquals("the index is there and could not be read", failed.message.get())
        // ⚠ And an index that holds nothing is still an answer.
        val empty = service(proxy(delivery(catalog = null, store = InMemoryModelStore { "aa" }))).installedModels()
        assertEquals(emptyList(), (empty as TResult.Success).value!!.toList())
    }

    /**
     * ⚠⚠ The delete's own look at what is installed is the same three answers: bytes that are no
     * index were *no record*, so NOT_INSTALLED, which the Models screen shows as "Removed" over a
     * model that may still be there — on Android as on iOS (the operation is common).
     */
    @Test
    fun aDeleteOverWhatIsNoIndexIsAFailedReadAndNeverNotInstalled() = runTest {
        val held = InMemoryModelStore { it.decodeToString() }
        held.add(
            entry().copy(artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100))),
            mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()),
        )
        var removed = 0
        val notAnIndex = object : ModelStore by held {
            override fun installed(): List<org.tenshu.inference.proxy.catalog.InstalledModelData> = emptyList()
            override fun holdsAnIndex() = false
            override fun read(): InstalledIndex = super<ModelStore>.read()
            override fun remove(modelId: String) {
                removed++
            }
        }
        val overNoIndex = (service(proxy(delivery(catalog = null, store = notAnIndex))).deleteModel("siglip") as TResult.Failure).error as ModelUnavailable

        assertEquals(ModelUnavailable.Reasons.TRANSPORT_FAILED, overNoIndex.reason.get())
        assertEquals(INSTALLED_NOT_AN_INDEX, overNoIndex.message.get())
        assertEquals(0, removed, "a removal was made over an index nobody could read")
    }

    @Test
    fun downloadingReturnsTheInstalledRecord() = runTest {
        val store = InMemoryModelStore { it.decodeToString() }
        val one = entry().copy(
            artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100)),
        )
        val delivery = delivery(
            catalogOf(one),
            store = store,
            downloader = FakeDownloader(store) {
                store.add(one, mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()))
            },
        )

        val result = service(proxy(delivery)).downloadModel("siglip")

        assertEquals("siglip", (result as TResult.Success).value!!.modelId.get())
    }

    /**
     * ⚠⚠ The four download failures are four different sentences, and two of them a different
     * button: a transport failure is worth retrying, an integrity failure fetches the same wrong
     * bytes from the same CDN. Collapsing them would deny the user the button that works, or offer
     * one that cannot.
     */
    @Test
    fun eachDownloadFailureKeepsItsOwnReason() = runTest {
        val cases = listOf(
            org.tenshu.inference.proxy.catalog.ModelCatalogUnavailableException("no catalog")
                to ModelUnavailable.Reasons.NO_CATALOG,
            org.tenshu.inference.proxy.catalog.UnknownModelException("no such model")
                to ModelUnavailable.Reasons.UNKNOWN_MODEL,
            org.tenshu.inference.proxy.catalog.ModelIntegrityException("sha mismatch")
                to ModelUnavailable.Reasons.INTEGRITY_FAILED,
            org.tenshu.inference.proxy.catalog.ModelDownloadException("503")
                to ModelUnavailable.Reasons.TRANSPORT_FAILED,
        )

        cases.forEach { (thrown, expected) ->
            val throwing = object : ModelDownloader {
                override suspend fun download(modelId: String) = throw thrown
                override suspend fun isDownloading(modelId: String) = false
            }
            val result = service(proxy(delivery(catalogOf(entry()), downloader = throwing)))
                .downloadModel("siglip")

            assertEquals(
                expected,
                ((result as TResult.Failure).error as ModelUnavailable).reason.get(),
                "wrong reason for ${thrown::class.simpleName}",
            )
        }
    }

    @Test
    fun deletingRemovesTheModelFromThisDevice() = runTest {
        val store = InMemoryModelStore { it.decodeToString() }
        store.add(
            entry().copy(
                artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100)),
            ),
            mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()),
        )
        val svc = service(proxy(delivery(catalog = null, store = store)))

        assertTrue(svc.deleteModel("siglip") is TResult.Success)

        assertTrue(store.installed().isEmpty())
    }

    /**
     * ⚠⚠ Where a download outlives the process (iOS), a removal is handed to the downloader,
     * which ends a download of that model first: otherwise the update that was downloading
     * installs the model again after the screen was told it is gone.
     */
    @Test
    fun deletingIsHandedToTheDownloaderWhichEndsADownloadOfThatModelFirst() = runTest {
        val store = InMemoryModelStore { it.decodeToString() }
        store.add(
            entry().copy(artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100))),
            mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()),
        )
        val downloader = FakeDownloader(store)
        val svc = service(proxy(delivery(catalog = null, store = store, downloader = downloader)))

        assertTrue(svc.deleteModel("siglip") is TResult.Success)

        // Handed over while the model was still there, and removed inside.
        assertEquals(listOf("siglip" to 1), downloader.removalsHandedOver)
        assertTrue(store.installed().isEmpty())
    }

    /**
     * ⚠⚠ Where a removal must stand something down first (the iOS owner: the old generation stops
     * being served before its record goes, `ios-on-device-inference.md` §14.11), the removal runs
     * **inside** the wrapper the graph contributes, not beside it.
     */
    @Test
    fun deletingRunsInsideTheStoreChangeWrapper() = runTest {
        val store = InMemoryModelStore { it.decodeToString() }
        store.add(
            entry().copy(artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100))),
            mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()),
        )
        val seen = mutableListOf<Int>()
        val wrapper = object : ModelStoreChanges {
            override suspend fun <T> changing(change: suspend () -> T): T {
                seen += store.installed().size
                return change().also { seen += store.installed().size }
            }
        }
        val svc = service(proxy(delivery(catalog = null, store = store, storeChanges = wrapper)))

        assertTrue(svc.deleteModel("siglip") is TResult.Success)

        // Entered with the model installed, left with it gone.
        assertEquals(listOf(1, 0), seen)
    }

    /**
     * ⚠⚠ A proxy that does not own the model store (a second desktop Control) refuses every write.
     * Over the wire that must be its own reason: the catch-all bucket is `TRANSPORT_FAILED`, the
     * RETRYABLE one, and this cannot succeed while the process lives. The sentence travels too.
     */
    @Test
    fun aStoreOwnedByAnotherProcessIsItsOwnReasonNotARetryableOne() = runTest {
        val sentence = "Another Control process owns the model store."
        assertRefusalCrossesTheWire(
            org.tenshu.inference.proxy.catalog.ModelStoreRefusal.OwnedElsewhere(sentence),
            ModelUnavailable.Reasons.STORE_NOT_OWNED,
        )
    }

    /**
     * ⚠⚠ The lock could not be taken at all: not the same reason, because there may be no other
     * Control to quit — and still not the retryable one.
     */
    @Test
    fun aStoreLockThatCouldNotBeTakenIsNotReportedAsAnotherControl() = runTest {
        assertRefusalCrossesTheWire(
            org.tenshu.inference.proxy.catalog.ModelStoreRefusal.LockUnavailable(
                message = "Could not lock the model store (/p/store.lock: IOException: denied).",
                detail = "/p/store.lock: IOException: denied",
            ),
            ModelUnavailable.Reasons.STORE_LOCK_UNAVAILABLE,
        )
    }

    /**
     * ⚠⚠ **One mapping for every operation.** Whatever a downloader or the store throws, it has the
     * same reason whichever operation met it — a reason typed in one operation and not another
     * reaches the executor's catch-all with no reason at all. ⚠ The last five are the retryable
     * bucket because the vocabulary has no reason of their own for them yet.
     */
    @Test
    fun everyFailureHasTheSameTypedReasonWhicheverOperationMeetsIt() = runTest {
        val cases: List<Pair<Exception, String>> = listOf(
            org.tenshu.inference.proxy.catalog.ModelCatalogUnavailableException("no catalog") to ModelUnavailable.Reasons.NO_CATALOG,
            org.tenshu.inference.proxy.catalog.UnknownModelException("no such model") to ModelUnavailable.Reasons.UNKNOWN_MODEL,
            org.tenshu.inference.proxy.catalog.ModelIntegrityException("sha mismatch") to ModelUnavailable.Reasons.INTEGRITY_FAILED,
            org.tenshu.inference.proxy.catalog.ModelStoreNotOwnedException(org.tenshu.inference.proxy.catalog.ModelStoreRefusal.OwnedElsewhere("another Control")) to ModelUnavailable.Reasons.STORE_NOT_OWNED,
            org.tenshu.inference.proxy.catalog.ModelDownloadException("503") to ModelUnavailable.Reasons.TRANSPORT_FAILED,
            org.tenshu.inference.proxy.catalog.ModelDownloadJournalUnreadableException() to ModelUnavailable.Reasons.TRANSPORT_FAILED,
            org.tenshu.inference.proxy.catalog.ModelDownloadCancelledException("torn down") to ModelUnavailable.Reasons.TRANSPORT_FAILED,
            org.tenshu.inference.proxy.catalog.ModelStorageInsufficientException(2, 1) to ModelUnavailable.Reasons.TRANSPORT_FAILED,
            org.tenshu.inference.proxy.catalog.ModelIndexUnreadableException("the index is there and could not be read") to ModelUnavailable.Reasons.TRANSPORT_FAILED,
            IllegalStateException("I/O error") to ModelUnavailable.Reasons.TRANSPORT_FAILED,
        )
        cases.forEach { (thrown, expected) ->
            val what = thrown::class.simpleName
            val store = InMemoryModelStore { it.decodeToString() }
            val one = entry().copy(artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100)))
            store.add(one, mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()))
            val throwing = object : ModelDownloader {
                override suspend fun download(modelId: String) = throw thrown
                override suspend fun isDownloading(modelId: String) = false
                override suspend fun <T> withoutDownloadOf(modelId: String, changes: ModelStoreChanges, remove: suspend () -> T): T = throw thrown
            }
            val unreadable = object : ModelStore by store {
                override fun installed(): List<org.tenshu.inference.proxy.catalog.InstalledModelData> = throw thrown
                // ⚠ The interface's own `read`, over the `installed` above: delegation would ask `store`'s.
                override fun read(): InstalledIndex = super<ModelStore>.read()
            }
            val service = service(proxy(delivery(catalogOf(one), store = store, downloader = throwing)))
            val overAStoreThatThrows = service(proxy(delivery(catalogOf(one), store = unreadable, downloader = throwing)))

            val download = (service.downloadModel("siglip") as TResult.Failure).error as ModelUnavailable
            val delete = (service.deleteModel("siglip") as TResult.Failure).error as ModelUnavailable
            val installed = (overAStoreThatThrows.installedModels() as TResult.Failure).error as ModelUnavailable
            val deleteUnread = (overAStoreThatThrows.deleteModel("siglip") as TResult.Failure).error as ModelUnavailable

            listOf("DownloadModel" to download, "DeleteModel" to delete, "DeleteModel, index read" to deleteUnread)
                .forEach { (operation, error) ->
                    assertEquals(expected, error.reason.get(), "$operation, $what")
                    // The sentence travels: it is all a screen has to tell these apart with.
                    assertEquals(thrown.message, error.message.get(), "$operation, $what")
                }
            // ⚠ `InstalledModels` asks `ModelStore.read()`, which answers every failed read as one
            // thing: *could not be read*, with the sentence. It is a read, so the retryable bucket.
            assertEquals(ModelUnavailable.Reasons.TRANSPORT_FAILED, installed.reason.get(), "InstalledModels, $what")
            assertEquals(thrown.message, installed.message.get(), "InstalledModels, $what")
        }
    }

    private suspend fun assertRefusalCrossesTheWire(
        refusal: org.tenshu.inference.proxy.catalog.ModelStoreRefusal,
        expectedReason: String,
    ) {
        val sentence = refusal.message
        val refused = org.tenshu.inference.proxy.catalog.ModelStoreNotOwnedException(refusal)

        // Installed, so the removal gets past "not installed" and reaches the store's refusal.
        val store = InMemoryModelStore { it.decodeToString() }
        val one = entry().copy(artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100)))
        store.add(one, mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()))
        val refusing = object : ModelStore by store {
            override fun remove(modelId: String): Unit = throw refused
        }
        val throwing = object : ModelDownloader {
            override suspend fun download(modelId: String) = throw refused
            override suspend fun isDownloading(modelId: String) = false
        }
        val service = service(proxy(delivery(catalogOf(one), store = refusing, downloader = throwing)))

        val download = (service.downloadModel("siglip") as TResult.Failure).error as ModelUnavailable
        assertEquals(expectedReason, download.reason.get())
        assertEquals(sentence, download.message.get())

        val delete = (service.deleteModel("siglip") as TResult.Failure).error as ModelUnavailable
        assertEquals(expectedReason, delete.reason.get())
        assertEquals(sentence, delete.message.get())
    }

    /**
     * ⚠ A delete that reports success having found nothing hides a store that is not where the
     * caller thinks it is — the failure mode `MediaIndexer`'s empty passes had for four slices.
     */
    @Test
    fun deletingSomethingAbsentFailsRatherThanSucceedingQuietly() = runTest {
        val result = service(proxy(delivery(catalog = null))).deleteModel("siglip")

        assertEquals(
            ModelUnavailable.Reasons.NOT_INSTALLED,
            ((result as TResult.Failure).error as ModelUnavailable).reason.get(),
        )
    }

    /**
     * ⚠⚠ Deleting a model must not disable the capability, nor vice versa (IN-D10). The operations
     * are kept apart so that promise is enforceable rather than remembered: this one touches the
     * store and nothing else.
     */
    @Test
    fun deletingTouchesTheStoreAndNotConsent() = runTest {
        val store = InMemoryModelStore { it.decodeToString() }
        store.add(
            entry().copy(
                artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "p", "x", 100)),
            ),
            mapOf(ModelArtifactRole.VISION to "x".encodeToByteArray()),
        )
        val testProxy = proxy(delivery(catalog = null, store = store))

        service(testProxy).deleteModel("siglip")

        // Consent was never written, and deleting did not write it either — the capability's state
        // is exactly as untouched afterwards as before.
        assertTrue(
            !testProxy.executionContext.hasInferenceConsent(
                InferenceCapability.SEMANTIC_IMAGE_SEARCH,
                "device-a",
            )
        )
    }
}
