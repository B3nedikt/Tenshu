package org.tenshu.core

import org.tenshu.core.number.TInteger

/**
 * Half-open numeric containment, the evaluation logic behind [inRange]:
 * `startInclusive <= receiver < endExclusive`.
 *
 * Defined only for a [TInteger] receiver — which covers every timestamp in the platform,
 * since `Instant : TInteger` — against numeric bounds (a [TInteger], or a raw `Long`/`Int`
 * for direct in-memory calls). ⚠ **Any other pairing is `false`, never an error and never a
 * coercion**: `inRange` is a numeric predicate, so matching it against text is meaningless
 * and we do not guess. This is the [containsLogic] posture, for the same reason.
 *
 * ⚠ **An empty or inverted range matches nothing**, rather than being treated as "no
 * filter". `inRange(x, x)` is the empty half-open interval and must return `false`;
 * silently widening it to "everything" would turn a caller's arithmetic slip into a full
 * scan that looks like a successful answer.
 */
fun inRangeLogic(receiver: Data, startInclusive: Any, endExclusive: Any): TBoolean {
    val value = (receiver as? TInteger)?.get() ?: return false.toTBoolean()
    val start = numericOrNull(startInclusive) ?: return false.toTBoolean()
    val end = numericOrNull(endExclusive) ?: return false.toTBoolean()

    return (value >= start && value < end).toTBoolean()
}

private fun numericOrNull(bound: Any): Long? = when (bound) {
    is TInteger -> bound.get()
    is Long -> bound
    is Int -> bound.toLong()
    else -> null
}
