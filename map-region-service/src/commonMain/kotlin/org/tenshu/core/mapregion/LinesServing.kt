package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * **The transit lines serving both ends of a leg** (`timeline-movement-capture.md` §33 L4) — the
 * question that turns "Transit · 2.9 km" into "U5".
 *
 * ### ⚠⚠ Why a stop **pair**, and not a line at a point
 *
 * A station serves many lines: Odeonsplatz answers `{U3, U4, U5, U6}`. Only the **intersection** with
 * the other end names the ride. And the intersection is what the evidence supports — §27 measured a
 * covered metro hop's stored trace at 2 points with a travelling span of 0 m, so there is no path to
 * map-match and the endpoints are all that survive.
 *
 * ### What it returns
 *
 * The line refs, **comma-separated and sorted**, or `""` for "cannot say". ⚠ Refs (`U5`), never the
 * relation's `name` (`U-Bahn U5: Laimer Platz => Neuperlach Süd`) — that is a direction, and
 * unreadable on a feed row.
 *
 * ⚠⚠ Deliberately the **data**, not a rendered label: the answer is often a set (`U3,U6` on a shared
 * trunk), and how to show that is the consumer's decision. `TransitLineIndex.label` is the settled
 * rule and lives where both sides can call it.
 *
 * ⚠ `""` means the overlay could not answer — no coverage, no artefact, a fetch that failed. It never
 * means "this leg has no line", so a caller must fall back to rendering the leg as it would without
 * this op rather than asserting anything.
 *
 * [withinM] is the caller's own uncertainty about where the leg ended, exactly as [NearestTransitStop]
 * takes it: a stop only answers for an endpoint if it lies where that endpoint could plausibly be.
 *
 * Egress is **Control's**, like every other tier here; the Timeline app has no `INTERNET`.
 */
suspend fun MapRegionService.linesServing(
    fromLatE7: Long,
    fromLngE7: Long,
    toLatE7: Long,
    toLngE7: Long,
    withinM: Long,
): TResult<TString> =
    findOperation<LinesServing>(LinesServing.ID)
        .linesServing(fromLatE7, fromLngE7, toLatE7, toLngE7, withinM)
        .asTaskInteractor<TString>()
        .execute()

interface LinesServing : Operation {

    override val schema: OperationSchema get() = Schema

    fun linesServing(
        fromLatE7: Long,
        fromLngE7: Long,
        toLatE7: Long,
        toLngE7: Long,
        withinM: Long,
    ): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/linesServing"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                int(Params.FROM_LAT_E7)
                int(Params.FROM_LNG_E7)
                int(Params.TO_LAT_E7)
                int(Params.TO_LNG_E7)
                int(Params.WITHIN_M)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val FROM_LAT_E7 = "$ID/fromLatE7"
        const val FROM_LNG_E7 = "$ID/fromLngE7"
        const val TO_LAT_E7 = "$ID/toLatE7"
        const val TO_LNG_E7 = "$ID/toLngE7"
        const val WITHIN_M = "$ID/withinM"
    }
}
