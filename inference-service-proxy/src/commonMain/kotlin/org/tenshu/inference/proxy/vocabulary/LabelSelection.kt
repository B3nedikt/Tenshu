package org.tenshu.inference.proxy.vocabulary

import org.tenshu.inference.proxy.ImageTag

/** A label the vocabulary's rule decided to write, and the score it was decided on. */
data class SelectedLabel(val label: String, val confidence: Float)

/**
 * IN-D9's tiered rule, applied to one image's scores — **the only thing that decides what becomes a
 * `MediaLabel` row**.
 *
 * Before this existed the indexer wrote every tag the backend returned, which is the shape §19.3
 * warns about: *"a backend gets labels; it does not get **good** labels."*
 *
 * The rule, in order:
 *
 * 1. **Top tier — independent thresholds.** "Is there a dog here at all?" is a question a zero-shot
 *    model answers reliably, and the labels are not mutually exclusive: a photo can be both *beach*
 *    and *dog*. Each clears its own floor or is dropped.
 * 2. **Leaves — floor, then margin, then parent.** Take the best sibling; ⚠⚠ it must clear its
 *    **own absolute floor first**, and only then beat its nearest sibling by the margin. Failing
 *    either, emit the parent.
 *
 * ⚠⚠ **The floor comes first, and that ordering is the amendment.** A purely relative rule cannot
 * tell "this is a bedroom, not a living room" from "the model rejects all fourteen of these and
 * this one is rejected least" — measured, the winner is routinely *negative* while the parent is
 * positive (`indoors 0.061` over `staircase −0.009`). And a *clear* margin between two
 * confidently-wrong siblings reads as confidence, so the relative rule alone is most wrong exactly
 * where it feels most certain.
 *
 * ⚠ A label the vocabulary does not contain is **dropped**, never written. The model may know
 * thousands of terms; IN-D9's whole argument is that the ones we write are chosen, not inherited.
 *
 * ⚠ An empty vocabulary emits nothing. See [LabelVocabulary.Empty] — an uncalibrated label is not
 * better than no label.
 */
fun selectLabels(tags: List<ImageTag>, vocabulary: LabelVocabulary): List<SelectedLabel> {
    if (vocabulary.isEmpty || tags.isEmpty()) return emptyList()

    // ⚠ Last-wins would let a duplicated term silently change the answer; the backend should not
    // return one, and if it does the strongest reading is the honest one.
    val scores = tags.groupBy { it.label }.mapValues { (_, t) -> t.maxOf { it.confidence } }
    val selected = linkedMapOf<String, Float>()

    vocabulary.labels.filter { it.tier == LabelTier.TOP }.forEach { top ->
        val score = scores[top.label] ?: return@forEach
        if (score >= top.floor) selected[top.label] = score
    }

    // Each sibling set competes once, under its parent.
    vocabulary.labels
        .filter { it.tier == LabelTier.LEAF }
        .mapNotNull { it.parent }
        .distinct()
        .forEach { parent ->
            val ranked = vocabulary.siblingsOf(parent)
                .mapNotNull { leaf -> scores[leaf.label]?.let { leaf to it } }
                .sortedByDescending { it.second }
            if (ranked.isEmpty()) return@forEach

            val (winner, winnerScore) = ranked.first()
            val runnerUp = ranked.getOrNull(1)?.second

            val clearsFloor = winnerScore >= winner.floor
            val clearsMargin = runnerUp == null || (winnerScore - runnerUp) >= winner.margin

            if (clearsFloor && clearsMargin) {
                selected[winner.label] = winnerScore
            } else {
                // ⚠⚠ Degrade, never invent. The parent is emitted **only if it earned its own
                // place** — a leaf failing its floor is not evidence for the parent, and promoting
                // an unearned parent would smuggle back the confidence the floor just refused.
                // In practice the parent usually did clear its threshold in the top-tier pass
                // above, which is exactly the `indoors 0.061` / `staircase −0.009` case.
                val parentScore = scores[parent]
                val parentLabel = vocabulary[parent]
                if (parentScore != null && parentLabel != null && parentScore >= parentLabel.floor) {
                    selected[parent] = parentScore
                }
            }
        }

    return selected.map { (label, confidence) -> SelectedLabel(label, confidence) }
}
