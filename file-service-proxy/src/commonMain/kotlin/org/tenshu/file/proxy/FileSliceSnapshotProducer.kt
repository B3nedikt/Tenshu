package org.tenshu.file.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.EncodedSlice
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SliceSnapshotProducer
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.time.UnixInstant
import org.tenshu.file.common.slice.FileSliceEntry
import org.tenshu.sync.common.resolveListField

/**
 * Produces `Folder.FILES` slice entries — Drive's half of the joiner snapshot
 * (`sync-device-catch-up.md` §5.7.2, C8).
 *
 * One slice per folder, keyed by the folder's entity id, which is what
 * `Sliceable(SliceKey.PerEntity)` on `File` declares: the user's own tree already partitions
 * the relation, so there is no bucket boundary to choose. That is why `Folder.FILES` ships
 * first — the realm-wide singletons need a time bucket and land in C9.
 */
class FileSliceSnapshotProducer : SliceSnapshotProducer {

    override val relationIds: Set<String> = setOf(Folder.Fields.FILES)

    /**
     * Every folder in the realm, by entity id.
     *
     * ⚠ Walks from root rather than scanning the store for `Folder` rows: the store is not
     * enumerable by type, and the tree walk is the shape the writer's idle+charging pass can
     * afford. Bounded by the folder count, not the file count — a 700 k-file realm with a few
     * thousand folders yields a few thousand slices, which is the point.
     */
    override fun sliceKeys(executionContext: ExecutionContext, relationId: String): List<String> {
        if (relationId !in relationIds) return emptyList()
        val keys = ArrayList<String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(ROOT_PATH))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!seen.add(path)) continue
            val folder = executionContext.database.getData(entityId(executionContext, path))
                ?: continue
            if (path != ROOT_PATH && !isFolder(folder)) continue
            keys += path
            childPaths(executionContext, folder).forEach { childPath ->
                // Only folders become slices; a plain file is an *entry* in its parent's.
                if (isFolder(executionContext.database.getData(entityId(executionContext, childPath)))) {
                    queue += childPath
                }
            }
        }
        return keys
    }

    /**
     * The entries of one folder.
     *
     * ⚠ Returns `null` — never a partial slice — when the folder itself cannot be read. A
     * missing slice degrades to an ordinary relation read; a *wrong* slice would render a
     * folder that silently omits files, which is far worse.
     */
    override fun encodeSlice(
        executionContext: ExecutionContext,
        relationId: String,
        sliceKey: String,
    ): EncodedSlice? {
        if (relationId !in relationIds) return null
        val folder = executionContext.database.getData(entityId(executionContext, sliceKey)) ?: return null

        val entries = childPaths(executionContext, folder).map { childPath ->
            val child = executionContext.database.getData(entityId(executionContext, childPath))
                ?: return null
            FileSliceEntry(
                entityId = childPath,
                name = childPath.substringAfterLast('/'),
                isFolder = isFolder(child),
                size = longProperty(executionContext, child, File.Size.id) ?: 0,
                // ⚠ contentId is what lets a joiner reach the content plane for this file's
                // bytes AND — since C7, via `derivedThumbnailContentId` — for either thumbnail
                // class, without first holding the `File` entity. That is why no thumbnail ref
                // is carried: it is derivable from this.
                contentId = stringProperty(executionContext, child, File.ContentId.id).orEmpty(),
                contentHash = stringProperty(executionContext, child, File.ContentHash.id).orEmpty(),
                createdAtMillis = longProperty(executionContext, child, File.CreatedAt.id) ?: 0,
                lastModifiedAtMillis = longProperty(executionContext, child, File.LastModifiedAt.id) ?: 0,
            )
        }
        return EncodedSlice(entryCount = entries.size, entries = FileSliceEntry.encode(entries))
    }

    /**
     * The root folder — the one coordinate a device can name before it holds any local state.
     *
     * Everything below root is reachable *from* root: a slice's folder entries tell the reader
     * which child slices exist, so navigation discovers the rest as the user opens folders.
     * Seeding more here would fetch a tree nobody has asked to see yet.
     */
    override fun bootstrapSliceKeys(
        executionContext: ExecutionContext,
        relationId: String,
    ): List<String> = if (relationId in relationIds) listOf(ROOT_PATH) else emptyList()

    /**
     * Materialise a fetched slice: the folder itself if this device lacks it, then its children.
     *
     * ⚠⚠ **Create-only.** An entity that already exists is left exactly as it is, because an
     * entry is a *projection* — it carries name/size/contentId/times and nothing else, so
     * writing it over a real row would discard every property the projection omits. The cost of
     * skipping is that a stale local row waits for the log to update it; the cost of not
     * skipping is silent data loss.
     *
     * ⚠ No `SyncMetadata` is written, deliberately — see [SliceSnapshotProducer.applySlice]. A
     * row with no metadata loses to the first real block that targets it, which is precisely
     * the resolution a provisional row wants.
     *
     * ⚠ The parent's `FILES` membership is **not** written here: saving a child fires
     * `ParentFilesRelationMaintainer`, exactly as `CreateFolder`/`UploadFile` rely on. Writing
     * it as well is what produced duplicate entries when that maintainer was worked around.
     */
    override fun applySlice(
        executionContext: ExecutionContext,
        relationId: String,
        sliceKey: String,
        entries: ByteArray,
        isDeleted: (DataId) -> Boolean,
    ): Int {
        if (relationId !in relationIds) return 0
        val decoded = FileSliceEntry.decode(entries) ?: return 0
        // ⚠⚠ A folder deleted since the slice was sealed takes its whole slice with it: recreating it
        // would hang every entry under a folder the log has already removed (§11.4.4). ⚠ Only when it
        // is absent here: a folder deleted and later recreated at the same path keeps its tombstone
        // record, and must still get its slice.
        if (!exists(executionContext, sliceKey) && isDeleted(entityId(executionContext, sliceKey))) return 0

        var created = 0
        // The slice's own folder first: a child saved before it exists has no parent row for
        // the relation maintainer to hang it on. Root is seeded by `initFileService`, so this
        // only ever fires for a slice fetched by navigation.
        if (!exists(executionContext, sliceKey)) {
            executionContext.saveData(
                folderEntity(executionContext, sliceKey, createdAtMillis = 0, lastModifiedAtMillis = 0),
                origin = WriteOrigin.SYNC_INGEST,
            )
            created++
        }

        decoded.forEach { entry ->
            if (exists(executionContext, entry.entityId)) return@forEach
            // ⚠⚠ Deleted since the slice was sealed: a tombstone already ingested will not come
            // again, so a row recreated here would stay for good (§11.4.4).
            if (isDeleted(entityId(executionContext, entry.entityId))) return@forEach
            val data = if (entry.isFolder) {
                folderEntity(
                    executionContext = executionContext,
                    path = entry.entityId,
                    createdAtMillis = entry.createdAtMillis,
                    lastModifiedAtMillis = entry.lastModifiedAtMillis,
                )
            } else {
                fileEntity(executionContext, entry)
            }
            executionContext.saveData(data, origin = WriteOrigin.SYNC_INGEST)
            created++
        }
        return created
    }

    private fun exists(executionContext: ExecutionContext, path: String): Boolean =
        executionContext.database.getData(entityId(executionContext, path)) != null

    private fun folderEntity(
        executionContext: ExecutionContext,
        path: String,
        createdAtMillis: Long,
        lastModifiedAtMillis: Long,
    ): Data = Entity.create<Folder>(
        context = executionContext.context,
        composite = Folder.create(executionContext.context, path, files = emptyList()),
    ) {
        set(File.Size, 0L)
        set(File.CreatedAt, createdAtMillis)
        set(File.LastModifiedAt, lastModifiedAtMillis)
    }

    /**
     * ⚠ `contentId`/`contentHash` are set only when the entry carries them. The producer
     * encodes an absent one as `""`, and storing that empty string would make a file with no
     * bytes yet look like a file whose content is addressed by the empty id.
     */
    private fun fileEntity(executionContext: ExecutionContext, entry: FileSliceEntry): Data =
        Entity.create<File>(
            context = executionContext.context,
            composite = File.create(executionContext.context, entry.entityId),
        ) {
            set(File.Size, entry.size)
            set(File.CreatedAt, entry.createdAtMillis)
            set(File.LastModifiedAt, entry.lastModifiedAtMillis)
            if (entry.contentId.isNotEmpty()) set(File.ContentId, entry.contentId)
            if (entry.contentHash.isNotEmpty()) set(File.ContentHash, entry.contentHash)
        }

    /**
     * The folder's direct children, by path.
     *
     * ⚠ Via [resolveListField] — the documented lazy-vs-materialised seam (`FolderMerger` is
     * its first consumer) — rather than a hand-built `ArrayDatabaseReference`. A folder's
     * `FILES` is a composite **field**, so the field resolver is what materialises it whether
     * it is stored inline or deferred.
     */
    private fun childPaths(executionContext: ExecutionContext, folder: Data): List<String> =
        executionContext.resolveListField(folder, Folder.Fields.FILES)
            .mapNotNull { member ->
                when (member) {
                    is Entity<*> -> (member.getFieldValue(File.Field.PATH) as? TString)?.get()
                    is File -> member.path.get()
                    else -> null
                }
            }
            .toList()

    /**
     * ⚠⚠ Every file **and folder** is keyed under [File.ID], never `Folder.ID`: `Folder`'s
     * parent schema is `File`, so it inherits `FileDataIdProvider` and its rows land in the
     * same keyspace (`requireParentFolder` looks a parent folder up exactly this way).
     * Keying folders on `Folder.ID` silently finds nothing — the store is not enumerable, so
     * it reads as "this folder does not exist" rather than as an error.
     */
    private fun entityId(executionContext: ExecutionContext, path: String) =
        DataId.StringDataId.create(executionContext.context, File.ID, path)

    /** Folder-ness is a property of the stored entity, not of its key — see [entityId]. */
    private fun isFolder(data: Data?): Boolean = when (data) {
        null -> false
        is Folder -> true
        is Entity<*> -> data.type.get() == Folder.ID
        else -> false
    }

    private fun stringProperty(executionContext: ExecutionContext, data: Data, propertyId: String): String? =
        (executionContext.resolveProperty(data, propertyRef(propertyId)) as? TString)
            ?.get()?.takeIf { it.isNotEmpty() }

    private fun longProperty(executionContext: ExecutionContext, data: Data, propertyId: String): Long? =
        when (val resolved = executionContext.resolveProperty(data, propertyRef(propertyId))) {
            is TInteger -> resolved.get()
            is UnixInstant -> resolved.get()
            else -> null
        }

    private companion object {
        const val ROOT_PATH = "/"
    }
}
