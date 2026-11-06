package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType

/**
 * Set-membership predicate — `category oneOf listOf(FileCategory.Image, FileCategory.Video)` keeps
 * the rows whose value equals **any** candidate. SQL's `IN`.
 *
 * **The fourth predicate in the platform, after [equalTo], [contains] and [inRange], and the one
 * that makes a *disjunction* expressible at all.** Until it existed the chain DSL had no way to say
 * "either of these": `equalTo`, `contains` and `inRange` each return a [TBoolean] and nothing
 * consumed two of them, so a caller wanting two categories had no expression for it — separate
 * `filter` steps are an **and**, and there is no `or`. The Photos feed is the shipped example: it
 * read `category equalTo FileCategory.Image` and therefore could not show a video at all, and the
 * app's video support was blocked on this one gap rather than on any UI work.
 *
 * ⚠⚠ **Why membership rather than a general `or` combinator, which was the obvious first idea.** A
 * predicate is a **flat callstack** evaluated left-to-right against one row (`FilterImplementation`
 * hands each element to `evaluateCallstack` as `contextData`). `a or b` is a *tree*: both operands
 * need the row. But an operation's `execute(…, caller, params)` receives the **current** data — by
 * the time an `Or` ran, the row would already have been reduced to the left-hand [TBoolean], and
 * the row itself is gone. Supporting it means threading `contextData` into the operation contract
 * or teaching `evaluateCallstack` a fifth peephole. Membership needs none of that: the receiver is
 * an already-resolved value and the candidates are a param, so it is exactly [equalTo]'s shape with
 * a list where the single `other` was.
 *
 * ⚠ It is therefore **narrower than `or` on purpose** — one field against many values, never two
 * different fields. That covers the overwhelmingly common case (any `TConstant` family, ids,
 * statuses); a genuine cross-field disjunction still has no expression, and should stay unbuilt
 * until a second real caller needs one.
 *
 * ⚠ **Comparison is [equalToLogic] per candidate**, so it inherits that function's semantics
 * exactly — identity-of-constant for a [TConstant], value equality for a [TValue]. Pass
 * `FileCategory.Image`, never `"image"`, for the same reason `equalTo` requires it.
 *
 * ⚠ **An empty candidate list is `false`, not an error and not "match everything".** "In none of
 * these" is a coherent question with a coherent answer, and the alternative — reading empty as
 * unfiltered — turns a caller's empty collection into a silently unbounded read.
 *
 * ⚠ Like [contains] and [inRange] this is a **linear scan** — it narrows what crosses the wire and
 * what a caller materialises, never what the store walks. It is a predicate, not an index.
 *
 * Dual-dispatches exactly as its three siblings: against a recording [RequestObject] it appends a
 * serialisable `OneOf` operation to the predicate callstack for the proxy to evaluate; against real
 * in-memory [Data] it evaluates directly through [oneOfLogic].
 *
 * @param candidates the values to match against — `String`, `Long`, `Boolean` or any [Data]
 */
infix fun Data.oneOf(candidates: List<Any>): TBoolean {
    return if (this is RequestObject) {
        findOperation<OneOf>(OneOf.ID).oneOf(candidates)
    } else {
        oneOfLogic(this, candidates)
    }
}

/**
 * The in-memory half of [oneOf] — `true` when [receiver] equals any of [candidates] under
 * [equalToLogic].
 *
 * ⚠ Short-circuits on the first match, so the common case (a small closed set, the match near the
 * front) costs one comparison rather than `candidates.size`.
 */
fun oneOfLogic(receiver: Data, candidates: List<Any>): TBoolean =
    candidates.any { equalToLogic(receiver, it).get() }.toTBoolean()

interface OneOf : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun oneOf(candidates: List<Any>): TBoolean

    companion object {
        const val ID = "tenshu/core/protocol/oneOf"

        val Schema = operation(
            id = ID,
            receiver = Data.Schema.toType(),
            params = {
                list(id = Params.CANDIDATES)
            }
        )
    }

    object Params {
        const val CANDIDATES: String = "${ID}/candidates"
    }
}
