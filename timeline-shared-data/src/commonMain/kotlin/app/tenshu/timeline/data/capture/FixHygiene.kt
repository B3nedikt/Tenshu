package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * **Fix-stream hygiene** (`docs/proposals/timeline-movement-capture.md` §22) — the two corrections the
 * real-journey measurement (§21) forced, both derived from measurement rather than argued.
 *
 * ### 1. The stream is not in time order — so drop, **never sort**
 *
 * `activeProviders()` registers GPS **and** NETWORK as two independent listeners, and their fixes
 * interleave by *arrival*, not by timestamp. Measured across 65 real traces: **149 of 2 438
 * consecutive pairs go backwards in time**, by up to 9 s. `MovementSegmenter`'s KDoc asserts the
 * opposite ("assumes fixes arrive in time order (live sensors do)") — it was simply untrue in
 * production.
 *
 * ⚠⚠ **Sorting is worse than doing nothing.** Measured on the reported journey: as-stored 129.8 km,
 * **sorted 132.9 km**, monotonic-drop 128.6 km. Sorting *relocates* a stale coarse fix into the
 * middle of the path, where it turns one there-and-back excursion into two long legs. Dropping it
 * removes the excursion. This is the counter-intuitive result that makes the rule worth stating.
 *
 * ### 2. ⚠⚠ Ordering was **not** the cause of the distance inflation — accuracy is
 *
 * §21.3 attributed a ~53 % over-report to the out-of-order stream. The measurement says otherwise:
 * monotonic ordering recovers **1.2 km of 45**. What recovers it is excluding **coarse fixes from
 * the route geometry** — 129.8 km → 78.0 km against an ≈85 km rail route.
 *
 * The mechanism is not small spurious steps (an accuracy-relative *leg* rule was tried and reached
 * only 109–120 km). A ±300–600 m fix is **displaced off the true path**, so the route goes
 * true → off → true: a spike whose two long legs both look like real travel. Removing the vertex
 * removes the spike; shortening the legs does not.
 *
 * ⚠⚠ **But coarse fixes must not simply be discarded.** §11.1's on-device finding is that indoors
 * GPS often never fixes while NETWORK does, and listening on both *is what makes an indoor place
 * record at all*. So the split is by **role**, not by disposal:
 *
 *  - **presence** (dwell clustering) — every fix counts, coarse included. Untouched here.
 *  - **geometry** (distance, polyline, speed) — credible fixes only.
 *
 * …with a fallback, because a journey recorded *entirely* on NETWORK is a real journey: measured over
 * all 65 traces, a hard filter drops **3 below the ≥3-fix floor** and cuts several genuine trips by
 * more than half. [geometryPath] therefore yields to the unfiltered path whenever the filtered one is
 * not viable, and the caller decides viability with the ordinary thresholds.
 */
object FixHygiene {

    /**
     * Everything not older than what came before it.
     *
     * ⚠ **Drop, not sort** — see the type KDoc; sorting measured *worse* than leaving the stream
     * alone. A dropped fix is never the only evidence of anything: it arrived late precisely because
     * a fix for that moment already came from the other provider.
     */
    fun inTimeOrder(fixes: List<CapturedFix>): List<CapturedFix> {
        if (fixes.size < 2) return fixes
        val out = ArrayList<CapturedFix>(fixes.size)
        val carry = StepCarry()
        for (fix in fixes) {
            if (out.isEmpty() || fix.timestampMs > out.last().timestampMs) out += carry.onto(fix)
            else carry.drop(fix)
        }
        return out
    }

