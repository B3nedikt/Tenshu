package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ArrayOperationContext
import org.tenshu.core.common.proxy.DatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder

/**
 * The paths of [folderRow]'s children, or **null when its member list could not be read whole**.
 *
 * ⚠⚠ A relation read skips a member whose row the store does not hold and returns a shorter list
 * (`DatabaseImpl.reportMissingMembers`). A subtree walk that trusts that list acts on part of the
 * folder as if it were all of it, so this compares what was read against the references the list
 * row declares. A member whose path cannot be read counts as missing too.
 *
 * A folder with no list row at all has no members: nothing is referenced, so nothing can be missed.
 *
 * Used by `MoveFile`'s pre-mutation walk and [MoveNeedsLocalBytes].
 */
internal fun ExecutionContext.folderChildPathsOrNull(folderRow: Data): List<String>? {
    val filesField = runCatching {
        when (folderRow) {
            is Entity<*> -> folderRow.optionalFieldValue<Data>(Folder.Fields.FILES)
            is Folder -> folderRow.getField<Data>(Folder.Fields.FILES)
            else -> null
        }
    }.getOrElse { return null } ?: return emptyList()

    val arrayRef = when (filesField) {
        is ArrayDatabaseReference -> filesField
        is DatabaseReference if filesField.getOperationContext() is ArrayOperationContext ->
            (filesField.getOperationContext() as ArrayOperationContext).arrayDatabaseReference
        else -> null
    }
    val members = if (arrayRef != null) {
        val read = getArrayData(arrayRef)?.toList().orEmpty()
        val declared = database.getArrayMemberHashes(arrayRef)?.size
        // `null` is "no list row" (read is empty, so complete) or "this store cannot say" (a
        // non-empty read we cannot check, so not complete).
        if (declared == null && read.isNotEmpty()) return null
        if (declared != null && declared != read.size) return null
        read
    } else {
        (filesField as? TList<*>)?.toList() ?: return emptyList()
    }
    return members.map { child ->
        runCatching {
            when (child) {
                is Entity<*> -> child.optionalFieldValue<TString>(File.Field.PATH)?.get()
                is File -> child.path.get()
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    }
}
