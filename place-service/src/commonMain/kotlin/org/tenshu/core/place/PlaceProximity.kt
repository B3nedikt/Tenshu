package org.tenshu.core.place

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Are these two records the same place?" — the one proximity rule, shared by the two layers that
 * ask it (`docs/proposals/timeline-recognition-and-place-naming.md` §8.4 F1 and §10.3):
 *
 *  - the **write-time snap** in the app (`PlaceIdentityResolver`), a cheap local pre-filter that
 *    stops a newly-minted place becoming a second row for a venue this device already knows;
 *  - the **proxy reconciliation pass** (`PlaceReconciler`), the convergent authority that also sees
 *    rows this device did not write.
 *
 * They must agree, or the pass would spend its life undoing the resolver's decisions (or worse,
 * merging pairs the resolver had deliberately kept apart). Hence one rule, in the schema module both
 * sides already depend on.
 *
 * **The radius is accuracy-scaled, not constant.** Two dwells can only be told apart to within the
 * accuracy of the fixes that produced them, and `accuracyM` is carried on every `Place` (4-B, §4.3).
 * A poor indoor fix — ±100 m, the case that fragments a venue in the first place — snaps generously;
 * two clean outdoor fixes 60 m apart stay distinct.
 */
object PlaceProximity {

    /** Never fold two places further apart than the best fix could plausibly confuse. */
    const val SAME_PLACE_MIN_RADIUS_M = 60.0

    /** …and never fold across more than this, however bad the reported accuracy claims to be. */
    const val SAME_PLACE_MAX_RADIUS_M = 150.0

    /** Used when neither side reports an accuracy (imports, pre-4-B captures). */
    const val SAME_PLACE_DEFAULT_RADIUS_M = 100.0

    /**
     * Ceiling for the **mutual-uncertainty** rule below, where *both* places must be this vague.
     *
     * ⚠ Larger than [SAME_PLACE_MAX_RADIUS_M] and safe for the same reason it is larger: that clamp
     * bounds a rule driven by the **worse** of two accuracies, where one vague row is enough to reach
     * across a district. This one is driven by the **better** of the two, so reaching 300 m requires
     * *both* rows to admit ±300 m — a far stronger precondition than "one of them is bad".
     */
    const val MUTUAL_UNCERTAINTY_MAX_RADIUS_M = 300.0

    /**
     * How far apart two dwells may be and still be one place: the worse of the two reported
     * accuracies, clamped. `0` means "not reported" on a side; when neither reports, the default
     * applies — we coarsen on ignorance rather than pretending to precision we do not have.
     */
    fun radiusFor(accuracyA: Int, accuracyB: Int): Double {
        val worst = maxOf(accuracyA, accuracyB)
        if (worst <= 0) return SAME_PLACE_DEFAULT_RADIUS_M
        return worst.toDouble().coerceIn(SAME_PLACE_MIN_RADIUS_M, SAME_PLACE_MAX_RADIUS_M)
    }

    /**
     * How far apart two dwells may be and still be one place when **both** of them admit that much
     * uncertainty: the *better* of the two accuracies, clamped to [MUTUAL_UNCERTAINTY_MAX_RADIUS_M].
     *
     * ### ⚠⚠ Why a second rule rather than a wider clamp on the first (§32.17)
     *
     * Since §32.10 a place's `accuracyM` is the worse of fix quality and **cluster spread**, so a stay
     * the user walked through legitimately reports ±300 m and its grid-snapped centroid moves that far
     * between visits. Measured on the reported store: one U-Bahn entrance had **two** `Place` rows
     * 244 m apart, both ±300, and [radiusFor]'s 150 m ceiling could never fold them.
     *
     * ⚠⚠ Simply raising that ceiling was measured and **rejected**: at 250 m it folds the duplicate
     * pair, and at 300 m it also folds the user's **office into the U-Bahn station** (256 m apart).
     * A 50 m margin, fitted to one store, is not a constant worth shipping.
     *
     * The asymmetry that separates them is not distance, it is *who is uncertain*. [radiusFor] takes
     * the **worse** accuracy, so one vague row is enough to reach out and swallow a precisely-located
     * neighbour — which is exactly the office being absorbed by a station that admits ±300 m. This
     * rule takes the **better**, so a fold requires both rows to be unable to tell the difference:
     *
     * | pair | apart | worse | better | folds here |
     * |---|---|---|---|---|
     * | the two station rows | 244 m | 300 | **300** | ✅ |
     * | office ↔ station | 256 m | 300 | **100** | ❌ |
     *
     * ⚠ The office's margin is **106 m**, set by [radiusFor]'s unchanged 150 m ceiling rather than by
     * this rule's 100 m — a union is only ever as strict as its most permissive arm.
     *
     * ⚠ It is deliberately a **union** with [radiusFor] in [isSamePlace], never a replacement. On its
     * own it is *stricter* for mismatched pairs — a ±100 m indoor dwell 80 m from a ±16 m known home
     * would stop folding — and that is precisely the §8.2 fragmentation F1 exists to prevent. Adding
     * a second way to say yes can only ever fold more, so no pair that folds today stops folding.
     */
    fun mutualUncertaintyRadiusFor(accuracyA: Int, accuracyB: Int): Double {
        if (accuracyA <= 0 || accuracyB <= 0) return 0.0 // unknown on either side ⇒ this rule abstains
        val better = minOf(accuracyA, accuracyB)
        return better.toDouble().coerceIn(SAME_PLACE_MIN_RADIUS_M, MUTUAL_UNCERTAINTY_MAX_RADIUS_M)
    }

    /**
     * True when the two coordinates are within [radiusFor] **or**
     * [mutualUncertaintyRadiusFor] of each other — see the latter for why there are two rules.
     */
    fun isSamePlace(
        aLatE7: Long, aLngE7: Long, aAccuracyM: Int,
        bLatE7: Long, bLngE7: Long, bAccuracyM: Int,
    ): Boolean {
        val d = distanceMeters(aLatE7, aLngE7, bLatE7, bLngE7)
        return d <= radiusFor(aAccuracyM, bAccuracyM) ||
            d <= mutualUncertaintyRadiusFor(aAccuracyM, bAccuracyM)
    }

    /** Great-circle distance between two E7 coordinates, in metres (haversine). */
    fun distanceMeters(aLatE7: Long, aLngE7: Long, bLatE7: Long, bLngE7: Long): Double {
        val earthRadiusM = 6_371_000.0
        val lat1 = aLatE7 / 1e7
        val lon1 = aLngE7 / 1e7
        val lat2 = bLatE7 / 1e7
        val lon2 = bLngE7 / 1e7
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusM * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}
