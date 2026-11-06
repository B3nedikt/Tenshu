package org.tenshu.inference

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
 * Removes a checkpoint from this device (IN-2c / IN-D10).
 *
 * ⚠⚠ **Deleting a model must not silently disable the capability** — IN-D10 is explicit that a
 * screen has to say which of the two an action does. This op does exactly one of them: it removes
 * the model. Consent is `permittedInference`'s, and it is deliberately not touched here, so neither
 * action can quietly perform the other.
 *
 * ⚠⚠ **Success means the model is no longer used, not that its disk is free.** The index is
 * cleared here; the bytes are released when the proxy process next starts (F2v — a file is never
 * deleted ahead of an index write that may not be durable). A screen must say so and must not
 * report the space as reclaimed.
 *
 * ⚠ Fails with [ModelUnavailable] `NOT_INSTALLED` rather than succeeding silently: a delete that
 * reports success having found nothing hides a store that is not where the caller thinks it is.
 */
suspend fun InferenceService.deleteModel(modelId: String): TResult<Data?> =
    findOperation<DeleteModel>(DeleteModel.ID)
        .deleteModel(modelId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteModel : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteModel(modelId: String): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/deleteModel"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                string(Params.MODEL_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val MODEL_ID = "$ID/modelId"
    }
}
