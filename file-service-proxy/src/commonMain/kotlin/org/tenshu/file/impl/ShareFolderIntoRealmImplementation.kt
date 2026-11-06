package org.tenshu.file.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.generateId
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.ShareFolderIntoRealm
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.propertyRef
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.ensureCapacity
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * Phase 3a/3b.2 of `docs/proposals/drive-shared-realm-file-model.md` (additive
 * model): shares the personal folder at `folderPath` into the already-bootstrapped
 * shared realm `realmId`.
 *
 * **3a — structure:** the shared folder itself becomes a single **wrapper root**
 * [SharedFile] at [SharedFile.ROOT_PARENT] (carrying the folder's `name`,
 * `isFolder = true`); then mint a stable `fileId` per descendant and save a [SharedFile]
 * for it under the wrapper (direct children under the wrapper's `fileId`; deeper
 * descendants under their parent's `fileId`). The wrapper is what carries the folder's
 * human-readable name into the realm so a joiner — who only sees realm content, never the
 * owner's personal path or `Placement` — can read it (3d.2b). The personal path-keyed
 * `File` records are **untouched**; the `SharedFileRelationMaintainer` builds the realm's tree.
 *
 * **3b.2 — content fan-out (host side):** for each placed *file* (not folder), emit
 * its bytes into the **realm's** content store via the realm-aware
 * [org.tenshu.sync.common.FileContentSyncEmitter] — chunks are AEAD'd under the
 * realm's keys and the manifest binds to the `SharedFile`'s namespaced DataId
 * (routing keys off the `"$realmId::"` prefix). Reads the plaintext from the host's
 * own `FileDatabase` at the file's personal path — which the host does **not**
 * necessarily have (`File.Data` is `Syncable(OnDemand)`), so a pass-0 pre-flight
 * fetches every descendant's bytes first and refuses the share if any can't be
 * materialised. The recipient-side reassembly + on-demand read is Phase 3b.2b.
 *
 * Ordering (ADR 0015, mirroring `UploadFileImplementation`): the `SharedFile`
 * entity blocks are *enqueued* async by `saveData`, so we `awaitEmitIdle()` once
 * after the whole subtree is saved — guaranteeing every `SharedFile` entity block
 * is HLC-ordered ahead of its bytes (the joiner's `FileBytesPropertyWriter` drops
 * bytes that arrive before their entity).
 *
 * Pure `:file-service`: it takes an already-bootstrapped `realmId` (the caller
 * runs `SyncService.createSharedRealm()` first), so realm bootstrap stays in
 * `:sync-service` and this op needs no cross-service collaborator beyond the
 * nullable emit holder (null in test harnesses without sync ⇒ structure only).
 */
internal class ShareFolderIntoRealmImplementation(
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val folderPath: TString = params.read(ShareFolderIntoRealm.Params.FOLDER_PATH)
        val realmId: TString = params.read(ShareFolderIntoRealm.Params.REALM_ID)

        // Tier-2 storage gate (storage-capacity-gating.md §2). Folder sizes need a
        // tree walk, so v1 enforces the account requirement up-front (the
        // meaningful gate while usage is mock/zero), before minting any SharedFile.
        // Byte-precise folder enforcement lands with relay-reported usage (a size
        // pre-pass here). No-op when storage isn't wired/known.
        storageCapacityHolder.ensureCapacity(executionContext, requiredBytes = 0L)

        val folderDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = folderPath.get(),
        )

        // Pass 0 — refuse before minting if any file isn't shareable. Content-addressing
        // §3.4.1 3-2b: a file is shared cross-realm by re-wrapping its per-content key and
        // COPYING its already-encrypted chunk blobs from the relay into the target realm —
        // NOT by reading this device's plaintext. So the precondition is no longer "the
        // bytes are on THIS device" (they come from the relay-backed content store, which
        // holds them for any file any device uploaded) but "the file is content-addressed"
        // (has a `File.contentId`). Post-Phase-0-reset every uploaded file has one; the only
        // way this fails is a file whose upload hasn't finished stamping it — a genuine
        // "not ready yet" the caller should retry. Runs before any SharedFile is minted so
        // a folder that can't be fully shared fails cleanly instead of half-sharing.
        requireContentAddressed(executionContext, folderDataId)

        // The shared folder is represented in the realm by a single **wrapper root**
        // SharedFile at ROOT_PARENT, carrying the folder's name (`isFolder = true`);
        // its contents nest under that node's `fileId`. This is what lets a joiner —
        // who only ever sees realm content, never the owner's personal path or
        // `Placement` — read the folder's human-readable name straight from the realm
        // (`listSharedFolder(realmId, ROOT_PARENT)` surfaces the named folder). The
        // realm-name lives in the file domain (a SharedFile), not on the generic
        // `SharedRealmMembership`. See drive-shared-realm-file-model.md §6 (3d.2b).
        //
        // Pass 1 — save every SharedFile (the wrapper + the tree), collecting the
        // per-*file* byte emits as (SharedFile DataId, host personal path) pairs.
        val folderName = folderPath.get().substringAfterLast("/").ifEmpty { folderPath.get() }
        val rootFileId = generateId()
        executionContext.saveData(
            Entity.create<SharedFile>(
                context = executionContext.context,
                composite = SharedFile.create(
                    context = executionContext.context,
                    realmId = realmId.get(),
                    fileId = rootFileId,
                    parentFileId = SharedFile.ROOT_PARENT,
                    name = folderName,
                    isFolder = true,
                ),
            )
        )

        // A non-folder / missing path simply places nothing under the wrapper (the SDK
        // caller validates folder-ness; typed not-found errors are a follow-up).
        // Each leaf file contributes its personal `contentId` for the content re-share.
        val contentShares = mutableListOf<String>()
        placeChildren(
            executionContext = executionContext,
            parentFolderDataId = folderDataId,
            realmId = realmId.get(),
            parentFileId = rootFileId,
            contentShares = contentShares,
        )

        // Pass 2 — content re-share into the realm (§3.4.1 3-2b). No plaintext, no
        // re-encrypt: each file's per-content key is re-wrapped to the realm's volume key
        // and its already-encrypted chunk blobs are copied by ref from the relay-backed
        // personal store into the realm's content store. One drain barrier first so the
        // wrapper/tree entity blocks are HLC-ordered ahead of the content.
        val fileContentEmitter = syncBlockEmitterHolder?.fileContentEmitter
        if (fileContentEmitter != null && contentShares.isNotEmpty()) {
            syncBlockEmitterHolder.emitter?.awaitEmitIdle()
            contentShares.forEach { contentId ->
                fileContentEmitter.shareContentToRealm(
                    contentId = contentId,
                    targetRealmOwnerId = realmId.get(),
                    targetPropertyId = SharedFile.DATA_PROPERTY_ID,
                )
            }
        }

        return null as T
    }

    /**
     * Mint a [SharedFile] for each child of [parentFolderDataId], recursing into
     * subfolders. Each non-folder child's (SharedFile DataId, host personal path) is
     * appended to [byteEmits] for the content fan-out pass.
     */
    /**
     * Refuse the share (before minting any `SharedFile`) if any leaf file isn't
     * content-addressed — i.e. has no `File.contentId`. That id is what 3-2b re-wraps
     * the CEK + copies the encrypted blobs against; without it the file's content
     * cannot be re-shared. Throws a typed [NotFoundError] naming the offenders so a
     * folder that would only be *partly* shareable is refused outright rather than
     * minting records members can never open. Post-Phase-0-reset every uploaded file
     * carries a contentId, so this only fires mid-upload (a retryable "not ready").
     * The bytes themselves need NOT be on this device — the re-share reads them from
     * the relay-backed content store (§3.4.1 3-2b).
     */
    private fun requireContentAddressed(
        executionContext: ExecutionContext,
        folderDataId: DataId,
    ) {
        val paths = mutableListOf<String>()
        collectFilePaths(executionContext, folderDataId, paths)

        val unaddressed = paths.filter { path ->
            val entity = executionContext.database.getData(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = File.ID,
                    keyValue = path,
                ),
                asEntity = true,
            )
            entity == null || contentIdOf(executionContext, entity).isEmpty()
        }

        if (unaddressed.isNotEmpty()) {
            throw TenshuException(
                NotFoundError.create(
                    context = executionContext.context,
                    message = "Cannot share this folder: ${unaddressed.size} file(s) have " +
                        "no shareable content yet (e.g. '${unaddressed.first()}'). Wait for " +
                        "upload to finish, then share the folder."
                )
            )
        }
    }

    /**
     * Depth-first collect of every **leaf file** path under [dataId] (folders
     * excluded). Mirrors `OfflineKeeperImplementation.collectFilePaths`.
     */
    private fun collectFilePaths(
        executionContext: ExecutionContext,
        dataId: DataId,
        out: MutableList<String>,
    ) {
        val data = executionContext.database.getData(dataId, asEntity = true) ?: return
        if (data !is Entity<*>) return
        val isFolder = executionContext.schemaProvider.getSchema(data.type.get())
            ?.isSubtypeOf(Folder.ID) == true
        if (isFolder) {
            val filesField = data.getFieldValue<Data>(Folder.Fields.FILES)
            val children = executionContext.resolveList(filesField) ?: emptySequence()
            children.forEach { child ->
                executionContext.getDataId(child)?.let { collectFilePaths(executionContext, it, out) }
            }
        } else {
            runCatching { data.optionalFieldValue<TString>(File.Field.PATH)?.get() }
                .getOrNull()
                ?.let { out += it }
        }
    }

    private fun placeChildren(
        executionContext: ExecutionContext,
        parentFolderDataId: DataId,
        realmId: String,
        parentFileId: String,
        contentShares: MutableList<String>,
    ) {
        val folder = executionContext.database.getData(parentFolderDataId, asEntity = true)
        if (!isFolder(executionContext, folder)) return

        @Suppress("UNCHECKED_CAST")
        val folderEntity = folder as Entity<Folder>
        val filesField = folderEntity.getFieldValue<Data>(Folder.Fields.FILES)
        val children = executionContext.resolveList(filesField) ?: emptySequence()

        children.forEach { child ->
            val childId = executionContext.getDataId(child) ?: return@forEach
            // Re-fetch the full entity so a subfolder's own FILES array resolves on recursion.
            val childEntity = executionContext.database.getData(childId, asEntity = true) ?: return@forEach
            val childPath = pathOf(childEntity) ?: return@forEach
            val name = childPath.substringAfterLast("/").ifEmpty { childPath }
            val childIsFolder = isFolder(executionContext, childEntity)

            // Content-addressing Phase 3-2a (§3.4.1): carry the *personal* file's
            // stable `contentId` onto the SharedFile so its realm content manifest is
            // addressed on the content plane by that id (like a personal file), not by
            // the legacy entity-id mailbox block. Empty for a folder. This is also the
            // handle 3-2b re-wraps the CEK + copies the encrypted chunk blobs against.
            val contentId = if (childIsFolder) "" else contentIdOf(executionContext, childEntity)

            val fileId = generateId()
            executionContext.saveData(
                Entity.create<SharedFile>(
                    context = executionContext.context,
                    composite = SharedFile.create(
                        context = executionContext.context,
                        realmId = realmId,
                        fileId = fileId,
                        parentFileId = parentFileId,
                        name = name,
                        isFolder = childIsFolder,
                        contentId = contentId,
                    ),
                ),
            )

            if (childIsFolder) {
                placeChildren(executionContext, childId, realmId, fileId, contentShares)
            } else {
                // A file: schedule its content for cross-realm re-share by contentId. The
                // guard (requireContentAddressed) already refused an empty contentId, so
                // this is non-empty; the belt-and-braces filter keeps a stray legacy row
                // from emitting a no-op share rather than crashing.
                if (contentId.isNotEmpty()) contentShares += contentId
            }
        }
    }

    private fun isFolder(executionContext: ExecutionContext, data: Data?): Boolean {
        if (data !is Entity<*>) return false
        return executionContext.schemaProvider.getSchema(data.type.get())
            ?.isSubtypeOf(Folder.ID) == true
    }

    private fun pathOf(data: Data): String? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TString>(File.Field.PATH)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(File.Field.PATH).get() }.getOrNull()
        else -> null
    }

    /**
     * Resolve a personal file entity's `File.ContentId` value property (Phase 1 stamps
     * it at upload). Returns `""` if unresolvable (a legacy/test file with no stamped
     * contentId) — the SharedFile then falls back to the legacy mailbox manifest,
     * byte-identical to pre-3-2a Mode-B.
     */
    private fun contentIdOf(executionContext: ExecutionContext, entity: Data): String =
        runCatching {
            (executionContext.resolveProperty(entity, propertyRef(File.ContentId.id)) as? TString)?.get()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: ""
}
