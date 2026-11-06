package org.tenshu.core.health

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
 * The per-source kill switch (`docs/proposals/fit-data-sources-restructure.md` D4). Flips an
 * existing [HealthSource]'s `autoImport`, preserving the descriptor and the denormalised run
 * trio. A no-op if no row exists for ([kind], [deviceId]).
 *
 * ⚠ **This works across devices, and the asymmetry is the design.** The owning device honours
 * the new value on its next foreground — there is no push. We cannot revoke HealthKit's
 * permission from an Android phone, but this field is ours; what stays device-local is
 * *triggering* an import and *fixing* permissions, which is why the detail screen disables
 * "Import now" for a source on another device rather than pretending it can run there.
 */
suspend fun HealthService.setSourceAutoImport(
    kind: String,
    deviceId: String,
    autoImport: Boolean,
): TResult<Data?> =
    findOperation<SetSourceAutoImport>(SetSourceAutoImport.ID)
        .setSourceAutoImport(kind, deviceId, autoImport)
        .asTaskInteractor<Data?>()
        .execute()

interface SetSourceAutoImport : Operation {

    override val schema: OperationSchema get() = Schema

    fun setSourceAutoImport(
        kind: String,
        deviceId: String,
        autoImport: Boolean,
    ): RequestObject

    companion object {
        const val ID = "tenshu/health/service/setSourceAutoImport"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                string(id = Params.KIND)
                string(id = Params.DEVICE_ID)
                boolean(id = Params.AUTO_IMPORT)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val KIND = "$ID/kind"
        const val DEVICE_ID = "$ID/deviceId"
        const val AUTO_IMPORT = "$ID/autoImport"
    }
}
