package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records one completed import, and folds its figures onto the owning [HealthSource] in the
 * same operation (`docs/proposals/fit-data-sources-restructure.md` §2.2, D5).
 *
 * Two writes, one call, deliberately: the denormalised `lastRunAt` / `lastStatus` /
 * `lastItemCount` exist so the Data sources list is one read, and splitting the writes across
 * two operations would open a window where the run exists but the row the UI reads still shows
 * the previous import.
 *
 * ⚠⚠ **Call this only for a run that did something (D7)** — imported items, or failed. A
 * foreground refresh that found nothing new writes no row (see [HealthImportRun]).
 *
 * A [HealthImportRun] whose `kind` is [HealthImportRun.KIND_TAKEOUT] updates no source row —
 * an import from a file is an act, not a standing connection (D11), and none exists.
 */
suspend fun HealthService.recordImportRun(run: HealthImportRun): TResult<Data?> =
    findOperation<RecordImportRun>(RecordImportRun.ID)
        .recordImportRun(run)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordImportRun : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordImportRun(run: HealthImportRun): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordImportRun"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.RUN, type = HealthImportRun.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val RUN = "$ID/run"
    }
}
