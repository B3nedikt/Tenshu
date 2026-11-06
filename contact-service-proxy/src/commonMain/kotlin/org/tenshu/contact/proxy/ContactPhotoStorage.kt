package org.tenshu.contact.proxy

import org.tenshu.contact.ContactSource
import org.tenshu.contact.ContentId
import org.tenshu.contact.Photos
import org.tenshu.contact.common.Reassembly
import org.tenshu.contact.common.photoRefsIn
import org.tenshu.contact.common.reassembleVCard
import org.tenshu.contact.common.relocatablePhotosNotIn
import org.tenshu.contact.common.relocatePhotos
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.content.contentEdgeOf
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.util.generateId
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.schema.propertyRef

/**
 * The external-store key for a card's relocated `PHOTO` lines (`contacts-app.md` §9.10). Keyed by
 * the **content id**, not the source key: a source key is `"<origin>:<id>"` with whatever an
 * exporter put in its UID, and a new set of photos is a new id, so a superseded blob is a separate
 * file to delete. ⚠ The one string the writer, the resolver, the save and the tests must agree on.
 */
fun contactPhotosPath(contentId: String): String = "/.contacts/photos/$contentId"

/** The `DataId` of the source row keyed [sourceKey]. */
internal fun contactSourceDataId(executionContext: ExecutionContext, sourceKey: String): DataId =
    DataId.StringDataId.create(
        context = executionContext.context,
        type = ContactSource.ID,
        keyValue = sourceKey,
    )

