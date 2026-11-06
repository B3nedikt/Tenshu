package org.tenshu.inference

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The models this device could install — the **verified** catalog, filtered to what this build can
 * run (IN-2c / IN-D10).
 *
 * ⚠⚠ Fails with [ModelUnavailable] `NO_CATALOG` rather than returning an empty list when there is
 * nothing to trust: no signing key pinned, or no catalog published. An empty list means *the
 * catalog was read and offers this device nothing*, which is a different sentence and today an
 * untrue one.
 *
 * ⚠ Hits the network (proxy-side, House Rule #1 — apps stay INTERNET-free), so it is an
 * [IoOperation] and can take as long as a CDN takes.
 */
suspend fun InferenceService.availableModels(): TResult<TList<AvailableModel>> =
    findOperation<AvailableModels>(AvailableModels.ID)
        .availableModels()
        .asTaskInteractor<TList<AvailableModel>>()
        .execute()

interface AvailableModels : Operation {

    override val schema: OperationSchema get() = Schema

    fun availableModels(): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/availableModels"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
