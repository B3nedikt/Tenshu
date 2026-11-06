package org.tenshu.core.file.share

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Mode-A: invalidate a previously-minted share link. After this op completes
 * — relay-side `ShareRecord` is gone and the local [Share]'s `revoked` flag
 * is `true` (Eager-syncs to every realm device) — anyone hitting `GET
 * /share/{shareId}` gets 410 Gone.
 *
 * Capability-semantics caveat: a recipient who already loaded the file's
 * bytes still has them; revocation invalidates *future* opens only. The
 * Drive UI copy makes this explicit before the user taps Revoke
 * (`docs/file-sharing.md`).
 */
suspend fun FileSystem.revokeShareLink(shareId: String): TResult<Data?> =
    findOperation<RevokeShareLink>(RevokeShareLink.ID)
        .revokeShareLink(shareId)
        .asTaskInteractor<Data?>()
        .execute()

interface RevokeShareLink : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun revokeShareLink(shareId: String): RequestObject

    companion object {
        const val ID = "tenshu/file/share/revokeShareLink"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.SHARE_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SHARE_ID = "$ID/shareId"
    }
}
