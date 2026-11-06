package org.tenshu.core.ingest

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
 * Ask the desktop Control host to present a native single-folder picker and return an
 * opaque, Control-owned security-scoped grant. The grant is intentionally not a path:
 * the sandboxed Control process creates the bookmark and later resolves it while ingesting.
 *
 * An empty string means the platform has no picker or the user cancelled.
 */
suspend fun IngestService.pickSecurityScopedFolder(): TResult<TString> =
    findOperation<PickSecurityScopedFolder>(PickSecurityScopedFolder.ID)
        .pickSecurityScopedFolder()
        .asTaskInteractor<TString>()
        .execute()

interface PickSecurityScopedFolder : Operation {
    override val schema get() = Schema

    fun pickSecurityScopedFolder(): RequestObject

    companion object {
        const val ID = "${IngestService.ID}/pickSecurityScopedFolder"

        val Schema = operation(
            id = ID,
            receiver = IngestService.Schema.toType(),
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
