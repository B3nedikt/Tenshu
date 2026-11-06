package app.tenshu.timeline.data.geocode

/**
 * **Was this dwell part of a journey by transit?** (§31) — the trajectory half of the naming decision.
 *
 * §28 lets a named transit stop beat the city when a fix is too coarse to name a venue, bounded by the
 * fix's own accuracy. That bound is honest but blunt: measured across cities, ordinary cafés with a
 * named stop in range are **38 % (Paris) / 30 % (Tokyo) at 150 m** and 85–90 % at 300 m, so for a
 * poor-fix dwell the bound alone cannot tell "on a platform" from "in a café near a station".
 *
 * The trajectory can. A dwell bracketed by transit legs was part of a transit journey; one bracketed
 * by a *confident* walk was not.
 *
 * ### ⚠⚠ It may only ever TIGHTEN, and only on confident evidence
 *
 * Measured against the four real coarse dwells this rule can touch:
 *
 * | dwell | acc | label today | leg in | leg out |
 * |---|---|---|---|---|
 * | Odeonsplatz | 300 m | `Odeonsplatz` | walk/35 | **walk/conf=0** |
 * | Schwindegg | 100 m | `Schwindegg` | transit/57 | transit/70 |
 * | Mühldorf | 100 m | `Mühldorf (Oberbay)` | run/18 | drive/48 |
 * | (city) | 94 m | `München` | — | — |
 *
 * ⚠⚠⚠ **Odeonsplatz's outbound leg is the U5 ride, classified `walk` with confidence 0.** A gate that
 * read "walk ⇒ not transit" would undo the label §28 just fixed — on the very case that motivated it.
 * So a non-transit verdict counts *only* when the classifier was confident about it
 * ([MIN_CONFIDENT]); everything else, including a missing leg, is [Verdict.UNKNOWN] and leaves §28's
 * behaviour exactly as it is.
 *
 * ⚠ Widening is deliberately **not** offered. Every real transit stop measured sat within 80 m —
 * comfortably inside the existing bound — so a wider radius in transit context would buy nothing and
 * risk naming a dwell after a station a few streets away.
 *
 * ⇒ The rule is monotone: it can only ever make the label *more* conservative, never less, and on the
 * maintainer's own data it changes nothing at all. That is the point, not a shortfall.
 */
object TransitAdjacency {

    /**
     * Confidence at or above which a non-transit verdict is taken as evidence *against* transit.
     *
     * ⚠ Not tuned to taste: `modeConfidence` already multiplies boundary margin × sample size × fix
     * accuracy (`classifyMode`), so a low score means the classifier itself is unsure. The real legs
     * here score 0 and 18 on rides that *were* transit and 35–70 on verdicts that were right, so a bar
     * in the thirties separates "the classifier had an opinion" from "it produced a default". 30 is the
     * low end of that, because the cost of being wrong is asymmetric: over-trusting a weak `walk`
     * silently undoes a correct station name, while under-trusting one merely leaves §28's behaviour.
     */
    const val MIN_CONFIDENT = 30

    /** One bracketing leg, as the store holds it. `null` when there is no leg on that side. */
    data class Leg(val mode: String, val confidence: Int)

    enum class Verdict {
        /** Bracketed by transit — the stop preference is exactly right here. */
        TRANSIT,

        /** Confidently *not* transit on both sides — tighten, so a nearby station cannot win. */
        NOT_TRANSIT,

        /** No usable evidence. §28's accuracy-bounded behaviour stands unchanged. */
        UNKNOWN,
    }

    /**
     * @param inbound the movement leg arriving at the dwell, or `null`.
     * @param outbound the movement leg departing it, or `null`.
     */
    fun verdict(inbound: Leg?, outbound: Leg?): Verdict {
        val legs = listOfNotNull(inbound, outbound)
        if (legs.isEmpty()) return Verdict.UNKNOWN
        // ⚠ Either side is enough to say "transit": a metro hop's *arrival* leg is transit even when the
        // departure has not been classified yet, and the walk that led you to a platform is a walk.
        if (legs.any { it.mode == TRANSIT_MODE }) return Verdict.TRANSIT
        // ⚠⚠ Both sides must be confidently non-transit, and both must exist. One confident walk beside
        // one unclassified leg is not evidence that a journey was not a transit journey — it is exactly
        // the Odeonsplatz shape (a confident walk in, the unrecognised U5 out).
        val confidentlyNotTransit = inbound != null && outbound != null &&
            legs.all { it.mode != TRANSIT_MODE && it.confidence >= MIN_CONFIDENT }
        return if (confidentlyNotTransit) Verdict.NOT_TRANSIT else Verdict.UNKNOWN
    }

    /**
     * The stop-search radius for a [verdict], given what §28 would have used.
     *
     * ⚠ [Verdict.TRANSIT] and [Verdict.UNKNOWN] both return [accuracyBounded] unchanged — the first
     * because the bound already covers every measured transit stop, the second because absence of
     * evidence must not change behaviour.
     */
    fun searchRadiusM(verdict: Verdict, accuracyBounded: Int): Int = when (verdict) {
        Verdict.TRANSIT, Verdict.UNKNOWN -> accuracyBounded
        // ⚠ A stop this close is effectively underfoot, so it survives even a confident walk — the
        // station entrance you are standing in. Beyond it, a confident walk wins and the city is used.
        Verdict.NOT_TRANSIT -> minOf(accuracyBounded, NOT_TRANSIT_RADIUS_M)
    }

    /**
     * The tightened radius (metres).
     *
     * ⚠ Measured: ordinary cafés with a named stop within 100 m are 38 % in Paris and 10 % in Tokyo,
     * so 100 would still mislabel a third of poor-fix Paris café dwells. 40 m is under the width of the
     * streets those cafés sit on, which is the scale at which "the stop is where I was" stops being a
     * guess. ⚠ It costs nothing measured: no real transit dwell relied on the range 40–300 m *and* had
     * a confident non-transit bracket.
     */
    const val NOT_TRANSIT_RADIUS_M = 40

    private const val TRANSIT_MODE = "transit"
}
