package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Share the personal folder at [folderPath] into an already-bootstrapped shared
 * realm [realmId] (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3a,
 * additive model). For every file/folder under [folderPath] it mints a stable
 * cross-realm `fileId` and saves a [org.tenshu.core.file.shared.SharedFile]
 * locating it in the realm's internal tree — the shared folder's own children are
 * placed at the realm root (`SharedFile.ROOT_PARENT`), descendants under their
 * parent's `fileId`. **Not a move, not a copy:** the personal path-keyed `File`
 * records are untouched (D5) — this only *adds* `SharedFile` records. (The *connector*
 * — a per-member mount of the realm into a personal FS — is the separate `Placement`
 * type, created by `mountSharedRealm`; this op only populates the realm's content.)
 *
 * Realm bootstrap is its own concern: callers create the realm first via
 * `SyncService.createSharedRealm()` and pass the resulting `ownerId` as [realmId]
 * (keeps this op pure `:file-service`, no cross-service seam — §6 Phase 3a note).
 * Content fan-out into the realm and the shared-realm read path are Phase 3b/3c.
 *
 * **Idempotency (v1):** fileIds are minted fresh each call — re-sharing the same
 * folder produces new placements with new ids. A sidecar path→fileId mapping for
 * idempotent re-share is Phase 4 (alongside placement removal / GC).
 */
suspend fun FileSystem.shareFolderIntoRealm(
    folderPath: String,
    realmId: String,
): TResult<Data?> =
    findOperation<ShareFolderIntoRealm>(ShareFolderIntoRealm.ID)
        .shareFolderIntoRealm(folderPath, realmId)
        .asTaskInteractor<Data?>()
        .execute()

interface ShareFolderIntoRealm : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun shareFolderIntoRealm(folderPath: String, realmId: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/shareFolderIntoRealm"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.FOLDER_PATH, type = FilePath.Schema)
                string(id = Params.REALM_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val FOLDER_PATH = "$ID/folderPath"
        const val REALM_ID = "$ID/realmId"
    }
}
