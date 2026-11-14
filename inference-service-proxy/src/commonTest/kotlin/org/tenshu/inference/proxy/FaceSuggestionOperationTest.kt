package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InferenceService
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.faceSuggestionRejections
import org.tenshu.inference.rejectFaceSuggestion
import org.tenshu.core.collection.list.toList
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.core.TResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device-local "No" to *"Is this Anna?"* end-to-end (IN-6 F6c name suggestions): through the
 * real client, the op and the template store's rejections.
 *
 * ⚠⚠ The half that matters is what it does NOT do: no realm row (D2), and nothing for a
 * withdrawn switch.
 */
class FaceSuggestionOperationTest {

    private val faces = InMemoryFaceTemplateStore()

    /** Templates for the photographs these tests answer about — a rejection is held only beside one. */
    private suspend fun indexed(vararg paths: String) = paths.forEach { path ->
        faces.put(path, listOf(StoredFace(FaceBox(0.1f, 0.2f, 0.3f, 0.4f, 0.9f), floatArrayOf(1f, 0f))), ModelProvenance("fake", 1))
    }

    /** When set, `rejections()` throws as an unreadable file does. */
    private var unreadable = false

    private val store: FaceTemplateStore = object : FaceTemplateStore by faces {
        override suspend fun rejections(): List<FaceRejection> =
            if (unreadable) throw RejectionsUnreadable("/r", IllegalStateException("garbled")) else faces.rejections()
    }

    private val thisDevice = "device-a"
    private val capability = InferenceCapability.FACE_CLUSTERING

    private fun proxy(gate: (suspend () -> Boolean)? = null): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        implementationFactoryOverride = { id, _ ->
            inferenceServiceProxyImplementationFactory(
                id = id,
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                embeddings = InMemoryEmbeddingStore(),
                deviceId = thisDevice,
                faceGrouping = FaceGroupingComponents(store = store, clustering = FaceClustering.Measured),
                storeGate = gate,
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

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                Entity.create<RealmSetting>(
                    context = tContext,
                    composite = RealmSetting.create(
                        context = tContext,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            } else {
                Entity.create<DeviceSetting>(
                    context = tContext,
                    composite = DeviceSetting.create(
                        context = tContext,
                        deviceId = deviceId,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            },
        )
    }

    private fun TestProxy.consent() {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    /** The stored row for this (path, name), or null when nothing was written. */
    private fun TestProxy.storedLabel(filePath: String, name: String): Any? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = tContext,
                type = MediaLabel.ID,
                keyValue = MediaLabel.labelKey(filePath, capability, name),
            ),
        )

    private fun TestProxy.provenanceOf(filePath: String, name: String): String? =
        when (val stored = storedLabel(filePath, name)) {
            is Entity<*> -> runCatching {
                stored.getFieldValue<TString>(MediaLabel.Fields.PRODUCED_BY)
            }.getOrNull()?.get()
            is Composite -> runCatching {
                stored.getField<TString>(MediaLabel.Fields.PRODUCED_BY)
            }.getOrNull()?.get()
            else -> null
        }

    @Test
    fun aNoIsRecordedOnThisDeviceAndReadBack() = runTest {
        indexed("/photos/x.jpg")
        val proxy = proxy().apply { consent() }
        val service = service(proxy)

        val wrote = service.rejectFaceSuggestion("Anna", listOf("/photos/x.jpg"), listOf(100, 200, 300, 400))

        assertEquals(1L, (wrote as TResult.Success).value?.get())
        val read = (service.faceSuggestionRejections() as TResult.Success).value?.toList().orEmpty()
        assertEquals(listOf("/photos/x.jpg" to "Anna"), read.map { it.filePath.get() to it.name.get() })
        assertEquals(listOf(100L, 200L, 300L, 400L), read.single().let {
            listOf(it.boxLeftPerMille.get(), it.boxTopPerMille.get(), it.boxRightPerMille.get(), it.boxBottomPerMille.get())
        })
        // ⚠⚠ D2: the "No" is NOT a label — nothing in the realm says anything about this face.
        assertNull(proxy.storedLabel("/photos/x.jpg", "Anna"))
        assertNull(proxy.storedLabel("/photos/x.jpg", "not:Anna"))
    }

    @Test
    fun withoutConsentNeitherWritesNorDiscloses() = runTest {
        val proxy = proxy() // ⚠ no consent
        val service = service(proxy)
        indexed("/photos/x.jpg", "/photos/y.jpg")
        faces.reject(listOf(FaceRejection("/photos/x.jpg", "Anna", 1, 2, 3, 4)))

        val write = service.rejectFaceSuggestion("Bob", listOf("/photos/y.jpg"), listOf(1, 2, 3, 4))
        val read = service.faceSuggestionRejections()

        for (result in listOf(write, read)) {
            val failure = result as? TResult.Failure ?: error("expected a refusal, got $result")
            assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, (failure.error as FaceGroupsUnavailable).reason.get())
        }
        assertEquals(1, faces.rejections().size, "the refused write wrote nothing")
    }

    @Test
    fun anUnreadableListIsAFailureNeverAnEmptyAnswer() = runTest {
        val proxy = proxy().apply { consent() }
        unreadable = true

        // ⚠⚠ An empty success would read as "nothing was ever rejected" and bring every No back.
        assertTrue(service(proxy).faceSuggestionRejections() is TResult.Failure, "an unreadable file is a refusal")
    }

    @Test
    fun aClosedStoreGateRefusesTheWriteAsItRefusesTheRead() = runTest {
        indexed("/photos/x.jpg")
        val proxy = proxy(gate = { false }).apply { consent() }
        val service = service(proxy)

        val write = service.rejectFaceSuggestion("Anna", listOf("/photos/x.jpg"), listOf(1, 2, 3, 4))

        val failure = write as? TResult.Failure ?: error("expected a refusal, got $write")
        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, (failure.error as FaceGroupsUnavailable).reason.get())
        assertTrue(faces.rejections().isEmpty())
    }

    @Test
    fun aMalformedNoWritesNothing() = runTest {
        indexed("/photos/x.jpg")
        val proxy = proxy().apply { consent() }
        val service = service(proxy)

        val results = listOf(
            service.rejectFaceSuggestion("Anna", listOf("/photos/x.jpg"), listOf(1, 2, 3)), // three edges
            service.rejectFaceSuggestion("  ", listOf("/photos/x.jpg"), listOf(1, 2, 3, 4)),
            service.rejectFaceSuggestion("Anna", listOf("/photos/x.jpg"), listOf(1, 2, 3, 4000)),
        )

        // ⚠ A caller mistake is answered — zero, not a failure the screen would call "not saved"
        // for a reason no retry fixes, and not a crash.
        results.forEach { assertEquals(0L, (it as? TResult.Success)?.value?.get(), "$it") }
        assertTrue(faces.rejections().isEmpty())
    }
}
