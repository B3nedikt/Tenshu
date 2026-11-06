package org.tenshu.core.file.share

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Mode-A: mint a fresh anonymous share link for the file at [path] (Drive
 * "Link" action — `docs/file-sharing.md`).
 *
 * Returns a [ShareLinkResult] composite carrying the relay-assigned `shareId`
 * + the per-share key fragment the caller turns into the copy-able URL. The
 * op *also* writes a local [Share] entity (`Syncable(Eager)`) so the *My
 * shares* list across realm devices shows the new link immediately.
 *
 * `expiresAtMillis` and `maxOpens` use the `0L` sentinel for "no expiry" /
 * "no cap" (see [Share.UNSET_INT_FIELD]) — the schema-DSL int field isn't
 * nullable today, and a sentinel is cheaper than introducing an
 * optional-int wrapper for two opt-in knobs.
 */
suspend fun FileSystem.shareLink(
    path: String,
    expiresAtMillis: Long = Share.UNSET_INT_FIELD,
    maxOpens: Long = Share.UNSET_INT_FIELD,
): TResult<ShareLinkResult?> =
    findOperation<ShareLink>(ShareLink.ID)
        .shareLink(path, expiresAtMillis, maxOpens)
        .asTaskInteractor<ShareLinkResult?>()
        .execute()

interface ShareLink : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun shareLink(
        path: String,
        expiresAtMillis: Long,
        maxOpens: Long,
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/share/shareLink"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.PATH, type = FilePath.Schema)
                int(id = Params.EXPIRES_AT_MILLIS)
                int(id = Params.MAX_OPENS)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PATH = "$ID/path"
        const val EXPIRES_AT_MILLIS = "$ID/expiresAtMillis"
        const val MAX_OPENS = "$ID/maxOpens"
    }
}
