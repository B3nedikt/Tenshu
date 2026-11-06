package org.tenshu.contact.proxy

import kotlinx.coroutines.sync.withLock
import org.tenshu.contact.ContactSource
import org.tenshu.contact.proxy.impl.sourceRows
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.LaneAwareDataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity

/**
 * Local photo blobs this device may no longer need (`contacts-app.md` §9.10).
 *
 * ## ⚠⚠ Why a superseded blob is never deleted where it is superseded
 *
 * A blob is this device's plaintext of content the relay holds sealed. When a save moves a card to
 * new photos — or a peer's row arrives naming new ones, or the contact is deleted — the old blob
 * looks unneeded. It is not necessarily: device B, offline, edits the card while it still names C1;
 * B's save wins last-writer when it lands, so the row names C1 again — and a device that deleted C1
 * at its own save has thrown away the only local copy. So the old id becomes a **candidate**, and
 * the sweep deletes it only when **no stored `ContactSource` row references it**; a row that comes
 * back to it keeps it.
 *
 * ⚠ The candidate list is a device-local file beside the blobs, never a synced row: which blobs one
 * device holds is nobody else's business, and a synced list would cost every peer a block per edit.
 */
internal fun contactPhotoCandidatesPath(): String = "/.contacts/photos/.superseded"

/** Read and write [contactPhotoCandidatesPath]. Callers hold the store's lock (`database.transaction`). */
internal class ContactPhotoCandidates(private val store: () -> FileDatabase) {

    constructor(fileDatabase: FileDatabase) : this({ fileDatabase })

    private val fileDatabase: FileDatabase get() = store()

    fun read(): Set<String> =
        fileDatabase.readBytes(contactPhotoCandidatesPath())?.get()?.decodeToString()
            ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            .orEmpty()

    fun write(ids: Set<String>) {
        if (ids.isEmpty()) {
            fileDatabase.deleteFile(contactPhotoCandidatesPath())
        } else {
            fileDatabase.writeRawByteArray(contactPhotoCandidatesPath(), ids.sorted().joinToString("\n").encodeToByteArray())
        }
    }

    fun add(id: String) {
        val current = read()
        if (id !in current) write(current + id)
    }
}

/**
 * Records every content id a `ContactSource` row stops naming — a local save, a peer's row arriving,
 * a delete (local or a peer's tombstone) — as a sweep candidate. One observer for every path,
 * because the receiver's case (a peer re-relocated the card) has no op of ours to hook.
 *
 * ⚠ Runs inside the save's or the delete's fanout, where a throw would fail the write or the sync
 * tick, so recording is best-effort: a lost note costs disk, never data.
 */
internal class ContactPhotoSupersededRecorder(
    private val executionContext: ExecutionContext,
    private val candidates: ContactPhotoCandidates,
) : LaneAwareDataObserver {

    override fun prepareSave(data: Data, origin: WriteOrigin): Any? {
        val dataId = runCatching { executionContext.getDataId(data) }.getOrNull() as? DataId.StringDataId ?: return null
        if (dataId.type.get() != ContactSource.ID) return null
        return storedContentIdOf(executionContext.database.getData(dataId, asEntity = true))
    }

    override fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?, prepared: Any?) {
        val previous = prepared as? String ?: return
        // A bare composite keeps the stored id (`saveData`'s content rule), so only an entity says.
        val now = if (data is Entity<*>) storedContentIdOf(data) else previous
        if (now != previous) record(previous)
    }

    override fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?) = Unit

    override fun beforeDataDeleted(dataId: DataId, origin: WriteOrigin) {
        val id = dataId as? DataId.StringDataId ?: return
        if (id.type.get() != ContactSource.ID) return
        storedContentIdOf(executionContext.database.getData(dataId, asEntity = true))?.let(::record)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit

    private fun record(contentId: String) {
        runCatching { executionContext.database.transaction { candidates.add(contentId) } }
            .onFailure { println("ContactPhotoSweep -> could not record $contentId as a candidate: $it") }
    }
}

/**
 * Delete the candidate blobs no stored `ContactSource` row references. Returns how many went.
 *
 * Runs as the scheduled `SweepContactPhotos` op — and on the web, which
 * has no schedules, from the sync scheduler's maintenance hook ([sweepContactPhotos]) — under the same rules as
 * the file sweeps: [ContactProxyComponents.sourceWriteLock] so no photo save is mid-flight, and the
 * whole decide-and-delete in one `database.transaction` so no row lands between "nothing references
 * it" and the delete. ⚠ A blob a journalled upload still reads from is kept — deleting it would lose
 * content the relay has not received yet.
 */
internal suspend fun sweepSupersededContactPhotos(
    executionContext: ExecutionContext,
    components: ContactProxyComponents,
): Int = components.sourceWriteLock.withLock {
    val candidates = ContactPhotoCandidates(components.fileDatabase)
    val pending = executionContext.database.transaction { candidates.read() }
    if (pending.isEmpty()) return@withLock 0
    val owed = pending.filterTo(mutableSetOf()) { id ->
        components.syncBlockEmitterHolder?.fileContentEmitter?.hasQueuedContentAt(contactPhotosPath(id)) == true
    }
    executionContext.database.transaction {
        val referenced = sourceRows(executionContext).mapNotNullTo(mutableSetOf()) { row ->
            storedContentIdOf(
                executionContext.database.getData(contactSourceDataId(executionContext, row.sourceKey.get()), asEntity = true)
            )
        }
        var deleted = 0
        val keep = mutableSetOf<String>()
        candidates.read().forEach { id ->
            when {
                // Named again (a peer's write came back to it): not a candidate any more. A later
                // supersession records it afresh.
                id in referenced -> Unit
                // ⚠⚠ Recorded after the snapshot: `owed` never asked the journal
                // about it — a `DeletePerson` or a peer's tombstone does not take `sourceWriteLock`,
                // so it can land between the two reads. Deleting it could destroy the only copy of an
                // upload still journalled. The next run judges it.
                id !in pending -> keep += id
                id in owed -> keep += id
                else -> {
                    components.fileDatabase.deleteFile(contactPhotosPath(id))
                    deleted++
                }
            }
        }
        candidates.write(keep)
        deleted
    }
}

/**
 * The superseded-photo sweep, for a runtime with no `SweepContactPhotos` schedule — the web
 * runtime, which runs it from its sync scheduler's maintenance hook. Cheap when
 * there is nothing to do: one read of the candidate file.
 */
suspend fun ExecutionContext.sweepContactPhotos(components: ContactProxyComponents): Int =
    sweepSupersededContactPhotos(this, components)

/** Installs the [ContactPhotoSupersededRecorder]. Part of `initContactService`. */
internal fun ExecutionContext.installContactPhotoRecorder(photoStore: () -> FileDatabase) {
    addDataObserver(ContactPhotoSupersededRecorder(this, ContactPhotoCandidates(photoStore)) as DataObserver)
}
