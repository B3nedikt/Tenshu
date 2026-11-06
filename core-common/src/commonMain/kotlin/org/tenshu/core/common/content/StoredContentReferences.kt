package org.tenshu.core.common.content

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.trait.ContentReferencing

/**
 * ⭐ **A bare composite never clears content** (`content-plane-cr2-quorumless.md` §10.12 Q5, decided
 * 2026-10-02, option d).
 *
 * A save replaces the whole stored entry, and a bare [Composite] carries no property map, so saving one
 * over a row that references content wipes the row's content-id slot (B2 audit W8).
 * Under CR-2 B that wipe reaches the relay as `(property, none)` and the content is freed after T, so
 * an importer re-recording a workout as a summary (Health Connect with route consent withheld) would
 * lose its streams for every device that has not fetched them yet.
 *
 * This keeps the stored [ContentReferencing.CONTENT_ID] slot across such a save, for every type whose schema declares content (B3a), with no per-writer code. The
 * fields still update. ⚠ What it does **not** do:
 *  - An [Entity] save keeps replace semantics, so a deliberate clear is still expressible: save an
 *    Entity without the slot.
 *  - Only a row of the **same type** is carried over. `Folder` and `File` share one key space, and a
 *    folder replacing a file at the same `DataId` is a write that clears the file's content.
 *  - Only the declared slot is kept, never other properties (relations such as `Folder.files`
 *    may rely on the replace; unaudited).
 *  - Sync ingest is not touched: a peer's row is applied as the peer wrote it (whole-row LWW).
 *
 * ⚠ Cost: one index lookup ([Database.contains]) per bare save of a content-referencing type, and one
 * read only when the row exists, so a fresh import of new summary rows reads nothing.
 */
class StoredContentReferences(
    private val registry: SchemaRegistry,
    private val database: Database,
    private val context: TContext,
) {
    private val declarations = ContentDeclarationsCache(registry)

    /** [data], or an [Entity] of it carrying the stored row's content slots; see the class KDoc. */
    fun keptAcross(data: Data, dataIdOf: (Data) -> DataId?): Data {
        if (data is Entity<*> || data !is Composite) return data
        val typeId = data.id
        if (!declarations.get().referencesContent(typeId)) return data
        val slot = registry.resolveValueRequirement(typeId, ContentReferencing.CONTENT_ID)?.id ?: return data
        val dataId = dataIdOf(data) ?: return data
        if (!database.contains(dataId)) return data
        val stored = database.getData(dataId, asEntity = true) as? Entity<*> ?: return data
        if (stored.type.get() != typeId) return data
        val kept = stored.getProperty<Data>(slot) ?: return data
        return Entity.create<Composite>(context = context, composite = data, properties = mapOf(slot to kept))
    }
}
