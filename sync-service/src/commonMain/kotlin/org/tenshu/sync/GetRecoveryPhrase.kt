package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension for triggering the GetRecoveryPhrase operation.
 */
suspend fun SyncService.getRecoveryPhrase(): TResult<TString> =
    findOperation<GetRecoveryPhrase>(GetRecoveryPhrase.ID)
        .getRecoveryPhrase()
        .asTaskInteractor<TString>()
        .execute()

/**
 * Operation for generating or retrieving the current device's recovery phrase.
 */
interface GetRecoveryPhrase : Operation {

    fun getRecoveryPhrase(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getRecoveryPhrase"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {},
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
