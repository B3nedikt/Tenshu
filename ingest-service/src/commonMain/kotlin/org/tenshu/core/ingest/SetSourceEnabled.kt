package org.tenshu.core.ingest

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The central kill switch (§5). Control's management surface flips a source's
 * `enabled` state; the owning app honours it on its next self-triggered tick
 * (mobile) or its next watch event (desktop) — there is no push. A no-op if no
 * row exists for (`sourceId`, `deviceId`).
 */
suspend fun IngestService.setSourceEnabled(
    sourceId: String,
    deviceId: String,
    enabled: Boolean,
): TResult<Data?> =
    findOperation<SetSourceEnabled>(SetSourceEnabled.ID)
        .setSourceEnabled(sourceId, deviceId, enabled)
        .asTaskInteractor<Data?>()
        .execute()

interface SetSourceEnabled : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun setSourceEnabled(
        sourceId: String,
        deviceId: String,
        enabled: Boolean,
    ): RequestObject

    companion object {
        const val ID = "tenshu/ingest/service/setSourceEnabled"

        val Schema = operation(
            id = ID,
            receiver = IngestService.Schema.toType(),
            params = {
                string(Params.SOURCE_ID)
                string(Params.DEVICE_ID)
                boolean(Params.ENABLED)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SOURCE_ID = "$ID/sourceId"
        const val DEVICE_ID = "$ID/deviceId"
        const val ENABLED = "$ID/enabled"
    }
}
