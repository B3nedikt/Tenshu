package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.Entity
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The consent gate and the backend registry (IN-1) — everything IN-1 ships that has behaviour.
 *
 * ⚠ There is no model here and there is not meant to be: the point of the slice is that the whole
 * consumer stack can be built and tested with **zero models and zero hardware**.
 */
class InferenceConsentTest {

    private val thisDevice = "device-a"

    private fun proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply { registerConfig(preferenceServiceCommonModule) },
    )

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

    // ---- consent ---------------------------------------------------------------

    @Test
    fun withNoRowsAtAllNothingIsConsented() {
        val proxy = proxy()

        // ⚠⚠ Absence means NO here — the opposite of Mia's read grants, and deliberately. Mia
        // defaults on because reading was already the shipped behaviour; nothing here has ever
        // run, so silence means "the user has not asked for this".
        InferenceCapability.entries.forEach {
            assertFalse(proxy.executionContext.hasInferenceConsent(it, thisDevice), it.id)
        }
    }

    @Test
    fun bothSwitchesAreRequired() {
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        val proxy = proxy()

        proxy.write(capability.realmConsent, true)
        assertFalse(
            proxy.executionContext.hasInferenceConsent(capability, thisDevice),
            "the realm may be indexable while THIS device is not the one to do it",
        )

        proxy.write(capability.deviceConsent, true, deviceId = thisDevice)
        assertTrue(proxy.executionContext.hasInferenceConsent(capability, thisDevice))
    }

    @Test
    fun revokingTheRealmSwitchStopsEveryDevice() {
        val capability = InferenceCapability.TEXT_IN_IMAGES
        val proxy = proxy()
        proxy.write(capability.realmConsent, true)
        proxy.write(capability.deviceConsent, true, deviceId = thisDevice)
        assertTrue(proxy.executionContext.hasInferenceConsent(capability, thisDevice))

        proxy.write(capability.realmConsent, false)

        // ⚠ The realm answer is the one that follows the user around; a device-level yes cannot
        // outvote it.
        assertFalse(proxy.executionContext.hasInferenceConsent(capability, thisDevice))
    }

    @Test
    fun anotherDevicesConsentIsNotThisDevices() {
        val capability = InferenceCapability.TEXT_IN_IMAGES
        val proxy = proxy()
        proxy.write(capability.realmConsent, true)
        proxy.write(capability.deviceConsent, true, deviceId = "device-b")

        // ⚠⚠ The bug this test exists for: a `DeviceSetting`'s key is "$deviceId/$preferenceId",
        // so a read that ignores the device half finds either everyone's row or nobody's. Here the
        // plugged-in hub said yes; the phone on battery did not.
        assertFalse(proxy.executionContext.hasInferenceConsent(capability, thisDevice))
        assertTrue(proxy.executionContext.hasInferenceConsent(capability, "device-b"))
    }

    @Test
    fun consentIsPerCapability() {
        val proxy = proxy()
        proxy.write(InferenceCapability.TEXT_IN_IMAGES.realmConsent, true)
        proxy.write(InferenceCapability.TEXT_IN_IMAGES.deviceConsent, true, deviceId = thisDevice)

        // ⚠⚠ Never a blanket "AI" switch (IN-D3): saying yes to finding text in screenshots is not
        // saying yes to clustering the faces of everyone in the library.
        assertTrue(proxy.executionContext.hasInferenceConsent(InferenceCapability.TEXT_IN_IMAGES, thisDevice))
        assertFalse(
            proxy.executionContext.hasInferenceConsent(InferenceCapability.FACE_CLUSTERING, thisDevice),
        )
    }

    @Test
    fun consentIsReReadRatherThanCached() {
        val capability = InferenceCapability.TEXT_IN_IMAGES
        val proxy = proxy()
        proxy.write(capability.realmConsent, true)
        proxy.write(capability.deviceConsent, true, deviceId = thisDevice)
        assertTrue(proxy.executionContext.hasInferenceConsent(capability, thisDevice))

        proxy.write(capability.deviceConsent, false, deviceId = thisDevice)

        // ⚠ An indexing pass that cached this at the top would keep grinding through a library the
        // user just switched off.
        assertFalse(proxy.executionContext.hasInferenceConsent(capability, thisDevice))
    }

    // ---- the registry ----------------------------------------------------------

    @Test
    fun withNoBackendNothingIsAvailable() {
        // ⚠⚠ The production state until IN-2 lands a model. "Nothing can do this here" is an
        // ordinary answer, not an error — nobody throws.
        assertNull(InferenceBackendRegistry.Empty.backendFor(InferenceCapability.TEXT_IN_IMAGES))
        assertEquals(emptySet(), InferenceBackendRegistry.Empty.available)
    }

    @Test
    fun theFirstBackendThatSupportsACapabilityWins() {
        val specialised = FakeInferenceBackend(capabilities = setOf(InferenceCapability.TEXT_IN_IMAGES))
        val general = FakeInferenceBackend()
        val registry = InferenceBackendRegistry(listOf(specialised, general))

        // ⚠ Order matters: a platform binding a specialised backend (iOS Vision for OCR) registers
        // it before the general one.
        assertEquals(specialised, registry.backendFor(InferenceCapability.TEXT_IN_IMAGES))
        assertEquals(general, registry.backendFor(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
    }

    @Test
    fun consentAndCapabilityAreJoinedOnlyAtTheEnd() = runTest {
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        val proxy = proxy()
        val registry = InferenceBackendRegistry(listOf(FakeInferenceBackend()))

        // Consented, nothing to run it: the honest answer is not "permitted".
        assertEquals(
            emptySet(),
            proxy.executionContext.permittedInference(InferenceBackendRegistry.Empty, thisDevice),
        )
        // Able, not consented: also not permitted — and a caller that conflated the two would tell
        // the user the wrong one of "you said no" and "we cannot".
        assertEquals(
            emptySet(),
            proxy.executionContext.permittedInference(registry, thisDevice),
        )

        proxy.write(capability.realmConsent, true)
        proxy.write(capability.deviceConsent, true, deviceId = thisDevice)

        assertEquals(
            setOf(capability),
            proxy.executionContext.permittedInference(registry, thisDevice),
        )
    }

    // ---- the fake --------------------------------------------------------------

    @Test
    fun theFakeEmbedsDeterministicallyFromTheBytes() = runTest {
        val backend = FakeInferenceBackend()
        val photo = byteArrayOf(1, 2, 3, 4)

        val first = backend.embedImage(photo)
        val again = backend.embedImage(byteArrayOf(1, 2, 3, 4))
        val other = backend.embedImage(byteArrayOf(9, 9, 9, 9))

        // ⚠⚠ The only property of an embedding a consumer depends on: the same photo embeds the
        // same way and a different one does not. A random vector would make every
        // nearest-neighbour test meaningless while looking like it worked.
        assertEquals(first, again)
        assertNotNull(other)
        assertTrue(first != other)
    }

    @Test
    fun everythingTheFakeProducesIsStampedAsFake() = runTest {
        val embedding = assertNotNull(FakeInferenceBackend().embedImage(byteArrayOf(1)))

        // ⚠⚠ A row the fake wrote must be recognisable in production data forever.
        assertEquals(ModelProvenance.Fake, embedding.producedBy)
    }

    @Test
    fun aCapabilityTheFakeDoesNotHaveAnswersEmpty() = runTest {
        val backend = FakeInferenceBackend(capabilities = setOf(InferenceCapability.TEXT_IN_IMAGES))

        assertNull(backend.embedImage(byteArrayOf(1)))
        assertEquals(emptyList(), backend.tagImage(byteArrayOf(1), vocabulary = emptyList()))
        assertTrue(backend.recognizeText(byteArrayOf(1)).isNotEmpty())
    }

    @Test
    fun theFakeRespectsTheCallersVocabulary() = runTest {
        val backend = FakeInferenceBackend(tags = listOf(ImageTag("cat", 0.9f), ImageTag("dog", 0.4f)))

        // ⚠ A consumer that passes a vocabulary and gets labels outside it would pass its own tests
        // and then fail against a real model.
        assertEquals(listOf("cat"), backend.tagImage(byteArrayOf(1), listOf("cat", "receipt")).map { it.label })
    }
}