/** The stored row's content id, or `null` when it moved no photos. */
internal fun storedContentIdOf(row: Data?): String? =
    (row as? Entity<*>)?.let { runCatching { it.get(ContactSource.ContentId)?.get() }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

/** The stored row's `personId`, from either shape the store hands back. */
internal fun storedPersonIdOf(row: Data?): String? = when (row) {
    is Entity<*> -> runCatching { row.getFieldValue<TString>(ContactSource.Field.PERSON_ID).get() }.getOrNull()
    is ContactSource -> row.personId.get()
    else -> null
}

/** The stored row's card, from either shape the store hands back. */
internal fun storedCardOf(row: Data?): String? = when (row) {
    is Entity<*> -> runCatching { row.getFieldValue<TString>(ContactSource.Field.RAW_VCARD).get() }.getOrNull()
    is ContactSource -> row.rawVcard.get()
    else -> null
}

/** What [writeContactSource] did. */
internal enum class ContactSourceWrite {
    /** Saved; the row names no photos. */
    SAVED_INLINE,

    /** Saved; the row names photos on the content plane. */
    SAVED_WITH_PHOTOS,

    /** Not written: the caller's [writeContactSource] `expect` no longer held for the stored row. */
    SKIPPED,
}

/**
 * ⚠⚠ **The one writer of a [ContactSource] row** (`contacts-app.md` §9.10). Every save — an edit,
 * an import, the relocation pass — goes through here, because the row's content id lives in its
 * property map and must be stated on every save: kept when the photos are, blank when they are gone.
 *
 * What it does with [card]:
 *
 *  - **no placeholder** (an import, a fresh card, a row with its photos inline): its big `PHOTO` lines move
 *    ([relocatePhotos]); the blob is written, the row saved with a fresh content id, and the blob
 *    emitted after the row (ADR 0015 entity-before-bytes). If the moved set is the one already
 *    stored, nothing is re-uploaded and the content id is kept.
 *  - **placeholders** (an edit merged into a stored card): they must be the ones the stored row
 *    holds, or the save is refused. With the photos local the card is reassembled and re-split, so
 *    a `PHOTO` the edit added moves too; without them the card is stored as it is under the existing
 *    content id — an offline rename works — unless it also carries a new big `PHOTO`, which cannot
 *    be merged into a blob that is not here and is refused rather than stored inline.
 *
 * ## ⚠⚠ Concurrency
 *
 *  - **One writer at a time, and the save lands on the row it was decided from.** Everything runs
 *    under [ContactProxyComponents.sourceWriteLock]; the plan is made outside the store's lock, and
 *    the commit re-reads the row inside one `database.transaction` — the lock `deleteData` holds
 *    across its fanout and every ingest takes — and saves only if the row is still the one planned
 *    from, else plans again ([planAndCommit]). [expect] is judged on that row: the relocation pass
 *    passes the snapshot it built its list from, and a row that changed or went meanwhile is
 *    [ContactSourceWrite.SKIPPED] rather than overwritten with a stale card.
 *  - **The row and its upload intent are one span.** From the save to `journalFileContent` nothing
 *    can be cancelled (`withContext(NonCancellable)`, the upload producer's shape): a cancel in the
 *    entity-before-bytes wait would otherwise leave a row naming photos no journal ever uploads. The
 *    upload itself stays cancellable after the span; the journal owns its retry.
 *  - **Nothing is deleted here.** A superseded blob may be the only plaintext of content a peer still
 *    names (an offline edit that wins last-writer), so it becomes a sweep candidate
 *    ([ContactPhotoSupersededRecorder]) and goes only once no stored row references it
 *    ([sweepSupersededContactPhotos]).
 */
internal suspend fun writeContactSource(
    executionContext: ExecutionContext,
    components: ContactProxyComponents,
    sourceKey: String,
    personId: String,
    card: String,
    expect: (stored: Data?) -> Boolean = { true },
    /**
     * Other rows to save in the SAME store transaction as this one — the import's `Person`. Run
     * only when the row commits, so a refusal, a cancel at the lock wait or a shutdown leaves
     * neither: never a name-only person that a retry would duplicate.
     */
    alsoInCommit: () -> Unit = {},
): ContactSourceWrite {
    val dataId = contactSourceDataId(executionContext, sourceKey)
    val holder = components.syncBlockEmitterHolder
    val contentEmitter = holder?.fileContentEmitter

    // ⚠⚠ Lock order: `sourceWriteLock`, THEN at most one content key, and never a content key held
    // while waiting for `sourceWriteLock`. So no `withContentEmitOwnership` (the file producer's
    // shape), which takes the OLD content's key before the Mutex: save A, holding the Mutex, commits
    // C2 and parks in `awaitEmitIdle`; save B of the same source takes C2's key and waits for the
    // Mutex; A's journal then needs C2's key — a cycle inside `NonCancellable`, every later write and
    // sweep stuck behind it. The ownership is not needed here
    // at all: a photo blob's path is its content id, so bytes under a key never change, which is the
    // hazard ownership exists for (a file overwritten in place under a drain).
    suspend fun write(): ContactSourceWrite {
        val planned = components.sourceWriteLock.withLock {
            withContext(NonCancellable) {
                val decided = planAndCommit(executionContext, components, dataId, sourceKey, personId, card, expect, alsoInCommit)
                val path = decided.newPhotosPath
                if (path != null) {
                    // Entity-before-bytes (ADR 0015): `saveData` only enqueues the row's block. ⚠ Bounded:
                    // under NonCancellable a wedged emit consumer would otherwise hold the save for ever;
                    // on a timeout the journal below still records the intent, which is the bytes.
                    withTimeoutOrNull(components.emitIdleBoundMs) { holder?.emitter?.awaitEmitIdle() }
                    // ⚠⚠ The durable intent, inside the span — see the KDoc. ⚠⚠ Bound to the id this save
                    // COMMITTED: a peer's row ingested during the wait may already name other content,
                    // and the unbound call reads the id off the row.
                    contentEmitter?.journalFileContent(dataId, ContactSource.Photos.id, path, decided.contentId!!, decided.contentHash)
                }
                decided
            }
        }
        val path = planned.newPhotosPath ?: return planned.outcome
        if (contentEmitter != null) {
            // Chunked + CEK-sealed, addressed by the content id — the manifest rides the content plane.
            contentEmitter.emitFileContent(
                targetEntityId = dataId,
                propertyId = ContactSource.Photos.id,
                filePath = path,
                contentId = planned.contentId!!,
                contentHash = planned.contentHash,
            )
        } else {
            // No content emitter (harnesses without a relay content store): the single property block,
            // so the receiver-side writer still works. A null holder means no sync at all.
            components.fileDatabase.readBytes(path)?.let { bytes ->
                holder?.emitter?.emitPropertyBlock(targetEntityId = dataId, propertyId = ContactSource.Photos.id, value = bytes)
            }
        }
        return planned.outcome
    }
    return write()
}

/** [newPhotosPath] and [contentId] are set together: the blob this save committed, and its id. */
private class Decided(
    val outcome: ContactSourceWrite,
    val newPhotosPath: String? = null,
    val contentId: String? = null,
    /**
     * `wholeFileHashOf` the new blob. ⚠⚠ Journalled with the intent: without a hash the drain
     * retires an entry once the row names other content, and a contact row moves and comes back —
     * so a photo only journalled (a Wi-Fi-only policy on cellular) would be dropped when a peer's
     * row arrived, and lost everywhere when the peer's write then lost to an offline edit.
     */
    val contentHash: String? = null,
)

/** What a save will write: the card to store and the content id it names. */
private class Plan(
    val outcome: ContactSourceWrite,
    val card: String = "",
    val contentId: String? = null,
    val newPhotosPath: String? = null,
    val contentHash: String? = null,
)

/** The three facts a plan was made from; a commit proceeds only if the row still has them. */
private data class RowState(val card: String?, val personId: String?, val contentId: String?)

private fun rowStateOf(row: Data?): RowState? =
    row?.let { RowState(storedCardOf(it), storedPersonIdOf(it), storedContentIdOf(it)) }

/** How many times a save re-plans when a writer outside [ContactProxyComponents.sourceWriteLock] got there first. */
private const val COMMIT_ATTEMPTS = 3

/**
 * Plan outside the store's lock, commit inside it.
 *
 * ⚠ The multi-MB work — reading the old blob (once), reassembling, splitting, writing the new blob —
 * happens **before** `database.transaction`, so it never stalls ingest or IPC reads on the store
 * lock. The transaction only re-reads the row and saves: if a writer that does not take
 * `sourceWriteLock` (a delete, a merge's relink, a peer's row) changed it since the plan, the plan is
 * thrown away and made again from the new row, at most [COMMIT_ATTEMPTS] times. [expect] is judged on
 * the row the plan was made from, which the commit then proves is still the stored one.
 *
 * ⚠ A freshly written blob is recorded as a sweep candidate before the commit: if the save is
 * skipped, refused or throws, nothing names it and the sweep collects it; once the row names it, the
 * sweep drops it from the candidates instead.
 */
private fun planAndCommit(
    executionContext: ExecutionContext,
    components: ContactProxyComponents,
    dataId: DataId,
    sourceKey: String,
    personId: String,
    card: String,
    expect: (stored: Data?) -> Boolean,
    alsoInCommit: () -> Unit,
): Decided {
    repeat(COMMIT_ATTEMPTS) {
        val stored = executionContext.database.getData(dataId, asEntity = true)
        if (!expect(stored)) return Decided(ContactSourceWrite.SKIPPED)
        val plan = plan(executionContext, components, stored, card)
        val committed = executionContext.database.transaction {
            val now = executionContext.database.getData(dataId, asEntity = true)
            if (rowStateOf(now) != rowStateOf(stored)) {
                false
            } else {
                alsoInCommit()
                saveContactSourceRow(executionContext, sourceKey, personId, plan.card, plan.contentId)
                true
            }
        }
        if (committed) {
            return Decided(plan.outcome, plan.newPhotosPath, plan.contentId.takeIf { plan.newPhotosPath != null }, plan.contentHash)
        }
        println("writeContactSource -> $sourceKey changed while it was being saved; planning again")
    }
    refuse(executionContext, "This contact changed while it was being saved. Try again.")
}

/** The decision, from [stored]. Writes nothing but a fresh blob (recorded as a sweep candidate). */
private fun plan(
    executionContext: ExecutionContext,
    components: ContactProxyComponents,
    stored: Data?,
    card: String,
): Plan {
    val storedContentId = storedContentIdOf(stored)
    val fileDatabase = components.fileDatabase

    val refs = photoRefsIn(card)
    if (refs.isNotEmpty()) {
        val known = storedCardOf(stored)?.let { photoRefsIn(it) }.orEmpty().map { it.key }.toSet()
        if (storedContentId == null || refs.any { it.key !in known }) {
            refuse(executionContext, "The card names photos this contact does not hold.")
        }
    }

    // Read once: the reassembly and the "same photos?" comparison below both use it.
    val storedBlob: ByteArray? = storedContentId?.let { fileDatabase.readBytes(contactPhotosPath(it))?.get() }

    val full: String? = when {
        refs.isEmpty() -> card
        storedBlob == null -> null
        else -> when (val reassembled = reassembleVCard(card, storedBlob)) {
            is Reassembly.Complete -> reassembled.card
            else -> refuse(executionContext, "The contact's stored photos do not match its card.")
        }
    }

    if (full == null) {
        // Placeholders, and the photos are not on this device: keep them as they are, unless the
        // card carries a photo that would have to join them.
        // ⚠ "New" against the STORED card, not a size margin:
        // `mergeIntoVCard` re-folds every line at 75 octets with CRLF (~4% on an unfolded photo), so
        // a photo that sat inline just under the 64 KiB threshold, or a card just under the budget,
        // would otherwise be refused on its first offline edit for a photo nobody added. A photo
        // already inline in the stored row is never new, however it is folded now.
        val newPhotos = relocatablePhotosNotIn(card = card, existing = storedCardOf(stored).orEmpty())
        if (newPhotos > 0) {
            refuse(
                executionContext,
                "This contact's photos are not on this device yet, so a new photo cannot be added. " +
                    "Try again once it has synced.",
            )
        }
        return Plan(ContactSourceWrite.SAVED_WITH_PHOTOS, card, storedContentId)
    }

    val relocated = relocatePhotos(full)
    val photos = relocated.photos ?: return Plan(ContactSourceWrite.SAVED_INLINE, full, contentId = null)

    // The same photos as already stored: nothing new to upload, the edge stays where it is.
    if (storedContentId != null && storedBlob?.contentEquals(photos) == true) {
        return Plan(ContactSourceWrite.SAVED_WITH_PHOTOS, relocated.card, storedContentId)
    }

    // ⚠ Fresh per relocation (see `ContactSource.ContentId`): the superseded blob is left declared
    // with no live edge — reclaimable — rather than overwritten under an id still declared.
    val contentId = generateId()
    val path = contactPhotosPath(contentId)
    // A candidate before it exists, so no failure from here on can leave it named by nothing and
    // known to nothing.
    executionContext.database.transaction { ContactPhotoCandidates(fileDatabase).add(contentId) }
    // Local write first: `GetContactSourcePhotos` resolves from here, so an export on this device
    // works the moment the op returns — sync or no sync.
    fileDatabase.writeByteArray(filePath = path, bytes = TByteArrayDto(photos))
    // ⚠⚠ The row names the id before the emit: `resolveContentId` reads the STORED row, so an id
    // written later is invisible to the emit it exists for.
    return Plan(ContactSourceWrite.SAVED_WITH_PHOTOS, relocated.card, contentId, newPhotosPath = path, contentHash = wholeFileHashOf(photos))
}

/**
 * Save one row **verbatim**, carrying [contentId] in its property map. ⚠ Only for a card whose
 * relocation is already decided — [writeContactSource] for everything else.
 */
internal fun saveContactSourceRow(
    executionContext: ExecutionContext,
    sourceKey: String,
    personId: String,
    card: String,
    contentId: String?,
    /** `SYNC_INGEST` only from tests standing in for a peer's row. */
    origin: org.tenshu.core.common.proxy.WriteOrigin = org.tenshu.core.common.proxy.WriteOrigin.USER,
) {
    val composite = ContactSource.create(
        context = executionContext.context,
        sourceKey = sourceKey,
        personId = personId,
        rawVcard = card,
    )
    // Through `ExecutionContext.saveData`, so `SyncObserver` emits the row and the relation
    // maintainer lists it.
    //
    // ⚠⚠ Always an entity with the slot SET — blank when the card moved nothing. A bare composite
    // keeps whatever content id the stored row had (`ExecutionContext.saveData`: "a bare composite
    // never clears content", CR-2 §10.12 Q5), so a save that dropped a card's photos would go on
    // declaring them and nothing would ever free them.
    // `the row declares its photos as a content edge - and a photo-less save declares none` pins it.
    executionContext.saveData(
        Entity.create<ContactSource>(context = executionContext.context, composite = composite) {
            set(ContactSource.ContentId, contentId ?: "")
        },
        origin,
    )
}

private fun refuse(executionContext: ExecutionContext, message: String): Nothing =
    throw TenshuException(GenericError.create(executionContext.context, message))

/**
 * Receiver-side writer for a card's relocated photos — the contacts twin of
 * `WorkoutStreamsPropertyWriter`. `SyncProcessor` hands it the decrypted
 * `DataBlockPayload(propertyId = ContactSource.Photos.id, …)`; it resolves the row's content id and
 * writes the bytes under [contactPhotosPath]. No-ops when the row has not synced yet — the
 * on-demand resolve retries once it lands.
 *
 * ⚠⚠ Registered in the one `DelegatingExternalPropertyWriter` each runtime builds (three Control
 * platforms, the web runtime, the system-test harness). Missing there, a peer's photos are
 * dropped on arrival and every export on that device fails as "photos unavailable".
 */
class ContactPhotosPropertyWriter(
    private val fileDatabase: FileDatabase,
) : ExternalPropertyWriter {

    override fun supports(propertyId: String): Boolean = propertyId == ContactSource.Photos.id

    override fun write(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
        value: Data,
    ) {
        val bytes = value as? TByteArray ?: return
        val path = resolveExternalPath(executionContext, targetEntityId, propertyId) ?: return
        fileDatabase.writeByteArray(filePath = path, bytes = bytes)
    }

    override fun resolveExternalPath(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? = resolveContentId(executionContext, targetEntityId, propertyId)?.let(::contactPhotosPath)

    override fun resolveContentId(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? {
        if (!supports(propertyId)) return null
        val row = executionContext.database.getData(targetEntityId, asEntity = true) ?: return null
        // Through the schema's declaration, as the backfill walk and the block builder read it.
        (executionContext.schemaProvider as? SchemaRegistry)
            ?.let { contentEdgeOf(it, row, propertyId) }
            ?.let { return it.contentId }
        return (executionContext.resolveProperty(row, propertyRef(ContactSource.ContentId.id)) as? TString)
            ?.get()
            ?.takeIf { it.isNotBlank() }
    }
}
