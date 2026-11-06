package org.tenshu.inference

import org.tenshu.preference.PreferenceScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The consent shape (IN-1, IN-D3) and the provenance stamp (IN-D5) — the whole of IN-1's domain. */
class InferenceCapabilityTest {

    @Test
    fun everyCapabilityIsOffUntilItIsAskedFor() {
        // ⚠⚠ Deliberately NOT Mia's call: Mia defaults its read grants on because reading was
        // already the shipped behaviour. Nothing here has ever run, so silence means "not asked
        // for" — inferring over a library on a missing row would be helping ourselves.
        InferenceCapability.entries.forEach {
            assertFalse(it.realmConsent.default, "${it.id} realm default")
            assertFalse(it.deviceConsent.default, "${it.id} device default")
        }
    }

    @Test
    fun thereAreTwoSwitchesAndTheyHaveDifferentScopes() {
        // ⚠ "May this realm be indexed at all" follows the user to a new device; "may THIS device
        // do the work" cannot — a phone on battery and a plugged-in hub are different answers.
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH

        assertEquals(PreferenceScope.REALM, capability.realmConsent.scope)
        assertEquals(PreferenceScope.DEVICE, capability.deviceConsent.scope)
    }

    @Test
    fun faceClusteringIsNeverOfferedInOnboarding() {
        // ⚠⚠ IN-D3, as a property of the capability rather than a convention a screen might
        // forget: an onboarding flow that asks is an onboarding flow that pressures, and a feature
        // that is fine when chosen becomes a different thing when it is the default.
        assertFalse(InferenceCapability.FACE_CLUSTERING.offerableInOnboarding)
        assertTrue(InferenceCapability.FACE_CLUSTERING !in InferenceCapability.offerable)
        assertTrue(InferenceCapability.TEXT_IN_IMAGES in InferenceCapability.offerable)
    }

    @Test
    fun preferenceIdsAreDistinctAcrossCapabilitiesAndScopes() {
        // A collision would silently make two capabilities one switch — and the first anyone would
        // know is a face-clustering toggle turning OCR on.
        val ids = InferenceCapability.entries.flatMap { listOf(it.realmConsent.id, it.deviceConsent.id) }

        assertEquals(ids.size, ids.toSet().size, ids.toString())
    }

    @Test
    fun provenanceRoundTrips() {
        val stamp = ModelProvenance("siglip-base-patch16", modelVersion = 3, labelSetVersion = 7)

        assertEquals(stamp, ModelProvenance.parse(stamp.asString()))
    }

    @Test
    fun anUnreadableStampIsNullRatherThanAThrow() {
        // ⚠ The string is read back from rows written by OLDER versions of us. Unreadable means
        // "re-derive", never "crash the indexer".
        assertNull(ModelProvenance.parse(null))
        assertNull(ModelProvenance.parse(""))
        assertNull(ModelProvenance.parse("nonsense"))
        assertNull(ModelProvenance.parse("model@notanumber/0"))
    }

    @Test
    fun aNewerModelOrVocabularyMakesARowStale() {
        val current = ModelProvenance("siglip", modelVersion = 3, labelSetVersion = 2)

        assertTrue(ModelProvenance("siglip", 2, 2).isStaleAgainst(current), "older weights")
        // ⭐ IN-D9's "the taxonomy is data, not weights": adding a label next year re-runs only the
        // rows whose label set is behind.
        assertTrue(ModelProvenance("siglip", 3, 1).isStaleAgainst(current), "older vocabulary")
        assertTrue(ModelProvenance("other", 3, 2).isStaleAgainst(current), "a different model")
        assertFalse(ModelProvenance("siglip", 3, 2).isStaleAgainst(current))
        // ⚠ A row from a NEWER model is not stale — a second device may simply be ahead, and
        // re-deriving it here would start a ping-pong between two devices that never converges.
        assertFalse(ModelProvenance("siglip", 4, 2).isStaleAgainst(current))
    }

    @Test
    fun theFakeStampIsRecognisableForever() {
        // ⚠⚠ A row the fake wrote must be identifiable in production data. A fixture that stamps
        // like the real thing is a fixture that can contaminate a store.
        assertEquals("fake", ModelProvenance.Fake.modelId)
        assertTrue(ModelProvenance.Fake.isStaleAgainst(ModelProvenance("siglip", 1)))
    }
}
