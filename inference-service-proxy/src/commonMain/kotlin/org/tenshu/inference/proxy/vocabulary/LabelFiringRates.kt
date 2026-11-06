package org.tenshu.inference.proxy.vocabulary

import kotlin.math.round

/**
 * How often one label fired, across the images a pass actually looked at.
 *
 * [rate] is a fraction of [consideredImages], not of the whole library — see [firingRates].
 */
data class LabelFiringRate(
    val label: String,
    val firedOn: Int,
    val consideredImages: Int,
) {
    /** 0.0 – 1.0. ⚠ Zero considered images is a rate of zero, never a divide. */
    val rate: Double get() = if (consideredImages <= 0) 0.0 else firedOn.toDouble() / consideredImages
}

/**
 * §7.7's *"a label can be true and useless"* view — **the only way this failure is visible at all**.
 *
 * `indoors` reached the top 3 for **55–60 %** of a real library (`models.md` §3.7.4). It is not
 * wrong; most photos *are* indoors. But a top-tier label applied to something that common carries
 * no information and costs an index row on every photograph.
 *
 * ⚠⚠ **Per image, each result looks entirely reasonable.** That is the whole problem: nothing about
 * one photograph tagged `indoors` is suspicious, and the defect only exists in the aggregate. A
 * reviewer looking at samples will never find it, which is why the doc obliges whichever slice
 * ships the vocabulary to build this view rather than trusting inspection.
 *
 * ⚠ **A rate, deliberately not a verdict.** §7.7 lists three responses to a label that fires on
 * everything — drop it, raise its threshold, or demote it to a filter never surfaced as a tag — and
 * which one applies is a per-label judgement that needs *this* data first. Emitting a "too common"
 * flag here would be inventing the policy ahead of the evidence, which is the same error §19.3
 * warns about for thresholds.
 *
 * ⚠ Ordered by rate descending, then by label, so the output is stable enough to diff between runs.
 */
fun firingRates(
    firedCounts: Map<String, Int>,
    consideredImages: Int,
): List<LabelFiringRate> =
    firedCounts.entries
        .map { (label, fired) -> LabelFiringRate(label, fired, consideredImages) }
        .sortedWith(compareByDescending<LabelFiringRate> { it.firedOn }.thenBy { it.label })

/**
 * A one-line summary for the pass's report.
 *
 * ⚠ Names the denominator, because the number is meaningless without it: a pass that skipped an
 * already-indexed library considered almost nothing, and "indoors 100 %" over three photographs is
 * not the library-wide finding it looks like. ⭐ A **first** index of a library is the run where
 * this is the whole-library view — which is exactly the run §3.7.4's own 55–60 % came from.
 */
fun List<LabelFiringRate>.report(limit: Int = 5): String {
    if (isEmpty()) return "no labels fired"
    val considered = first().consideredImages
    val top = take(limit).joinToString(", ") { r ->
        // ⚠⚠ ROUND, never truncate. `0.57 * 100` is 56.999… in binary floating point, so `toInt()`
        // renders 57 of 100 as "56%" — an off-by-one in the single number this view exists to
        // report, and one nobody would question because it looks like a plausible percentage.
        "${r.label} ${round(r.rate * 100).toInt()}%"
    }
    return "$top (of $considered image${if (considered == 1) "" else "s"} considered)"
}