    /**
     * The fixes credible enough to be **route vertices**, or the input unchanged when filtering would
     * leave too little to describe the journey. **The two terminal fixes are always kept** — see
     * below.
     *
     * ⚠ The fallback is not defensive coding — it is the measured case: journeys recorded mostly on
     * NETWORK exist, and for them the coarse fixes *are* the route. Better a route with known
     * uncertainty than no route.
     *
     * ⚠ `accuracyM == 0` means **not reported** (the convention `confidenceOf` and [anchorRadiusM]
     * already follow) and is kept: absence of a quality claim is not a claim of poor quality.
     *
     * ### ⚠⚠ The endpoints are presence, not geometry (§32.6) — the third role §25 asked for
     *
     * The §22 rule above is right about *interior* vertices and wrong about the two ends, and the
     * difference is not a matter of degree:
     *
     * | fix | dropping it | keeping it |
     * |---|---|---|
     * | interior, coarse | removes a true→off→true **excursion** | adds two long spurious legs |
     * | **terminal**, coarse | **truncates the journey** — deletes real distance and the destination | adds one leg, to where the user actually was |
     *
     * A terminal fix is not a vertex the path passes through, it is the claim *this is where the leg
     * ended*, and nothing else in the path carries that claim. Measured on the reported U5 ride
     * (2026-08-12): the whole underground portion is one jump between the last fix before descending
     * and the first after emerging, and the arrival fix is coarse **because** the rider was
     * underground. Dropping it recorded `1223 m` ending at Isartor for a 4.2 km ride to Odeonsplatz —
     * the leg stops where GPS stopped rather than where the journey did. Same input, arrival fix at
     * 40 m instead of 300 m: `2162 m`, reaching the arrival.
     *
     * ⚠ This is bounded and self-limiting, which is why it does not re-open §22's inflation:
     *  - at most **one** added leg per end, against an interior spike's two;
     *  - `classifyMode` takes the **median** per-leg speed, so a single long terminal leg cannot flip
     *    a multi-leg verdict;
     *  - S6b's extent floor scales with the path's *mean reported accuracy*, so admitting coarse ends
     *    raises the bar a path must clear, never lowers it;
     *  - an all-coarse path still has fewer than [MIN_GEOMETRY_FIXES] credible vertices and still
     *    takes the unfiltered fallback, exactly as before.
     *
     * ⚠ Not re-measured against §22's 65-trace corpus — that corpus lives on the device, not in the
     * repo. The expectation is that §22's 78.0 km against an ≈85 km true route moves *up*, since what
     * is being restored is real travel at the ends of legs; it is an expectation, not a measurement.
     */
    fun geometryPath(fixes: List<CapturedFix>, maxAccuracyM: Int): List<CapturedFix> {
        if (fixes.size < MIN_GEOMETRY_FIXES) return fixes
        fun keeps(i: Int, fix: CapturedFix) =
            i == 0 || i == fixes.lastIndex || fix.accuracyM <= 0 || fix.accuracyM <= maxAccuracyM
        if (fixes.withIndex().count { (i, fix) -> keeps(i, fix) } < MIN_GEOMETRY_FIXES) return fixes
        // A dropped vertex's steps were still walked: carry them onto the next kept fix, which the
        // terminal rule above guarantees exists.
        val carry = StepCarry()
        return fixes.mapIndexedNotNull { i, fix ->
            if (keeps(i, fix)) carry.onto(fix) else { carry.drop(fix); null }
        }
    }

    /** Below this the filtered path cannot describe a journey and the unfiltered one is used. */
    const val MIN_GEOMETRY_FIXES: Int = 3
}

/**
 * Stream operator: enforce time order before segmentation.
 *
 * ⚠ Applied **after** the raw-fix corpus records (§18.9) and **before** [segment], deliberately. The
 * corpus must keep the stream exactly as the platform delivered it — disorder included, since that
 * disorder is itself a finding — while everything that reasons about the path gets a monotonic one.
 */
fun Flow<CapturedFix>.inTimeOrder(): Flow<CapturedFix> = flow {
    var lastMs = Long.MIN_VALUE
    val carry = StepCarry()
    collect { fix ->
        if (fix.timestampMs > lastMs) {
            lastMs = fix.timestampMs
            emit(carry.onto(fix))
        } else {
            carry.drop(fix)
        }
    }
}

/**
 * The steps of dropped fixes, waiting for the next kept one ([CapturedFix.stepsDroppedBefore]).
 * `null` once any dropped fix was uncounted: the total it feeds would otherwise be short by a number
 * no sensor reported.
 */
internal class StepCarry {
    private var steps: Int? = 0

    fun drop(fix: CapturedFix) {
        val own = fix.stepsSinceLastFix
        val before = fix.stepsDroppedBefore
        steps = if (own == null || before == null) null else steps?.plus(own + before)
    }

    fun onto(fix: CapturedFix): CapturedFix {
        if (steps == 0) return fix
        val before = fix.stepsDroppedBefore
        val merged = if (before == null) null else steps?.plus(before)
        steps = 0
        return fix.copy(stepsDroppedBefore = merged)
    }
}
