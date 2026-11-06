package org.tenshu.inference.proxy.vocabulary

import org.tenshu.inference.proxy.ImageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * IN-D9's tiered rule (Phase 2, §19.3 / the 2026-08-14 amendment) — the only thing deciding what
 * becomes a `MediaLabel` row.
 *
 * ⚠ Pure, so every branch is testable with no model, no library and no labelled sample. What a
 * labelled sample is still needed for is the **numbers**; this suite pins the **rule**.
 */
class LabelSelectionTest {

    /**
     * `indoors` is the parent; `staircase` and `gym` are its leaves. The numbers are the measured
     * ones from `models.md` §3.7.5 wherever a case has a real counterpart.
     */
    private val vocab = vocabularyOf(
        version = 1,
        labels = listOf(
            VocabularyLabel("indoors", LabelTier.TOP, floor = 0.04f),
            VocabularyLabel("dog", LabelTier.TOP, floor = 0.10f),
            VocabularyLabel("staircase", LabelTier.LEAF, parent = "indoors", floor = 0.08f, margin = 0.02f),
            VocabularyLabel("gym", LabelTier.LEAF, parent = "indoors", floor = 0.08f, margin = 0.02f),
        ),
    )

    private fun select(vararg tags: Pair<String, Float>, vocabulary: LabelVocabulary = vocab) =
        selectLabels(tags.map { ImageTag(it.first, it.second) }, vocabulary)
            .associate { it.label to it.confidence }

    // ─── Top tier: independent thresholds, not mutually exclusive.

    @Test
    fun aTopLabelClearingItsFloorIsWritten() {
        assertEquals(setOf("dog"), select("dog" to 0.4f).keys)
    }

    @Test
    fun aTopLabelBelowItsFloorIsDropped() {
        assertTrue(select("dog" to 0.05f).isEmpty())
    }

    /** ⚠ Not mutually exclusive — a photo really can be both, and both must survive. */
    @Test
    fun twoTopLabelsCanBothFire() {
        assertEquals(setOf("indoors", "dog"), select("indoors" to 0.3f, "dog" to 0.4f).keys)
    }

    // ─── Leaves: floor, then margin, then parent.

    @Test
    fun aLeafClearingBothItsFloorAndItsMarginWins() {
        val got = select("indoors" to 0.3f, "staircase" to 0.5f, "gym" to 0.1f)

        assertEquals(setOf("indoors", "staircase"), got.keys)
    }

    /**
     * ⚠⚠ **The amendment, and the case that motivated it.** The best sibling is *negative* while
     * the parent is positive — measured as `indoors 0.061` winning with `staircase −0.009`. A
     * purely relative rule emits `staircase` here with a *clear* margin, which reads as confidence
     * and is exactly wrong. The floor runs first, so the answer degrades to the parent.
     */
    @Test
    fun aWinningLeafBelowItsFloorDegradesToTheParentEvenWithAClearMargin() {
        val got = select("indoors" to 0.061f, "staircase" to -0.009f, "gym" to -0.4f)

        assertEquals(setOf("indoors"), got.keys)
        assertEquals(0.061f, got.getValue("indoors"))
    }

    /** ⚠ The other failure: above the floor, but too close to call. Degrade rather than guess. */
    @Test
    fun aLeafThatClearsItsFloorButNotItsMarginDegradesToTheParent() {
        val got = select("indoors" to 0.3f, "staircase" to 0.20f, "gym" to 0.19f)

        assertEquals(setOf("indoors"), got.keys)
    }

    /** ⚠ A single sibling has nothing to be close to, so only the floor applies. */
    @Test
    fun aLoneSiblingIsJudgedOnItsFloorAlone() {
        assertEquals(setOf("indoors", "staircase"), select("indoors" to 0.3f, "staircase" to 0.5f).keys)
    }

    /**
     * ⚠⚠ **Degrade, never invent.** A leaf failing its floor is not evidence *for* the parent — if
     * the parent did not earn its own place, nothing is written. Promoting an unearned parent would
     * smuggle back the confidence the floor just refused.
     */
    @Test
    fun aFailingLeafDoesNotPromoteAParentThatDidNotEarnItsOwnPlace() {
        val got = select("indoors" to 0.01f, "staircase" to -0.2f, "gym" to -0.3f)

        assertTrue(got.isEmpty(), "nothing earned a place, got $got")
    }

    // ─── The vocabulary is the allowlist.

    /** ⚠ The model may know thousands of terms; the ones we write are chosen, not inherited. */
    @Test
    fun aLabelOutsideTheVocabularyIsNeverWritten() {
        assertTrue(select("hamster" to 0.99f).isEmpty())
    }

    /**
     * ⚠⚠ The production value today. An uncalibrated label is not better than no label — §19.3's
     * trap is precisely a plausible constant invented by a consumer.
     */
    @Test
    fun anEmptyVocabularyEmitsNothingHoweverConfidentTheModelIs() {
        val got = select("dog" to 0.99f, "indoors" to 0.99f, vocabulary = LabelVocabulary.Empty)

        assertTrue(got.isEmpty())
    }

    // ─── Author errors fail at construction, not per photograph.

    @Test
    fun aLeafWithNoParentIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            vocabularyOf(1, listOf(VocabularyLabel("corgi", LabelTier.LEAF, floor = 0.1f, margin = 0.02f)))
        }
    }

    @Test
    fun aLeafNamingAMissingParentIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            vocabularyOf(
                1,
                listOf(VocabularyLabel("corgi", LabelTier.LEAF, parent = "dog", floor = 0.1f, margin = 0.02f)),
            )
        }
    }

    @Test
    fun aDuplicatedLabelIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            vocabularyOf(
                1,
                listOf(
                    VocabularyLabel("dog", LabelTier.TOP, floor = 0.1f),
                    VocabularyLabel("dog", LabelTier.TOP, floor = 0.2f),
                ),
            )
        }
    }

    /** ⚠ `terms` is what the model is asked about — every label, whatever its tier. */
    @Test
    fun everyLabelIsOfferedToTheModel() {
        assertEquals(listOf("indoors", "dog", "staircase", "gym"), vocab.terms)
    }
}
