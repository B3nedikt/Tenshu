package org.tenshu.file.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ArrayOperationContext
import org.tenshu.core.common.proxy.DatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.ContentChangedFileModification
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.common.util.generateId
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.Size
import org.tenshu.core.file.path
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.StillSyncingError
import org.tenshu.core.id
import org.tenshu.core.time.Instant
import org.tenshu.core.time.clock
import org.tenshu.sync.common.DataIdMigrator
import org.tenshu.sync.common.retargetPendingContentEmits
import org.tenshu.sync.common.TombstoneEmitter

internal class MoveFileImplementation(
    private val fileDatabase: FileDatabase,
    private val tombstoneEmitter: TombstoneEmitter? = null,
    private val deviceId: String? = null,
    /**
     * Refuses — by throwing [org.tenshu.core.file.system.MoveNeedsLocalBytesError] — a move this
     * device must not make now, which is an OFFLINE move of a file, or a folder holding a file,
     * whose bytes are not here. See [MoveNeedsLocalBytes] for the rule.
     *
     * ⚠⚠ Consulted ONCE, for the whole operation, before the first mutation. [moveFile] below is
     * recursive and commits as it goes — it saves the destination folder, then moves children one
     * at a time — so a decision taken per child would leave a partially moved tree.
     *
     * ⚠ A lambda, so this operation stays free of the connectivity signals: it asks and nothing
     * more. Null (the default) never refuses — every test and unwired runtime.
     */
    private val localBytesGuard: (suspend (ExecutionContext, String) -> Unit)? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sourcePath: TString = params.read(MoveFile.Params.SOURCE_PATH)
        val targetPath: TString = params.read(MoveFile.Params.TARGET_PATH)

        // Cheap, non-mutating, and it must precede any answer: a missing source or an occupied
        // target answers as such, not as a connectivity refusal.
        validateMove(executionContext, sourcePath.get(), targetPath.get())

        // ⚠⚠ A provisional row, or a folder whose members are not all here, is refused before
        // anything else looks at it.
        refuseIfSubtreeIncomplete(executionContext, sourcePath.get())

        // ⚠⚠ The local-bytes refusal: before the first mutation, and once for the operation.
        localBytesGuard?.invoke(executionContext, sourcePath.get())

        val result = moveFile(executionContext, sourcePath.get(), targetPath.get())
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        return (result as TResult.Success<*>).value as T
    }

    /**
     * The three non-mutating preconditions of a move: the source exists, the target does not, and
     * the target's parent folder does. Returns the source row, which [moveFile] needs anyway.
     *
     * ⚠ Extracted so the provisional-row and local-bytes refusals run behind it: a move that could
     * never happen answers why it cannot, not "try again online". Cheap (two `getData`s).
     */
    private fun validateMove(
        executionContext: ExecutionContext,
        sourcePath: String,
        targetPath: String,
    ): Data {
        val sourceDataId = DataId.StringDataId.create(type = File.ID, keyValue = sourcePath)
        val sourceData = executionContext.database.getData(sourceDataId)
            ?: throw TenshuException(
                GenericError.create(
                    context = executionContext.context,
                    message = "Source file not found at $sourcePath"
                )
            )

        val targetDataId = DataId.StringDataId.create(type = File.ID, keyValue = targetPath)
        if (executionContext.database.getData(targetDataId) != null) {
            throw TenshuException(
                AlreadyExistsError.create(
                    context = executionContext.context,
                    message = "Target already exists at $targetPath"
                )
            )
        }

        // The destination has to exist as a folder. A rename is a move, so this is also what
        // stops a typed `sub/b.txt` from relocating a file into a folder that isn't there — the
        // client-side fence (`ios-drive-parity.md` §27.1) closes the reachable route, this closes
        // the state.
        // ⚠ Safe for the recursive folder branch below: the new folder is saved BEFORE its
        // children are moved into it, so each child's parent exists by the time it is checked.
        executionContext.requireParentFolder(targetPath)
        return sourceData
    }

    private suspend fun moveFile(
        executionContext: ExecutionContext,
        sourcePath: String,
        targetPath: String,
    ): TResult<Data?> {
        val sourceDataId = DataId.StringDataId.create(
            type = File.ID,
            keyValue = sourcePath
        )
        val targetDataId = DataId.StringDataId.create(type = File.ID, keyValue = targetPath)
        val sourceData = validateMove(executionContext, sourcePath, targetPath)

        val isFolder = isFolderRow(sourceData)

        // Generic value-property preservation (`file-content-addressing.md` §3.3.2 step 1): a move
        // must carry EVERY stored value property (contentId, contentHash, Size, CreatedAt, fileId,
        // thumbnails — and any value property added later) to the new path, or it silently
        // drops. The source loaded above with `asEntity = false` has an EMPTY `.properties`
        // map (value properties aren't inline on the entity), so reload it as a full entity:
        // `.properties` then holds every stored value property already encoded as `Data`, and
        // `include(...)` preserves the whole set generically — a new value property survives
        // with no change here. Only the intentional deltas are overridden per branch below
        // (LastModifiedAt = now; folder Size = 0). One entity load, cheaper than resolving each
        // property separately.
        val sourceProps =
            (executionContext.database.getData(sourceDataId, asEntity = true) as? Entity<*>)
                ?.properties?.mapKeys { it.key.get() }
                ?: emptyMap()

        // 1. Remove from old parent array
        val oldParentPath = sourcePath
            .substringBeforeLast("/")
            .takeIf { it.isNotEmpty() }
            ?: "/"

        val oldParentDataId = DataId.StringDataId.create(
            type = File.ID,
            keyValue = oldParentPath
        )

        val oldArrayDataId = DataId.EntityFieldDataId.create(
            type = TList.ID,
            parentEntity = oldParentDataId as DataId.EntityDataId,
            fieldRef = Folder.Fields.FILES
        )

        executionContext.database.removeFromArray(
            reference = ArrayDatabaseReference(
                arrayId = oldArrayDataId,
                isEntity = false,
                elementSchemaId = File.ID
            ),
            dataId = sourceDataId
        )

        // 2. Add to new parent array
        val newParentPath = targetPath
            .substringBeforeLast("/")
            .takeIf { it.isNotEmpty() }
            ?: "/"

        val newParentDataId = DataId.StringDataId.create(
            type = File.ID,
            keyValue = newParentPath
        )

        val newArrayDataId = DataId.EntityFieldDataId.create(
            type = TList.ID,
            parentEntity = newParentDataId as DataId.EntityDataId,
            fieldRef = Folder.Fields.FILES
        )

        if (isFolder) {
            val filesField = when (sourceData) {
                is Entity<*> -> {
                    sourceData.getFieldValue<Data>(Folder.Fields.FILES)
                }

                is Folder -> sourceData.getField<Data>(Folder.Fields.FILES)
                else -> null
            }

            val children: List<Data> = when (filesField) {
                is ArrayDatabaseReference -> {
                    executionContext
                        .getArrayData(filesField)
                        ?.toList()
                        ?: emptyList()
                }

                is DatabaseReference if filesField.getOperationContext() is ArrayOperationContext -> {
                    val arrayRef = (filesField.getOperationContext() as ArrayOperationContext)
                        .arrayDatabaseReference

                    executionContext
                        .getArrayData(arrayRef)
                        ?.toList()
                        ?: emptyList()
                }

                is TList<*> -> {
                    filesField.toList()
                }

                else -> {
                    emptyList()
                }
            }

            // Create the new folder entity. `include(sourceProps)` preserves every stored
            // value property generically (a folder is a tree node too — its stable fileId,
            // CreatedAt, etc. survive the move); we refresh size + lastModifiedAt — a move is
            // a modification, so lastModifiedAt is "now" and size is recomputed as children
            // are re-added via the recursive moves below.
            val now = executionContext.context.clock().nowEpochMillis()
            val folderEntity = Entity.create<Folder>(
                context = executionContext.context,
                composite = Folder.create(
                    context = executionContext.context,
                    path = targetPath,
                    files = emptyList() // Children will be added via recursive moves
                ),
            ) {
                include(sourceProps)
                set(File.Size, 0L)
                set(File.LastModifiedAt, now) // a move is a modification
            }

            // 1. Save NEW folder. Single `saveData` covers both local parent-FILES
            // membership (`FileRelationMembership.kt`) + outbound sync block
            // (`SyncObserver`). ⚠ Do not also call `addDataToArray`: both together
            // produce duplicate entries.
            executionContext.saveData(folderEntity)

            // 3. Recursively move children into the NEW folder
            children.forEach { child ->
                val childSourcePath = when (child) {
                    is Entity<*> -> @Suppress("UNCHECKED_CAST") (child as Entity<out File>).path.get()
                    is File -> child.path.get()
                    else -> ""
                }
                if (childSourcePath.isNotEmpty()) {
                    val childNameOnly = childSourcePath.substringAfterLast("/")
                    val childTargetPath =
                        if (targetPath == "/") "/$childNameOnly" else "$targetPath/$childNameOnly"
                    // Abort the cascade on a failed child move instead of silently skipping it:
                    // a dropped Failure leaves a partially-moved folder with no error. The caller
                    // sees the Failure and a retry folds: every re-move is content-addressed
                    // metadata (`file-content-addressing.md` §8.5).
                    val childResult = moveFile(executionContext, childSourcePath, childTargetPath)
                    if (childResult is TResult.Failure) {
                        return childResult
                    }
                }
            }
        } else {
            // Content addressing (file-content-addressing.md §8.5): a file's
            // ciphertext + manifest are bound to its stable random `contentId`, not its
            // path — so a move is PURE METADATA. Write a `File` at the new path carrying
            // the SAME `contentId` (preserved by `include(sourceProps)`, exactly as
            // `CreatedAt` survives a move); the bytes never move, are never re-encrypted,
            // and this device need not even hold them. Any peer resolves the moved file by
            // its unchanged contentId.
            if (sourcePath != targetPath) {
                // Best-effort O(1) local plaintext rename — a no-op when the bytes were
                // never downloaded here (a later OnDemand resolve fetches them by contentId
                // straight to the new path). Leaves NO 0-byte ghost at the source: a
                // 0-byte file is not an absent one (`fileSize` → `0L` not `null`), which
                // would read as "downloaded" and wedge the OnDemand fetch off permanently.
                fileDatabase.moveFile(sourcePath, targetPath)
            }

            val now = executionContext.context.clock().nowEpochMillis()

            // `include(sourceProps)` preserves every stored value property generically. The
            // load-bearing ones are contentId/contentHash — they keep the file content-addressed
            // at its new path so the same bytes resolve with no re-encryption/re-upload;
            // Size/CreatedAt/fileId/thumbnails ride along (and any value property added later is
            // preserved with no change here, §3.3.2 step 1). Only LastModifiedAt is refreshed — a
            // move is a modification. (`Share.fileId` / `FileModification.fileId` store the path,
            // not the stable fileId; §3.3.1.)
            val fileEntity = Entity.create<File>(
                context = executionContext.context,
                composite = File.create(
                    context = executionContext.context,
                    path = targetPath,
                ),
            ) {
                include(sourceProps)
                set(File.LastModifiedAt, now)
            }

            // Single `saveData` — observer maintains parent FILES membership + eager-syncs
            // the relocated metadata (incl. the unchanged contentId). No content re-emit.
            executionContext.saveData(fileEntity)
        }

        // Re-key the file's PRIOR history rows to the new path (`file-content-addressing.md`
        // §3.3.4). Done BEFORE the
        // migrator's `deleteData(sourceDataId)` (whose `cascadeDeleteHistory(sourcePath)` would
        // otherwise reap them — so without this a move DESTROYS history), and BEFORE the move's
        // own new modification is stamped below: a `FileModification`'s DataId is
        // `"$fileId|$deviceId|$timestamp"` with a wall-clock ms timestamp, so if a prior op and
        // this move land in the same millisecond a re-keyed row and the new row collide on the
        // same key; stamping the new row LAST makes it win that (rare) race — a "keep the newest
        // event" degradation — while the common distinct-ms case keeps the full history. The cascade later reaps only the `sourcePath` rows, so the
        // re-keyed `targetPath` rows survive on every device: history follows the file across the
        // move. (`Share.fileId` is NOT rewritten: a Mode-A share is a relay-side snapshot, so its
        // stored path is not load-bearing; §3.3.)
        if (sourcePath != targetPath) {
            rekeyHistory(executionContext, sourcePath, targetPath)
        }

        // Emit-side history hook (docs/proposals/file-service-history.md §4.1,
        // §9.11): a move within the same parent is a Rename (carries old/new
        // name); a move to a different parent is a Move (carries old/new parent
        // path). Splitting them at emit time means the history UI never has to
        // reverse-engineer intent from a path diff. Recursive child moves each
        // emit their own record — a child's parent path always changes, so each
        // is a Move. The record is keyed to the file's NEW path identity
        // (targetPath). No-op without a deviceId.
        if (deviceId != null) {
            val modifiedAt = executionContext.context.clock().nowEpochMillis()
            val modification = if (oldParentPath == newParentPath) {
                RenamedFileModification.create(
                    context = executionContext.context,
                    fileId = targetPath,
                    timestamp = modifiedAt,
                    deviceId = deviceId,
                    oldName = sourcePath.substringAfterLast("/"),
                    newName = targetPath.substringAfterLast("/"),
                )
            } else {
                MovedFileModification.create(
                    context = executionContext.context,
                    fileId = targetPath,
                    timestamp = modifiedAt,
                    deviceId = deviceId,
                    oldParentPath = oldParentPath,
                    newParentPath = newParentPath,
                )
            }
            executionContext.saveData(modification)
        }

        if (sourcePath != targetPath) {
            // Retire the OLD path via the generic DataId-migration primitive (§3.3.4).
            // It tells peers the old key is gone (else a device still holding `sourcePath` shows a
            // **duplicate** at both paths), sweeps stale `SyncMetadata`
            // at both hashes, and drops the orphaned old-path entity locally (`removeFromArray`
            // above only detached it from the parent listing). Runs AFTER the new-entity + history
            // creates so the create is enqueued no later than the tombstone (§3.3.3.D). A folder
            // move tombstones each descendant via its own recursive call above; here the folder's
            // own now-empty FILES array is cleaned via `extraLocalCleanup`.
            // ⚠⚠ The deferred content intents follow the row. They are keyed on their CONTENT, which
            // a move does not change, but they name this row as their holder and — for
            // `File.Data` — this path as where the bytes are, and the migration below retires the
            // one while `fileDatabase.moveFile` above already relocated the other. Retargeted before
            // the retirement, so a crash in between leaves intents naming a row that still exists.
            // ⚠ A drain landing between the byte move and this line finds no bytes where the entry
            // says; it keeps the entry because the row still names the content, and drains it from
            // the new path next pass.
            retargetPendingContentEmits(
                executionContext = executionContext,
                oldDataId = sourceDataId,
                newDataId = targetDataId,
                oldFilePath = sourcePath,
                newFilePath = targetPath,
            )
            DataIdMigrator(executionContext, tombstoneEmitter).migrate(
                oldDataId = sourceDataId,
                newHash = targetDataId.getDataIdHash().toString(),
                entityType = File.ID,
                extraLocalCleanup = {
                    if (isFolder) {
                        // The moved folder's OWN FILES array (keyed on its old path), now
                        // emptied by the recursive child relocations above — distinct from
                        // `oldArrayDataId`, which is the *parent's* FILES array.
                        executionContext.deleteData(
                            DataId.EntityFieldDataId.create(
                                type = TList.ID,
                                parentEntity = sourceDataId as DataId.EntityDataId,
                                fieldRef = Folder.Fields.FILES,
                            )
                        )
                    }
                },
            )
        }

        return TResult.Success.create<Data?>(context = executionContext.context, value = null)
    }

    private fun isFolderRow(data: Data): Boolean =
        if (data is Entity<*>) data.type.get() == Folder.ID else data.id == Folder.ID

    /**
     * Refuse the move, with [StillSyncingError], while any part of the subtree under [sourcePath]
     * is not wholly on this device. Two cases, one walk:
     *
     * - **A provisional slice-snapshot row** (`content-plane-cr2-b5-spec.md` §4,
     *   `content-plane-cr2-quorumless.md` §10.11). ⚠⚠ Such a row names its content id as of the snapshot. Moving it pushes that id
     *   under the new path at a fresh HLC and tombstones the old path at a fresh HLC, so every peer
     *   follows this device's stale copy over the newer one. Checked only while the probe says such
     *   rows can exist at all (a device still catching up).
     * - **A folder whose member list reads short**: it names a child whose row this store
     *   does not hold, or one whose path cannot be read. ⚠⚠ [moveFile] moves only the children it
     *   can read and then retires the folder, so the unseen child would be left naming a folder that
     *   no longer exists: a file no folder lists. Retrying once the row is here moves it along.
     *
     * ⚠ The whole subtree, before the first write: [moveFile] commits as it goes, so a refusal
     * found halfway down would leave a partly moved tree. A file move walks nothing.
     */
    private fun refuseIfSubtreeIncomplete(executionContext: ExecutionContext, sourcePath: String) {
        val probe = tombstoneEmitter?.provisionalRows?.takeIf { it.mayExist() }
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(sourcePath))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!visited.add(path)) continue
            val dataId = DataId.StringDataId.create(context = executionContext.context, type = File.ID, keyValue = path)
            if (probe?.isProvisional(dataId) == true) {
                throw stillSyncing(executionContext, "$path is still syncing to this device; it can be moved once it has caught up")
            }
            val row = executionContext.database.getData(dataId) ?: continue
            if (!isFolderRow(row)) continue
            queue += executionContext.folderChildPathsOrNull(row) ?: throw stillSyncing(
                executionContext,
                "Part of $path has not reached this device yet; it can be moved once everything in it has arrived",
            )
        }
    }

    private fun stillSyncing(executionContext: ExecutionContext, message: String) = TenshuException(
        StillSyncingError.create(context = executionContext.context, message = message)
    )

    /**
     * Re-key every prior [FileModification] of [sourcePath] to [targetPath]
     * (`file-content-addressing.md` §3.3.4).
     * Enumerated by a linear filter over the realm-wide `MODIFICATIONS` log (the same pattern
     * `enforceRetention`/`cascadeDeleteHistory` use — there is no predicate-query API at the op
     * layer). The snapshot (`toList()`) is taken before any save, so the re-keyed rows appended
     * during iteration aren't re-processed. Each re-key is a fresh `saveData` (⇒ a new `Syncable`
     * block + `MODIFICATIONS`/`File.FileHistory` membership on every device); the old
     * `sourcePath` rows are then reaped by the cascade the migrator triggers.
     */
    private fun rekeyHistory(
        executionContext: ExecutionContext,
        sourcePath: String,
        targetPath: String,
    ) {
        val modificationsRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = FileService.Relations.MODIFICATIONS,
            ),
            isEntity = false,
            elementSchemaId = FileModification.ID,
        )
        val rows = executionContext.database.getArrayData(modificationsRef)?.toList() ?: return
        rows.forEach { row ->
            if (fileIdOf(row) != sourcePath) return@forEach
            val rekeyed = rekeyRow(executionContext, row, targetPath) ?: return@forEach
            executionContext.saveData(rekeyed)
        }
    }

    /**
     * Reconstruct [row] as the same [FileModification] subtype but with `fileId = [newFileId]`
     * (⇒ a new `compositeKey` ⇒ a new DataId). Preserves `timestamp`/`deviceId` and each
     * subtype's payload. Returns null for an unknown subtype (skip — cannot reconstruct).
     */
    private fun rekeyRow(
        executionContext: ExecutionContext,
        row: Data,
        newFileId: String,
    ): FileModification? {
        val ctx = executionContext.context
        val timestamp = timestampOf(row)
        val deviceId = stringFieldOf(row, FileModification.Field.DEVICE_ID) ?: return null
        return when (typeIdOf(row)) {
            CreatedFileModification.ID ->
                CreatedFileModification.create(ctx, newFileId, timestamp, deviceId)

            ContentChangedFileModification.ID ->
                ContentChangedFileModification.create(
                    ctx, newFileId, timestamp, deviceId,
                    manifestId = stringFieldOf(row, ContentChangedFileModification.Field.MANIFEST_ID)
                        ?: return null,
                )

            RenamedFileModification.ID ->
                RenamedFileModification.create(
                    ctx, newFileId, timestamp, deviceId,
                    oldName = stringFieldOf(row, RenamedFileModification.Field.OLD_NAME) ?: return null,
                    newName = stringFieldOf(row, RenamedFileModification.Field.NEW_NAME) ?: return null,
                )

            MovedFileModification.ID ->
                MovedFileModification.create(
                    ctx, newFileId, timestamp, deviceId,
                    oldParentPath = stringFieldOf(row, MovedFileModification.Field.OLD_PARENT_PATH)
                        ?: return null,
                    newParentPath = stringFieldOf(row, MovedFileModification.Field.NEW_PARENT_PATH)
                        ?: return null,
                )

            else -> null
        }
    }

    // Field readers — a row is an `Entity` or a bare `Composite` depending on how it was
    // materialised.
    private fun fileIdOf(data: Data): String? = stringFieldOf(data, FileModification.Field.FILE_ID)

    private fun typeIdOf(data: Data): String = (data as? Entity<*>)?.type?.get() ?: data.id

    private fun timestampOf(data: Data): Long = when (data) {
        is Entity<*> -> runCatching {
            data.getFieldValue<Instant>(FileModification.Field.TIMESTAMP).toEpochMilliseconds()
        }.getOrNull()
        is Composite -> runCatching {
            data.getField<Instant>(FileModification.Field.TIMESTAMP).toEpochMilliseconds()
        }.getOrNull()
        else -> null
    } ?: 0L

    private fun stringFieldOf(data: Data, fieldId: String): String? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TString>(fieldId)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(fieldId).get() }.getOrNull()
        else -> null
    }
}
