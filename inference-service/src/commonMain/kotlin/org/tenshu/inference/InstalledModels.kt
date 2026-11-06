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
 * What this device holds (IN-2c). Reads proxy-local state only — **no network**, so it answers on a
 * plane and it answers when the catalog cannot be reached.
 *
 * ⚠ Therefore it does *not* fail with `NO_CATALOG`: an empty list here genuinely means "this device
 * has no models", which is a true and useful sentence even when nothing can be fetched.
 */
suspend fun InferenceService.installedModels(): TResult<TList<InstalledModel>> =
    findOperation<InstalledModels>(InstalledModels.ID)
        .installedModels()
        .asTaskInteractor<TList<InstalledModel>>()
        .execute()

interface InstalledModels : Operation {

    override val schema: OperationSchema get() = Schema

    fun installedModels(): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/installedModels"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
