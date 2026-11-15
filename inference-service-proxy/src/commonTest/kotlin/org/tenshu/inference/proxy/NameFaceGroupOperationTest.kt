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
import org.tenshu.inference.nameFaceGroup
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
 * `nameFaceGroup` end-to-end (IN-6 F3b) — the one operation in this feature that writes realm data.
 *
 * ⚠⚠ Everything else IN-6 holds is device-local by construction (IN-D5). These rows sync, so what
 * these tests pin is the shape of the only thing that leaves the device: a path, a typed string,
 * and a stamp saying a person rather than a model produced it.
 */
class NameFaceGroupOperationTest {

    private val thisDevice = "device-a"
    private val capability = InferenceCapability.FACE_CLUSTERING

    private fun proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        implementationFactoryOverride = { id, _ ->
            // ⚠ No `faceGrouping`, deliberately: naming must work on a device with no templates and
            // no model, or a name typed on a phone could not be corrected from a laptop.
            inferenceServiceProxyImplementationFactory(
                id = id,
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                embeddings = InMemoryEmbeddingStore(),
                deviceId = thisDevice,
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
    fun namingWritesOneLabelPerPhotograph() = runTest {
        val proxy = proxy().apply { consent() }

        service(proxy).nameFaceGroup(listOf("/photos/a.jpg", "/photos/b.jpg"), "Anna")

        assertTrue(proxy.storedLabel("/photos/a.jpg", "Anna") != null)
        assertTrue(proxy.storedLabel("/photos/b.jpg", "Anna") != null)
    }

    @Test
    fun theNameIsStampedAsUserAuthoredRatherThanAsAModelOpinion() = runTest {
        val proxy = proxy().apply { consent() }

        service(proxy).nameFaceGroup(listOf("/photos/a.jpg"), "Anna")

        // ⚠⚠ The stamp is the durable distinction. A name written with a MODEL's provenance is one
        // a later re-index is entitled to overwrite; `ModelProvenance.User` is what makes it not.
        assertEquals(ModelProvenance.User.asString(), proxy.provenanceOf("/photos/a.jpg", "Anna"))
    }

    @Test
    fun aSecondPersonInTheSameFrameDoesNotDisturbTheFirst() = runTest {
        val proxy = proxy().apply { consent() }
        val service = service(proxy)

        service.nameFaceGroup(listOf("/photos/both.jpg"), "Anna")
        service.nameFaceGroup(listOf("/photos/both.jpg"), "Bob")

        // ⚠⚠ Two people in one frame is the ordinary case, not the corner one. An implementation
        // that cleared the path's face labels before writing — the obvious way to make renaming
        // work — would silently delete Anna when Bob is named.
        assertTrue(proxy.storedLabel("/photos/both.jpg", "Anna") != null, "Anna survived")
        assertTrue(proxy.storedLabel("/photos/both.jpg", "Bob") != null, "Bob was written")
    }

    @Test
    fun namingTheSameGroupTwiceFoldsRatherThanPilingUp() = runTest {
        val proxy = proxy().apply { consent() }
        val service = service(proxy)

        service.nameFaceGroup(listOf("/photos/a.jpg"), "Anna")
        service.nameFaceGroup(listOf("/photos/a.jpg", "/photos/a.jpg"), "Anna")

        // `labelKey` is `"$file/$capability/$label"`, so the row is the same row. Re-naming after
        // adding a photograph must not double anything.
        assertTrue(proxy.storedLabel("/photos/a.jpg", "Anna") != null)
    }

    @Test
    fun withoutConsentItRefusesRatherThanWritingQuietly() = runTest {
        val proxy = proxy() // ⚠ no consent written

        val result = service(proxy).nameFaceGroup(listOf("/photos/a.jpg"), "Anna")

        // ⚠⚠ The stale-client window this guards: consent can be withdrawn while a screen full of
        // faces is still open, and a write arriving afterwards must not create the very data the
        // switch was just used to stop.
        val failure = result as? TResult.Failure ?: error("expected a refusal, got $result")
        assertEquals(
            FaceGroupsUnavailable.Reasons.NO_CONSENT,
            (failure.error as FaceGroupsUnavailable).reason.get(),
        )
        assertNull(proxy.storedLabel("/photos/a.jpg", "Anna"))
    }

    @Test
    fun aBlankNameWritesNothingAndClaimsNothing() = runTest {
        val proxy = proxy().apply { consent() }

        service(proxy).nameFaceGroup(listOf("/photos/a.jpg"), "   ")

        // ⚠ Not a refusal: "you sent me no name" is a caller mistake, not a fact about this
        // realm's switches. Reporting it as NO_CONSENT would send someone to a setting already on.
        assertNull(proxy.storedLabel("/photos/a.jpg", "   "))
        assertNull(proxy.storedLabel("/photos/a.jpg", ""))
    }

    @Test
    fun namingNoPhotographsWritesNothing() = runTest {
        val proxy = proxy().apply { consent() }

        val result = service(proxy).nameFaceGroup(emptyList(), "Anna")

        assertTrue(result is TResult.Success, "an empty list is a no-op, not a failure")
    }

    @Test
    fun theNameIsTrimmedSoTwoSpellingsDoNotBecomeTwoPeople() = runTest {
        val proxy = proxy().apply { consent() }

        service(proxy).nameFaceGroup(listOf("/photos/a.jpg"), "  Anna  ")

        // ⚠ `labelKey` includes the label, so an untrimmed name is a DIFFERENT person forever —
        // and People would list both.
        assertTrue(proxy.storedLabel("/photos/a.jpg", "Anna") != null)
        assertNull(proxy.storedLabel("/photos/a.jpg", "  Anna  "))
    }
}
