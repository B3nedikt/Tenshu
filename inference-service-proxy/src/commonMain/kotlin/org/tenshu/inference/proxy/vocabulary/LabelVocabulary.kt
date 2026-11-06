package org.tenshu.inference.proxy.vocabulary

/**
 * Where a label sits in IN-D9's shallow hierarchy.
 *
 * ⚠⚠ The tier is not decoration — it selects a **different rule**. Top-tier labels are not mutually
 * exclusive ("a photo can be both *beach* and *dog*") so each carries an independent threshold;
 * leaves are mutually confusable, so independent thresholds either fire several at once or none,
 * and they compete within their sibling set instead.
 */
enum class LabelTier {
    /** An independent question — "is there a dog here at all?" */
    TOP,

    /** One of a mutually confusable sibling set — a breed, a room, a species. */
    LEAF,
}

/**
 * One label in the vocabulary (IN-D9).
 *
 * ⚠⚠ **Every threshold is required.** There is no default, and that is the point: §19.3's trap is a
 * consumer inventing a plausible constant, and `photos-app.md` §24.3 is the worked example — a
 * confidence floor of 0.500 sounds cautious and would have labelled **nothing at all**, because no
 * (image, label) pair in a real library reaches p = 0.5 (`models.md` §3.7.2). A label that has not
 * been calibrated cannot be expressed here, so it cannot be emitted by accident.
 *
 * ⚠ The scale is the model's own: SigLIP scores `sigmoid(111.00·cos − 12.89)`, read from its
 * weights (§3.7). What a labelled sample is still needed for is these per-label cut-offs, not the
 * scale — so the numbers below are *data to be measured*, never constants to be reasoned out.
 */
data class VocabularyLabel(
    /** The stable label text the model is asked about, and the text a `MediaLabel` row carries. */
    val label: String,
    val tier: LabelTier,
    /**
     * The parent to fall back to when a leaf cannot claim itself. ⚠ Required for [LabelTier.LEAF]
     * and meaningless for [LabelTier.TOP]; [vocabularyOf] rejects a leaf without one.
     */
    val parent: String? = null,
    /**
     * The **absolute floor** this label must clear on its own (IN-D9's 2026-08-14 amendment).
     *
     * ⚠⚠ For a leaf this runs **before** the margin test, and it is the step that stops the
     * vocabulary inventing detail. Run against a real library the best sibling is routinely
     * *negative* while the parent is positive — `indoors 0.061` winning with `staircase −0.009` —
     * and a purely relative rule cannot tell "this is a bedroom, not a living room" from "the model
     * rejects all fourteen of these and this one is rejected least". Worse, a *clear* margin
     * between two confidently-wrong siblings reads as confidence, so the relative rule alone is
     * most wrong exactly where it feels most certain.
     */
    val floor: Float,
    /**
     * How far a winning leaf must beat its nearest sibling by. ⚠ Required for [LabelTier.LEAF],
     * ignored for [LabelTier.TOP] (whose labels do not compete).
     *
     * ⚠ "If the top two are close, emit the parent and stop." Degrading to *dog* is the honest
     * answer; a confidently wrong *Corgi* on someone's shiba inu is worse than no breed at all,
     * because it teaches the user the whole feature is unreliable.
     */
    val margin: Float = 0f,
)

/**
 * IN-D9's vocabulary: a shallow hierarchy with a calibrated threshold per label.
 *
 * ⭐⭐ **Data, not weights.** Under IN-D8 the label set is text, so a taxonomy decision that would
 * ordinarily be irreversible is revisable here: ship coarse tiers first, add leaves later where
 * they prove reliable, and **nothing re-indexes**.
 *
 * ⚠⚠ **Special-category inference is never in the default vocabulary** (IN-D9). Health, religion,
 * ethnicity, sexuality, politics — a capability that genuinely wants one earns its own opt-in with
 * its own consent surface, off by default, shipping with its own threat-model amendment. ⚠ The
 * control sits at *creation*, not at query time: IN-D5 syncs labels, so the moment such a label is
 * written it is realm data on every device, and filtering it out of a search result changes nothing
 * about what is held.
 */
class LabelVocabulary internal constructor(
    val version: Int,
    val labels: List<VocabularyLabel>,
) {
    private val byName: Map<String, VocabularyLabel> = labels.associateBy { it.label }
    private val siblingsByParent: Map<String, List<VocabularyLabel>> =
        labels.filter { it.tier == LabelTier.LEAF }.groupBy { it.parent!! }

    /** Every label the model should be asked about — what `tagImage` receives. */
    val terms: List<String> get() = labels.map { it.label }

    operator fun get(label: String): VocabularyLabel? = byName[label]

    /** The leaves competing under [parent], in declaration order. */
    fun siblingsOf(parent: String): List<VocabularyLabel> = siblingsByParent[parent].orEmpty()

    val isEmpty: Boolean get() = labels.isEmpty()

    companion object {
        /**
         * ⚠⚠ **The honest production value until a vocabulary is calibrated.** An empty vocabulary
         * emits **no labels at all** — see [org.tenshu.inference.proxy.vocabulary.selectLabels].
         * That is deliberately not "write whatever the model says": labels nobody calibrated are
         * how a user learns the feature is unreliable, and §19.3 is explicit that the cut-off
         * belongs where the vocabulary is, not in a consumer.
         */
        val Empty = LabelVocabulary(version = 0, labels = emptyList())
    }
}

/**
 * Builds a vocabulary, rejecting shapes the rule could not apply.
 *
 * ⚠ A leaf with no parent has nothing to degrade to, and a leaf whose parent is missing would
 * silently emit nothing rather than the coarse label the user should still get. Both are author
 * errors that must fail at construction, not per photograph.
 */
fun vocabularyOf(version: Int, labels: List<VocabularyLabel>): LabelVocabulary {
    val names = labels.mapTo(mutableSetOf()) { it.label }
    require(names.size == labels.size) { "duplicate label in vocabulary" }
    labels.filter { it.tier == LabelTier.LEAF }.forEach { leaf ->
        val parent = requireNotNull(leaf.parent) { "leaf '${leaf.label}' has no parent to degrade to" }
        require(parent in names) { "leaf '${leaf.label}' names a parent '$parent' not in the vocabulary" }
    }
    return LabelVocabulary(version, labels)
}
