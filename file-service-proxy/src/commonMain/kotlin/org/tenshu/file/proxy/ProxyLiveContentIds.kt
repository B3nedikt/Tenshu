package org.tenshu.file.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.sync.LiveContentIds
import org.tenshu.core.schema.isSubtypeOf

/**
 * The realm walk behind [LiveContentIds], and **the seam that decides whether ST-3b is safe at all**.
 *
 * ⚠⚠ `LiveContentIds` can only make an incomplete walk unmistakable if this reports a failure as
 * `null` rather than as an empty folder. Swallow a read error into an empty list and the whole
 * safety property is off — silently, with every test in the track still green.
 *
 * ## Where the line is drawn, and why it is not obvious
 *
 * `ExecutionContext.resolveList` cannot help: its own contract says `null` means *"not list-shaped —
 * callers treat it as empty or fail per their own contract"*, so it collapses "this folder is empty"
 * and "this field would not load" into one value. Reading that `null` as failure would abort on
 * every legitimately empty folder and stall reclamation forever; reading it as success is the bug
 * quoted above. So the distinction is drawn one level down, where it can actually be made:
 *
 * | Situation | Verdict | Why |
 * |---|---|---|
 * | the folder row itself will not load | **failure** | we were told it exists; if it will not load we know nothing about its subtree |
 * | `FILES` absent or not list-shaped | empty folder | indistinguishable from empty *by design*, and an empty folder owns no content |
 * | a child reference that resolves to no row | **failure** | a file we cannot read is a `contentId` we cannot see, which is exactly the miss that reclaims a live file's bytes |
 * | a child row with no path | **failure** | same: unidentifiable, so unaccountable |
 * | a file with no `contentId` | skipped | owns no blobs — pre-content-addressing, or bytes never uploaded |
 *
 * ⚠ Failure is therefore *"a row we were pointed at would not load"*, never *"a field was absent"*.
 * A dangling child reference mid-sync lands in the failure column on purpose: it means wait.
 */
internal fun ExecutionContext.reclamationChildren(folderPath: String): List<LiveContentIds.Node>? {
    val entity = loadFileEntity(folderPath) ?: return null

    // Not a folder: a leaf reached through a folder reference. It contributes no children; its own
    // contentId was already collected by whoever listed it.
    if (!isFolder(entity)) return emptyList()

    val filesField = runCatching { entity.getFieldValue<Data>(Folder.Fields.FILES) }.getOrNull()
        ?: return emptyList()
    val children = resolveList(filesField) ?: return emptyList()

    val nodes = mutableListOf<LiveContentIds.Node>()
    for (child in children) {
        val childId = getDataId(child) ?: return null
        val childEntity = database.getData(childId, asEntity = true) as? Entity<*> ?: return null
        val path = runCatching { childEntity.optionalFieldValue<TString>(File.Field.PATH)?.get() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        nodes += if (isFolder(childEntity)) {
            LiveContentIds.Node.Folder(path)
        } else {
            LiveContentIds.Node.File(
                path = path,
                // ⚠ Absent is legitimate here and only here — see the table above.
                contentId = runCatching { childEntity.get(File.ContentId)?.get() }.getOrNull(),
            )
        }
    }
    return nodes
}

/**
 * Every `contentId` any live file in this realm still carries, walked from the root.
 *
 * ⚠ The root is `"/"`, the same key `DeleteFileImplementation` derives a parent path down to. A
 * realm whose root row will not load reports [LiveContentIds.Result.Incomplete] rather than an empty
 * set — "I could not look" and "there is nothing" are the two answers this whole design exists to
 * keep apart.
 */
suspend fun ExecutionContext.liveContentIds(): LiveContentIds.Result =
    when (val walked = LiveContentIds.collect(root = ROOT_PATH) { path -> reclamationChildren(path) }) {
        is LiveContentIds.Result.Incomplete -> walked
        is LiveContentIds.Result.Complete -> LiveContentIds.Result.Complete(
            walked.contentIds + walked.contentIds.flatMap { derivedThumbnailContentIds(it) },
        )
    }

/**
 * ⚠⚠ **The invariant `content-plane-refcount.md` §9.6 states: declared ⇒ walkable.** A file's
 * thumbnails are `Syncable(OnDemand)` with their own **derived** content ids
 * (`FileBytesPropertyWriter.derivedThumbnailContentId`), so the emit declares an edge for each —
 * but the tree walk above sees only `File.ContentId`, and a walk that cannot name a declared id
 * cannot veto its reclamation.
 *
 * ⚠ The window is narrow but real: under §9.5's rule a live file's thumbnail edge is live, so it is
 * never a candidate. It becomes one across a **recreate** — the tombstone undeclares every edge the
 * holder had, a peer brings the row back, and the veto covers the file's own content while the five
 * thumbnails go unnamed. The file survives with its thumbnails reaped.
 *
 * ⚠ Derived rather than looked up, which is what makes this cheap: the ids are a pure function of
 * the file's own contentId, so no extra row is read and the walk stays one pass.
 */
private fun derivedThumbnailContentIds(fileContentId: String): List<String> =
    FileBytesPropertyWriter.THUMBNAIL_CLASSES.map { thumbnailClass ->
        FileBytesPropertyWriter.derivedThumbnailContentId(fileContentId, thumbnailClass)
    }

private const val ROOT_PATH = "/"

private fun ExecutionContext.loadFileEntity(path: String): Entity<*>? {
    val dataId = DataId.StringDataId.create(context = context, type = File.ID, keyValue = path)
    return database.getData(dataId, asEntity = true) as? Entity<*>
}

private fun ExecutionContext.isFolder(entity: Entity<*>): Boolean =
    runCatching { schemaProvider.getSchema(entity.type.get())?.isSubtypeOf(Folder.ID) == true }
        .getOrDefault(false)
