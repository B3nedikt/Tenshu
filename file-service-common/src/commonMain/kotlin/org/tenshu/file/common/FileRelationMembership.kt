package org.tenshu.file.common

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.RelationScope
import org.tenshu.core.common.relation.RelationSpec
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.Folder
import org.tenshu.core.file.shared.SharedFile

/**
 * The file service's three **entity-scoped** relations, declared as
 * [RelationSpec]s (`core-relation-system.md` §9.2.1 / R2). Registered with
 * `relationMembership(...)` in [fileServiceCommonModule]; the DSL verb's KDoc
 * explains why these declare here rather than on a schema.
 *
 * The shared shape: an element's parent is derived from the element's own key,
 * which is what lets one `(ExecutionContext, Data) -> DataId.EntityDataId?`
 * lambda serve both observer paths (the saved row on save, the element's own
 * `DataId` on delete — `ExecutionContext.getDataId` returns a `DataId`
 * unchanged, so the two collapse into one expression).
 */

/**
 * `Folder.FILES` — a folder's children, keyed under the *parent's* entity id.
 *
 * ⚠ **Where probe-first membership came from.** This is the relation whose raw
 * array order the user can see (Drive's default listing is unsorted), and
 * remove-then-add on every save reshuffled a re-saved file to the end — the list
 * visibly reordering itself after a detail-view round trip. The fix started here
 * as `MembershipOrder.Stable`, one relation opting out; R2b (§10.4) made it the
 * only behaviour, so this declaration no longer says anything about it.
 *
 * The element type is `File.ID`: `Folder` is a subtype, and the maintainer
 * matches with `isSubtypeOf`, so folders are members of their parent's array
 * too — the same set the old exact-match `typeId != File.ID && typeId !=
 * Folder.ID` test admitted.
 */
val FolderFilesMembership = RelationSpec(
    elementTypeId = File.ID,
    scope = RelationScope.EntityField(
        fieldRef = Folder.Fields.FILES,
        parentOf = { context, element ->
            // The root is bootstrapped by `initFileService` and has no parent.
            keyOf(context, element)
                ?.takeIf { it != "/" }
                ?.let { path ->
                    fileEntityId(
                        context,
                        path.substringBeforeLast("/").takeIf { it.isNotEmpty() } ?: "/",
                    )
                }
        },
    ),
)

/**
 * [File.FileHistory] — the File-rooted view of a file's append-only
 * [FileModification] log, as a per-file array keyed by the file's own `DataId`.
 * The entity-scoped counterpart of the realm-wide
 * `FileService.Relations.MODIFICATIONS`: same membership, O(this file's mods) to
 * read instead of a whole-log scan (`core-nested-projection.md` §3.3/§3.4).
 *
 * The parent falls out of the element's key: a `FileModification` is keyed by
 * `compositeKey = fileId|deviceId|timestamp` and `|` never occurs in a path, so
 * `substringBefore("|")` recovers the file path on both observer paths.
 */
val FileHistoryMembership = RelationSpec(
    elementTypeId = FileModification.ID,
    scope = RelationScope.EntityProperty(
        propertyId = File.FileHistory.id,
        parentOf = { context, element ->
            keyOf(context, element)
                ?.substringBefore("|")
                ?.takeIf { it.isNotEmpty() }
                ?.let { fileEntityId(context, it) }
        },
    ),
)

/**
 * `SharedFile.CHILDREN_RELATION_ID` — a shared realm's folder tree, derived from
 * its [SharedFile] rows (`drive-shared-realm-file-model.md` §6 Phase 3).
 *
 * ⚠⚠ **The one relation whose parent is NOT derivable from the element's key**,
 * and therefore the one place §10.1's single-lambda contract does not hold in
 * general: the parent is `SharedFile.parentFileId`, a *field*, while the row's
 * own key is `(realmId, fileId)`. It works today only because
 * [parentOf] returns `null` when it is fed a bare `DataId`, which reproduces
 * exactly the behaviour the hand-written `SharedFileRelationMaintainer` had —
 * its `onDataDeleted` is a deliberate no-op, because `SharedFile` *removal* is
 * Phase 4 and pruning the right parent array needs the row resolved before the
 * delete (or a tombstone carrying `parentFileId`).
 *
 * So: when Phase 4 lands, this spec cannot simply gain a delete path. It needs
 * either a resolve-before-delete or the two-lambda shape §10.1 names as the
 * honest fix. Recorded in the proposal's §10.2.
 */
val SharedFileChildrenMembership = RelationSpec(
    elementTypeId = SharedFile.ID,
    scope = RelationScope.EntityProperty(
        propertyId = SharedFile.CHILDREN_RELATION_ID,
        parentOf = { context, element ->
            val realmId = stringFieldOf(element, SharedFile.Field.REALM_ID)
            val parentFileId = stringFieldOf(element, SharedFile.Field.PARENT_FILE_ID)
            if (realmId == null || parentFileId == null) {
                null
            } else {
                DataId.StringDataId.create(
                    context = context.context,
                    type = SharedFile.ID,
                    keyValue = SharedFile.compositeKey(realmId, parentFileId),
                ) as DataId.EntityDataId
            }
        },
    ),
)

/** The `File`-rooted entity id for [path] — the array's parent key. */
private fun fileEntityId(context: ExecutionContext, path: String): DataId.EntityDataId =
    DataId.StringDataId.create(
        context = context.context,
        type = File.ID,
        keyValue = path,
    ) as DataId.EntityDataId

/**
 * The element's own key on both observer paths: the registered `DataIdProvider`
 * resolves it from a saved row, and a `DataId` is returned unchanged, so a
 * delete's bare id works without a second lambda.
 */
private fun keyOf(context: ExecutionContext, element: Data): String? =
    (context.getDataId(element) as? DataId.StringDataId)?.keyValue?.get()

/** A `TString` field off a row; `null` for a bare `DataId` (the delete path). */
private fun stringFieldOf(data: Data, fieldId: String): String? = when (data) {
    is DataId -> null
    is Entity<*> -> runCatching { data.optionalFieldValue<TString>(fieldId)?.get() }.getOrNull()
    is Composite -> runCatching { data.getField<TString>(fieldId).get() }.getOrNull()
    else -> null
}
