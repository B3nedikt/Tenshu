package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.file.File
import org.tenshu.core.TResult
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.toTBoolean
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.FakeInferenceBackend
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MQ-4 — *"photos like a sunset over water"*: the utterance reaches IN-4b's `photosLike` op and the
 * answer comes back as citations.
 *
 * ⭐⭐ Still no model. `FakeInferenceBackend` embeds text and image bytes with the same hash, so
 * retrieval is real and says nothing whatever about real photographs — the property IN-4b's fixture
 * was built for, and never a claim about quality.
 *
 * ⚠⚠ Half of these are about the op **refusing**. IN-4b made `photosLike` throw rather than return
 * an empty list so that "switched off" would not arrive looking like "no photos matched"; if Mia
 * flattened that into its generic unavailable line, the whole argument would have been undone one
 * layer above where it was won.
 */
class AskMiaVisualSearchTest {

    private val nowMs = 1_700_000_000_000L

    private fun TestProxy.grant(source: MiaSource, granted: Boolean) {
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = source.preference.id,
                value = granted.toTBoolean(tContext),
            ),
        )
    }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                RealmSetting.create(
                    context = tContext,
                    preferenceId = preference.id,
                    value = preference.adapter.toData(value, tContext),
                )
            } else {
                DeviceSetting.create(
                    context = tContext,
                    deviceId = deviceId,
                    preferenceId = preference.id,
                    value = preference.adapter.toData(value, tContext),
                )
            },
        )
    }

    /** IN-D3's two switches — the *inference* consent, which is not Mia's grant. */
    private fun TestProxy.consentToInference() {
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = MIA_TEST_DEVICE)
    }

    /**
     * Seeds the vector sink exactly as an index pass would.
     *
     * ⚠⚠ The phrase must be the planner's **residue**, not the whole utterance: the fake embeds
     * bytes, so `"photos like a sunset over water"` reaches the op as `"sunset over water"` — the
     * anchor and the article are consumed by the grammar. Seeding the utterance produced a
     * confident wrong winner rather than an error, which is what makes this worth spelling out:
     * these tests pin the residue end-to-end as a side effect of pinning retrieval.
     */
    private suspend fun TestProxy.indexPhotos(vararg phrases: String) {
        val backend = FakeInferenceBackend()
        // ⚠⚠ The photographs exist: Mia cites a visual-search hit only once its file is live (P0g).
        fileService().defaultFileSystem.createFolder("/photos")
        phrases.forEach { phrase ->
            assertTrue(
                fileService().defaultFileSystem.uploadFile("/photos", "$phrase.jpg", phrase.encodeToByteArray())
                    is TResult.Success,
                "seeding /photos/$phrase.jpg should succeed",
            )
            val embedding = backend.embedImage(phrase.encodeToByteArray())!!
            miaEmbeddings.put("/photos/$phrase.jpg", embedding.vector, embedding.producedBy)
        }
    }

    private suspend fun TestProxy.ask(utterance: String): MiaAnswer? =
        miaService().askMia(utterance, nowMs, "UTC").getOrNull()

    @Test
    fun photosLikeADescriptionComeBackRankedAndCited() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply {
            consentToInference()
            // ⚠ Seeded so the expected hit is NOT first in insertion order — otherwise an
            // implementation that ranked nothing at all would pass this test.
            indexPhotos("receipt", "dog", "sunset over water")
        }

        val answer = proxy.ask("photos like a sunset over water")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer?.verdict?.get())
        val hits = answer?.hits?.toList().orEmpty().filterIsInstance<MiaHit>()
        assertEquals("/photos/sunset over water.jpg", hits.first().targetKey)
        assertEquals(File.ID, hits.first().targetType)
    }

    @Test
    fun theExplanationSaysLookLikeRatherThanOf() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply {
            consentToInference()
            indexPhotos("sunset over water")
        }

        val answer = proxy.ask("photos like a sunset over water")

        // ⚠⚠ MQ-D3: the plan is what the user is shown. Ranked guesses described as "photos of X"
        // would promise rows carrying the word they typed, which is a different — and checkable —
        // claim.
        assertTrue(
            answer?.explanation?.get().orEmpty().contains("look like"),
            "explanation was: ${answer?.explanation?.get()}",
        )
    }

    @Test
    fun ofStillMeansLabelsAndLikeStillMeansVectors() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply {
            consentToInference()
            indexPhotos("sunset over water")
        }

        // ⚠ Two prepositions, two intents, two grants. "of" is IN-4a's label search and finds
        // nothing here because no label rows were written — and it must NOT silently fall through
        // to the vector search, which would make the explanation describe the wrong thing.
        val ofAnswer = proxy.ask("photos of a sunset over water")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, ofAnswer?.verdict?.get())
        assertEquals(0, ofAnswer?.hits?.toList().orEmpty().size, "label search finds no rows")
    }

    @Test
    fun revokingVisualSearchRefusesByName() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply {
            consentToInference()
            indexPhotos("sunset over water")
            grant(MiaSource.VISUAL_SEARCH, granted = false)
        }

        val answer = proxy.ask("photos like a sunset over water")

        // ⚠ Named, not a generic refusal: "I can't answer that" is indistinguishable from not
        // understanding, which is exactly what MQ-D4's no-match verdict keeps separate.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer?.verdict?.get())
        assertTrue(
            answer?.explanation?.get().orEmpty().contains("look like"),
            "should name the source: ${answer?.explanation?.get()}",
        )
    }

    @Test
    fun revokingVisualSearchLeavesLabelSearchWorking() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply {
            consentToInference()
            indexPhotos("sunset over water")
            grant(MiaSource.VISUAL_SEARCH, granted = false)
        }

        // ⚠⚠ The whole reason this is a THIRD grant. Both sources sit on `InferenceService`, so if
        // the two were folded together — or if the validator separated them by service alone —
        // revoking one would take the other with it and the switch would be a lie.
        val answer = proxy.ask("photos of a cat")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer?.verdict?.get())
    }

    @Test
    fun withoutInferenceConsentTheAnswerNamesTheSwitch() = runTest {
        // Mia's grant is on; IN-D3's inference consent is NOT. Two different permissions.
        val proxy = createMiaProxy(backgroundScope).apply { indexPhotos("sunset over water") }

        val answer = proxy.ask("photos like a sunset over water")

        assertEquals(MiaAnswer.VERDICT_UNAVAILABLE, answer?.verdict?.get())
        assertTrue(
            answer?.explanation?.get().orEmpty().contains("switched off"),
            "should carry IN-4b's NO_CONSENT reason, not the generic line: " +
                "${answer?.explanation?.get()}",
        )
    }

    @Test
    fun theConsentRefusalDoesNotSendTheUserToMiasSettings() = runTest {
        // ⚠⚠ **The §14.2 guard, and the reason the test above was not enough.** This sentence used
        // to end "— you can turn it on in Settings", which on a device sends the user to Mia's
        // Settings, where "Photo search by appearance" is **already on** (it is granted by default,
        // like every read source). The switch that is actually off is IN-D3's inference consent —
        // a different permission, which lives in Control's Settings → Models (§32).
        //
        // ⚠ So the refusal says the permission is separate and names the switch that is off, by the
        // label Control gives it.
        val proxy = createMiaProxy(backgroundScope).apply {
            indexPhotos("sunset over water")
            grant(MiaSource.VISUAL_SEARCH, granted = true)
        }

        val explanation = proxy.ask("photos like a sunset over water")?.explanation?.get().orEmpty()

        assertTrue(
            !explanation.contains("in Settings", ignoreCase = true),
            "must not point at a switch that is already on: $explanation",
        )
        assertTrue(
            explanation.contains("separate permission"),
            "should say the permission is not Mia's own: $explanation",
        )
        assertTrue(
            explanation.contains("Search photos by description"),
            "should name the switch that is off, as Control labels it: $explanation",
        )
    }

    @Test
    fun anUnindexedLibraryTellsTheUserToWaitRatherThanThatNothingMatched() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply { consentToInference() }

        val answer = proxy.ask("photos like a sunset over water")

        // ⚠⚠ The failure IN-4b exists to prevent, checked at the layer the user actually reads.
        // "No photos matched" here would be a lie about a library nothing has looked at yet.
        assertEquals(MiaAnswer.VERDICT_UNAVAILABLE, answer?.verdict?.get())
        assertTrue(
            answer?.explanation?.get().orEmpty().contains("haven't looked through"),
            "should carry NOT_INDEXED: ${answer?.explanation?.get()}",
        )
    }

    @Test
    fun aRefusalCitesNothing() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply { consentToInference() }

        val answer = proxy.ask("photos like a sunset over water")

        // ⚠ MQ-D1: citations are the evidence for an answer, so an answer that could not run must
        // not carry any.
        assertEquals(0, answer?.hits?.toList().orEmpty().size)
    }
}
