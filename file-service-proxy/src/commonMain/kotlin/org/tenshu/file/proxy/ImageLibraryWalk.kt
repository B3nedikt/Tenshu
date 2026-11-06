package org.tenshu.file.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.arrayReferenceOrNull
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isTrashedPath
import org.tenshu.core.get
import org.tenshu.core.schema.isSubtypeOf

/**
 * Every image path in the realm's file tree, depth-first.
 *
 * Mirrors `PhotosCapturedBetweenImplementation.collectPhotos` and
 * `OfflineKeeperImplementation.collectFilePaths` — a folder is a `File` subtype carrying a
 * `Folder.Fields.FILES` array. Lives here because walking the file tree is a file-service concern;
 * its first consumer is the inference indexer, which reaches it through a seam rather than a
 * dependency (`MediaSource`).
 *
 * ⚠⚠ **Whole rows, and that is affordable.** `inference-service.md` IN-D6 warns that a naive
 * walk materialises every stored value property "including `File.Thumbnail` blobs" and dies on heap
 * long before it saturates compute. Thumbnails are a `dynamicProperty` on the content plane, not
 * stored value properties — as stored values they were ~97 % of a `File` row's bytes — so a row is
 * ~336 B of metadata and arrives without them. `PhotosCapturedBetween` reads whole rows on exactly
 * this basis.
 *
 * ⚠ Re-introducing a stored blob property on `File` makes the IN-D6 warning true again, and this
 * walk is one of the places that would die first.
 *
 * ⚠ "Is it an image" is the **name**'s answer (`fileCategoryForName`) — the same derivation Drive
 * uses for row glyphs and preview routing, so a screenshot is an image like any other.
 *
 * ⚠ Trashed paths are skipped: a photo the user deleted must not come back as a search result.
 */
fun ExecutionContext.collectImagePaths(): List<String> = walkImageLibrary().paths

/**
 * What [walkImageLibrary] found, and **whether it saw the whole tree**.
 *
 * @property complete `false` when any part of the tree could not be read: a referenced child
 *   with no row, an unresolved reference, a folder whose list is missing or not list-shaped, or a
 *   file whose path cannot be read. ⚠ Skipping a trashed file or a non-image is not incompleteness — those
 *   are decisions, not gaps.
 */
class ImageLibraryWalk(val paths: List<String>, val complete: Boolean)

/**
 * [collectImagePaths], plus whether the result is the **whole** library.
 *
 * ⚠⚠ **Why the second half exists.** The walk is lenient on purpose — a
 * subtree it cannot read is skipped, so one dangling reference never stops a whole index pass. But
 * that makes its list unfit to be read as "everything the user has": a device mid-sync can hold a
 * folder whose children have not arrived yet, and a reconcile trusting that list would erase the
 * face templates and vectors of photographs that still exist. Anything that deletes by absence
 * must ask [ImageLibraryWalk.complete] first.
 *
 * ⚠⚠ **`complete` is not "a snapshot", and nothing may delete on it alone.** The
 * walk reads the tree row by row with no lock held across it, and `MoveFile` unlinks its source
 * from the old parent before it saves the destination. A walk overlapping a move can therefore miss
 * a file — or a moving folder's whole subtree — whose row still exists, having read nothing
 * unreadable: `complete` is true and the list is short. The store offers nothing that would close
 * that here:
 *  - `Database.transaction` is the store-wide lock, which a whole-library walk must not hold — and
 *    a move is not one transaction, so even a walk under the lock could land between its writes;
 *  - there is no store revision to compare, **and one would not be enough**: a move that unlinked
 *    its source before the walk began and saves its destination after the walk ended changes
 *    nothing *during* the walk. Before and after would match over a tree that was short throughout.
 *
 * So a caller that deletes by absence owes a **second proof per path**: [livePathsAmong], a point
 * read of the row itself. A path this walk missed whose row still exists is not dead. That is the
 * contract `MediaWalk.complete` encodes in `:inference-service-proxy` — a complete walk cannot be
 * built without that read — and `ImageLibraryWalkTest` pins the window it exists for.
 */
fun ExecutionContext.walkImageLibrary(): ImageLibraryWalk {
    val out = mutableListOf<String>()
    val gaps = Gaps()
    val rootId = DataId.StringDataId.create(context = context, type = File.ID, keyValue = "/")
    collectImagePaths(rootId, out, gaps)
    return ImageLibraryWalk(out, complete = gaps.count == 0)
}

private class Gaps {
    var count = 0
}

private fun ExecutionContext.collectImagePaths(dataId: DataId, out: MutableList<String>, gaps: Gaps) {
    val data = database.getData(dataId, asEntity = true)
    if (data !is Entity<*>) {
        gaps.count++
        return
    }

    val isFolder = schemaProvider.getSchema(data.type.get())?.isSubtypeOf(Folder.ID) == true
    if (isFolder) {
        val filesField = data.getFieldValue<Data>(Folder.Fields.FILES)
        val children = resolveList(filesField)?.toList()
        if (children == null) {
            gaps.count++
            return
        }
        // ⚠⚠ **The resolved list hides two gaps, so the raw one is counted.** A
        // stored list that is MISSING reads as empty, and a member whose row is gone is silently
        // SKIPPED — both indistinguishable from a smaller folder. The list's own member hashes are
        // what was referenced: fewer resolved children than hashes is a dangling member, and no
        // answer at all (`null`) is a missing list or a store that cannot say. An empty folder
        // reads as `[]`, never as `null`.
        arrayReferenceOrNull(filesField)?.let { reference ->
            val referenced = database.getArrayMemberHashes(reference)
            if (referenced == null || children.size < referenced.size) gaps.count++
        }
        children.forEach { child ->
            val childId = getDataId(child)
            if (childId == null) gaps.count++ else collectImagePaths(childId, out, gaps)
        }
        return
    }

    val path = runCatching { data.optionalFieldValue<TString>(File.Field.PATH)?.get() }.getOrNull()
    if (path == null) {
        gaps.count++
        return
    }
    if (isTrashedPath(path)) return
    if (fileCategoryForName(path.substringAfterLast('/')) != FileCategory.Image) return
    out += path
}
