package org.tenshu.inference

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
 * Downloads a checkpoint into the proxy's canonical store and returns the installed record
 * (IN-2c / IN-D10, `inference-service.md` §22).
 *
 * ⚠⚠ **The only route by which a model reaches a device.** Nothing downloads on its own: IN-D3
 * keeps inference off by default and IN-D10 requires the user to see what a model costs first, so
 * this exists to be called by a deliberate tap and by nothing else.
 *
 * ⚠ One-shot — it returns when the whole checkpoint has landed, which for the pinned SigLIP is
 * ~383 MB and minutes. The work runs on a process-lifetime scope proxy-side and holds the platform
 * liveness while it does, so backgrounding the app does not kill it; a second call for the same
 * model **joins** the running download rather than starting a second.
 *
 * ⚠ Failures are typed [ModelUnavailable]: `NO_CATALOG`, `UNKNOWN_MODEL`, `TRANSPORT_FAILED` and
 * `INTEGRITY_FAILED` need four different sentences and two different buttons.
 */
suspend fun InferenceService.downloadModel(modelId: String): TResult<InstalledModel> =
    findOperation<DownloadModel>(DownloadModel.ID)
        .downloadModel(modelId)
        .asTaskInteractor<InstalledModel>()
        .execute()

interface DownloadModel : Operation {

    override val schema: OperationSchema get() = Schema

    fun downloadModel(modelId: String): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/downloadModel"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                string(Params.MODEL_ID)
            },
            returnType = InstalledModel.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val MODEL_ID = "$ID/modelId"
    }
}
