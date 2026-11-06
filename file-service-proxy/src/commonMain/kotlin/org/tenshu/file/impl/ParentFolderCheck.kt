package org.tenshu.file.impl

import org.tenshu.core.NotFoundError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.id

/**
 * Refuse to write a `File`/`Folder` row whose **parent folder does not exist**.
 *
 * ⚠⚠ Every writer here checked only that the *target* path was free, never that the path it was
 * about to occupy had somewhere to hang: creating `/Photos/a/b` with no `/Photos/a` row wrote a
 * row addressable by path and present in **no folder's `FILES` list** — reachable by a direct
 * `getData`, invisible in every listing, and undeletable through the UI. The
 * `ParentFilesRelationMaintainer` cannot save it either: it appends to the parent's array, and
 * that array's owning entity is not there.
 *
 * This is the proxy-side twin of the client-side fences on the rename and new-folder dialogs
 * (`ios-drive-parity.md` §27.1/§28), which reject a `/` in a typed name. Those close the one
 * *reachable* route to this state; this closes the state itself, for every caller — including the
 * ones a UI does not mediate (the JS SDK, a third-party app over EPC, a future import path).
 *
 * The root always exists — `initFileService` seeds the `"/"` row — so a top-level write passes.
 * Shared-realm content is a different type ([org.tenshu.core.file.shared.SharedFile], keyed
 * `"$realmId::…"`) with its own tree maintainer, and never reaches these ops.
 */
internal fun ExecutionContext.requireParentFolder(targetPath: String) {
    val parentPath = targetPath.substringBeforeLast("/").ifEmpty { "/" }
    // The root has no parent to check, and neither does a path with no separator (which the
    // callers normalise to a root-relative one before getting here).
    if (parentPath == targetPath) return

    val parent = database.getData(
        DataId.StringDataId.create(context = context, type = File.ID, keyValue = parentPath)
    ) ?: throw TenshuException(
        NotFoundError.create(
            context = context,
            message = "No folder at $parentPath to hold $targetPath"
        )
    )

    val parentIsFolder = when (parent) {
        is Entity<*> -> parent.type.get() == Folder.ID
        else -> parent.id == Folder.ID
    }
    if (!parentIsFolder) {
        throw TenshuException(
            NotFoundError.create(
                context = context,
                message = "$parentPath is a file, not a folder, so it cannot hold $targetPath"
            )
        )
    }
}
