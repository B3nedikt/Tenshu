package org.tenshu.core.file.system

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
 * Mount the shared realm [sharedRealmId] into this member's **personal** file system
 * at [parentPath], shown as [name] (`docs/proposals/drive-shared-realm-file-model.md`
 * §3 D3 / §6 Phase 3c.2). Creates (and `saveData`s) the member's
 * [org.tenshu.core.file.placement.Placement] connector — a personal-realm Syncable
 * keyed by [sharedRealmId], so it syncs across the member's own devices but is private
 * to them (it is *not* shared content).
 *
 * "Mounting" is the inbound counterpart of "sharing": the sharer's
 * `ShareFolderIntoRealm` populates the realm's `SharedFile` tree, and each member
 * (the sharer included) `mountSharedRealm`s it to surface that realm in their own FS.
 * One mount per realm per member in v1 — re-mounting the same realm overwrites the
 * placement's location/name (the key is [sharedRealmId]). Opening the mount = browsing
 * the realm via `listSharedFolder(sharedRealmId, …)` / `readSharedFile(sharedRealmId, …)`.
 *
 * Content-type-agnostic by construction: it references only a realm id, so the same op
 * mounts a future `ChatService` / `SharedDocument` realm unchanged (§4).
 */
suspend fun FileSystem.mountSharedRealm(
    sharedRealmId: String,
    parentPath: String,
    name: String,
): TResult<Data?> =
    findOperation<MountSharedRealm>(MountSharedRealm.ID)
        .mountSharedRealm(sharedRealmId, parentPath, name)
        .asTaskInteractor<Data?>()
        .execute()

interface MountSharedRealm : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun mountSharedRealm(sharedRealmId: String, parentPath: String, name: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/mountSharedRealm"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.SHARED_REALM_ID)
                string(id = Params.PARENT_PATH)
                string(id = Params.NAME)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SHARED_REALM_ID = "$ID/sharedRealmId"
        const val PARENT_PATH = "$ID/parentPath"
        const val NAME = "$ID/name"
    }
}
