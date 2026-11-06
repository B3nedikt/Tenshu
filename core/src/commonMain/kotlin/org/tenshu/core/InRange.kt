package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType

/**
 * Half-open range predicate for numeric fields — `createdAt.inRange(junMs, sepMs)` keeps
 * the rows in `[start, end)`.
 *
 * **The third predicate in the platform, after [equalTo] and [contains], and the one that
 * makes a *time window* expressible at all.** Until it existed, every window question had
 * to become a bespoke per-service operation (`photosCapturedBetween` is the shipped
 * example) or be refused outright — Mia's MQ-1 grammar had to answer *"I can only search
 * files by name, not by date yet"* for exactly this reason, and Timeline, Fit and Calendar
 * would each have needed an op of their own to say anything about last week.
 *
 * ⚠⚠ **Half-open, and that is not a detail.** `start` is inclusive, `end` is exclusive, so
 * adjacent windows can be laid end to end without the row that lands exactly on midnight
 * being counted in both. The same call is made everywhere ranges appear in this codebase —
 * VAT filing periods (`commerce` WC-2), Mia's `TimeWindow` — and disagreeing here would put
 * a double-counting bug one refactor away.
 *
 * ⚠ **Numeric only, and absent is never in range.** The receiver must be a [TInteger] — which
 * covers every timestamp, since `Instant : TInteger` — and any other pairing is `false`
 * rather than an error or a guess (the [containsLogic] posture). A row whose field is
 * *absent* therefore drops out of every window, which is the same call
 * `PhotosCapturedBetween` made deliberately: without a timestamp a row cannot be placed in
 * time, and reading absence as `0` would file it under whichever window reaches back
 * furthest.
 *
 * ⚠ Like [contains] this is a **linear scan** — it narrows what crosses the wire and what a
 * caller materialises, never what the store walks. It is a predicate, not an index.
 *
 * Dual-dispatches exactly as its two siblings: against a recording [RequestObject] it
 * appends a serialisable `InRange` operation to the predicate callstack for the proxy to
 * evaluate; against real in-memory [Data] it evaluates directly through [inRangeLogic].
 *
 * @param startInclusive lower bound, included — a `Long`, [TInteger] or any [Instant]
 * @param endExclusive upper bound, excluded
 */
fun Data.inRange(startInclusive: Any, endExclusive: Any): TBoolean {
    return if (this is RequestObject) {
        findOperation<InRange>(InRange.ID).inRange(startInclusive, endExclusive)
    } else {
        inRangeLogic(this, startInclusive, endExclusive)
    }
}

interface InRange : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun inRange(startInclusive: Any, endExclusive: Any): TBoolean

    companion object {
        const val ID = "tenshu/core/protocol/inRange"

        val Schema = operation(
            id = ID,
            receiver = Data.Schema.toType(),
            params = {
                composite(id = Params.START_INCLUSIVE)
                composite(id = Params.END_EXCLUSIVE)
            }
        )
    }

    object Params {
        const val START_INCLUSIVE: String = "${ID}/startInclusive"
        const val END_EXCLUSIVE: String = "${ID}/endExclusive"
    }
}
