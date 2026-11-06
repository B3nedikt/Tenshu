package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.sync.DeclarableContent
import org.tenshu.core.file.sync.LiveContentIds

/**
 * The backfill's enumeration (`content-plane-refcount.md` §9.7, CR-4d): every
 * `(holder, propertyId, contentId)` this realm's live files still carry.
 *
 * ## ⚠ Why this is not [liveContentIds]
 *
 * The **veto** needs content ids alone, and gets them from the same walk. A **declaration** needs
 * the holder and the property too, because an edge is keyed `(owner, holder, property)` — and a
 * holder's relay-visible name is a keyed hash, i.e. **one-way**, so a client can never recover a
 * holder from an edge it already sent. Provenance has to come forward from the walk or not at all.
 * ⚠ It comes forward as a `DataId`: the sync layer names it (EI-1), not this module.
 *
 * ## ⚠⚠ Incomplete is *not* fatal here, unlike the veto
 *
 * The two directions are opposite. A short **veto** fails to protect and deletes live content. A
 * short **backfill** declares fewer edges, so fewer things become candidates — a leak, never a
 * loss. So this returns whatever it walked *and says whether the walk finished*, and the caller
 * declares what it has while refusing to record itself complete. Collapsing the two would either
 * strand every realm with one unreadable folder, or latch "done" over a partial pass.
 */
suspend fun ExecutionContext.declarableContent(): DeclarableContent.Result {
    // ⚠ Its own walk rather than `LiveContentIds.collect`, because that one discards the path and
    // the path is half of an edge's key. Same `reclamationChildren` contract underneath, so the
    // incomplete-vs-empty distinction is the one already reasoned about there.
    val nodes = mutableListOf<Pair<String, String>>() // path to contentId
    var complete = true
    val pending = ArrayDeque(listOf("/"))
    val visited = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
        val folder = pending.removeFirst()
        if (!visited.add(folder)) continue
        val children = reclamationChildren(folder)
        if (children == null) {
            complete = false
            continue
        }
        for (child in children) {
            when (child) {
                is LiveContentIds.Node.Folder -> pending += child.path
                is LiveContentIds.Node.File ->
                    child.contentId?.takeIf { it.isNotBlank() }?.let { nodes += child.path to it }
            }
        }
    }

    val entries = buildList {
        for ((path, contentId) in nodes) {
            val holder = DataId.StringDataId.create(context = context, type = File.ID, keyValue = path)
            add(DeclarableContent.Entry(holder, File.Data.id, contentId))
            // ⚠ Every thumbnail class declares its OWN derived id — §9.6's invariant, and
            // the same table the emit declares from, so the two cannot drift.
            for ((propertyId, thumbnailClass) in THUMBNAIL_PROPERTIES) {
                add(
                    DeclarableContent.Entry(
                        holder = holder,
                        propertyId = propertyId,
                        contentId = FileBytesPropertyWriter
                            .derivedThumbnailContentId(contentId, thumbnailClass),
                    ),
                )
            }
        }
    }
    return DeclarableContent.Result(entries = entries, walkComplete = complete, rowsRead = nodes.size)
}

/**
 * ⚠ Derived from the one rung table (`ThumbnailClasses`) so a new thumbnail class extends the emit, the
 * veto and the backfill together. The property id is needed here as well as the class, because it is
 * half the edge's key. ⚠⚠ It used to be a second hand-written list whose KDoc claimed exactly this
 * derivation (`drive-xxlarge-thumbnail-rung.md` §3).
 */
private val THUMBNAIL_PROPERTIES: List<Pair<String, String>> =
    ThumbnailClasses.ALL.map { thumbnailClass -> thumbnailClass.property.id to thumbnailClass.id }
