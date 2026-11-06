package org.tenshu.core.ingest

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The app reports its own live ingest status for a source it owns (§4/§5). A
 * no-op if no row exists for (`sourceId`, `deviceId`) — status is meaningless
 * without a registered descriptor. Preserves the descriptor + `enabled` state;
 * only the status fields are written.
 *
 * Conventions: [pendingCount] / [lastRunAtMillis] of `0` mean "unset" (stored as
 * absent); a blank [lastError] clears the error. [lastError] is a **typed-error
 * code only** — redaction-by-construction, never a path or file content (A19 /
 * error-channel §8.11).
 */
suspend fun IngestService.updateSourceStatus(
    sourceId: String,
    deviceId: String,
    status: String,
    pendingCount: Long = 0,
    lastRunAtMillis: Long = 0,
    lastError: String = "",
): TResult<Data?> =
    findOperation<UpdateSourceStatus>(UpdateSourceStatus.ID)
        .updateSourceStatus(sourceId, deviceId, status, pendingCount, lastRunAtMillis, lastError)
        .asTaskInteractor<Data?>()
        .execute()

interface UpdateSourceStatus : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun updateSourceStatus(
        sourceId: String,
        deviceId: String,
        status: String,
        pendingCount: Long,
        lastRunAtMillis: Long,
        lastError: String,
    ): RequestObject

    companion object {
        const val ID = "tenshu/ingest/service/updateSourceStatus"

        val Schema = operation(
            id = ID,
            receiver = IngestService.Schema.toType(),
            params = {
                string(Params.SOURCE_ID)
                string(Params.DEVICE_ID)
                string(Params.STATUS)
                int(Params.PENDING_COUNT)
                int(Params.LAST_RUN_AT)
                string(Params.LAST_ERROR)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SOURCE_ID = "$ID/sourceId"
        const val DEVICE_ID = "$ID/deviceId"
        const val STATUS = "$ID/status"
        const val PENDING_COUNT = "$ID/pendingCount"
        const val LAST_RUN_AT = "$ID/lastRunAt"
        const val LAST_ERROR = "$ID/lastError"
    }
}
