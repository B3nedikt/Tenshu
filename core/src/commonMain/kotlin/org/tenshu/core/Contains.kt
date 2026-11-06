package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType

/**
 * Substring predicate for [TString] fields — the fuzzy counterpart to [equalTo].
 * `name contains "Rep"` matches `"Report.pdf"`.
 *
 * **Case-sensitive by default**, matching Kotlin's `String.contains` (and the
 * norm across query DSLs — Django `__contains`, Postgres `LIKE`, NSPredicate
 * `CONTAINS`). Case-insensitive matching is an explicit opt-in via the
 * [contains]`(other, ignoreCase = true)` overload, mirroring Kotlin's own
 * non-infix shape — never a hidden engine policy (the pre-2026-07 behaviour,
 * where the infix silently matched case-insensitively while borrowing Kotlin's
 * case-sensitive syntax; see `docs/proposals/core-operation-dispatch.md` §4).
 *
 * Like [equalTo] this dual-dispatches: against a recording [RequestObject]
 * it appends a serialisable `Contains` operation to the predicate callstack
 * (sent to the proxy and evaluated in [containsLogic]); against real
 * in-memory [Data] it evaluates directly.
 *
 * Text-only: a non-string receiver or argument yields `false` (see
 * [containsLogic]). Unlike [equalTo] this never qualifies for the exact-key
 * fast path — every `contains` is a linear scan.
 */
infix fun Data.contains(other: Any): TBoolean = contains(other, ignoreCase = false)

/**
 * [contains] with an explicit case fold — `name.contains(query, ignoreCase = true)`
 * is the shape user-facing search wants (a query for "photo" should match
 * "Photo.jpg"). The flag rides as an optional op param, so a plain [contains]
 * stays byte-identical on the wire.
 */
fun Data.contains(other: Any, ignoreCase: Boolean): TBoolean {
    return if (this is RequestObject) {
        findOperation<Contains>(Contains.ID).contains(other, ignoreCase)
    } else {
        containsLogic(this, other, ignoreCase)
    }
}

interface Contains : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun contains(other: Any, ignoreCase: Boolean): TBoolean

    companion object {
        const val ID = "tenshu/core/protocol/contains"

        val Schema = operation(
            id = ID,
            receiver = Data.Schema.toType(),
            params = {
                composite(id = Params.OTHER)
                // Optional: omitted for the (default) case-sensitive match, so the
                // common case doesn't carry the param on the wire.
                boolean(id = Params.IGNORE_CASE, isOptional = true)
            }
        )
    }

    object Params {
        const val OTHER: String = "${ID}/other"
        const val IGNORE_CASE: String = "${ID}/ignoreCase"
    }
}
