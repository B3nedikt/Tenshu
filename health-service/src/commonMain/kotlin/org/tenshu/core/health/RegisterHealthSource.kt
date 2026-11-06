package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Declares that this device can import from a platform health store — an idempotent upsert of
 * the ([kind], [deviceId]) [HealthSource] row
 * (`docs/proposals/fit-data-sources-restructure.md` §2.1).
 *
 * ⚠⚠ **Registering must not reset the user's settings.** This runs on every launch where the
 * importer reports itself available, so it refreshes only the *descriptor* ([deviceLabel]) and
 * preserves `autoImport` plus the denormalised run trio on an existing row. A register that
 * re-defaulted `autoImport` to `true` would silently re-enable a source the user turned off,
 * from the other side of the realm.
 */
suspend fun HealthService.registerHealthSource(
    kind: String,
    deviceId: String,
    deviceLabel: String,
): TResult<Data?> =
    findOperation<RegisterHealthSource>(RegisterHealthSource.ID)
        .registerHealthSource(kind, deviceId, deviceLabel)
        .asTaskInteractor<Data?>()
        .execute()

interface RegisterHealthSource : Operation {

    override val schema: OperationSchema get() = Schema

    fun registerHealthSource(
        kind: String,
        deviceId: String,
        deviceLabel: String,
    ): RequestObject

    companion object {
        const val ID = "tenshu/health/service/registerHealthSource"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                string(id = Params.KIND)
                string(id = Params.DEVICE_ID)
                string(id = Params.DEVICE_LABEL)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val KIND = "$ID/kind"
        const val DEVICE_ID = "$ID/deviceId"
        const val DEVICE_LABEL = "$ID/deviceLabel"
    }
}
