package org.tenshu.core.common.parser

import org.tenshu.core.TenshuException
import org.tenshu.core.common.impl.UnsupportedOperationErrorDto

/**
 * The parser met a 16-byte type tag this build cannot resolve to a registered
 * schema. Deliberately **not** a typed [org.tenshu.core.TError] on its own: the
 * parser is generic — the same bytes carry operations, their parameters and
 * sync payloads — so *what* an unknown tag means depends on where it sat, and
 * only the caller of the parse knows that.
 *
 * [position] is what makes that judgement possible: `parseArray` compares it
 * against the element it was about to read, so "the callstack element's own
 * type is unknown" (⇒ an operation, §9.3) is distinguishable from "something
 * nested inside a known operation is unknown" (⇒ not an operation, and it must
 * not be reported as one).
 *
 * ⚠ [resolvedTypeId] is null in the common case. The tag→id direction is built
 * from registered schemas, so a *deregistered* id cannot be named at all; it is
 * non-null only for the narrower "id known, schema missing" inconsistency.
 */
internal class UnknownTypeTagException(
    val position: Int,
    val typeTagHex: String,
    val resolvedTypeId: String?,
    cause: Throwable?,
) : IllegalStateException(
    buildString {
        append("No schema registered for type tag ")
        append(typeTagHex)
        if (resolvedTypeId != null) append(" (id '").append(resolvedTypeId).append("')")
        append(" at position ").append(position)
    },
    cause,
) {

    /** The callstack-element reading of this failure (E3, §9.3). */
    fun asUnsupportedOperation(): TenshuException = TenshuException(
        UnsupportedOperationErrorDto(
            message = "this build has no operation for wire tag $typeTagHex" +
                (resolvedTypeId?.let { " (id '$it')" } ?: "") +
                " — it was removed, or the caller is newer than this proxy " +
                "(schema-evolution §9.3)",
            operationId = resolvedTypeId ?: "",
            typeTag = typeTagHex,
        )
    )
}
