package org.tenshu.sync.proxy

import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.shared.SharedFile

/**
 * **Puts back content the relay no longer holds, from a device that still has the bytes**
 * (`drive-upload-stop-and-reclamation.md` §4.18).
 *
 * A dangling reference is a live `File` whose `contentId` resolves to nothing: reclamation took the
 * blobs while this row still pointed at them, or an upload died before its manifest landed. Without
 * a repair that file is broken for ever; this is what makes it usually not.
 *
 * ## Why it can put the content back at all
 *
 * Three facts line up, and none of them is obvious from the outside:
 *
 * 1. **`contentId` is a stable random id carried on the entity**, not a hash of the bytes. A repair
 *    reads back the id the row already has, so nothing about the entity changes.
 * 2. **`manifestContentRef(contentId)` is a pure function of that id**, not of the manifest's
 *    ciphertext. The rebuilt manifest therefore lands at exactly the address every other device is
 *    already looking at.
 * 3. **The refs it needs are free** precisely because they were reclaimed — a repair writes into the
 *    hole the delete left, so there is nothing to overwrite and no conflict to resolve.
 *
 * ⇒ the repair is a re-emit under the same id: no entity write, no sync block, nothing for peers to
 * merge. They were already asking for this content and now the answer exists. ⚠ Not byte-identical
 * — a re-emit mints a fresh CEK, so the chunks are new blobs and the old ones (if any survived) are
 * orphaned, which is the accepted shape from `file-content-addressing.md` §3.4.
 *
 * ## Why it cannot fight a legitimate upload
 *
 * An absent manifest also describes an upload still in flight, and racing one would waste an emit.
 * ⭐ The condition rules it out for free: a repair acts **only when this device holds the plaintext**,
 * and a device that never had the file cannot reach that branch. The device that *is* uploading is
 * the only one holding bytes, and its own journal already owns that retry.
 *
 * ⚠⚠ **Additive by construction.** Every path here either uploads or does nothing; there is no
 * branch that deletes, and no input that makes one appear. That is what lets it be switched on
 * ahead of the reclamation sweep rather than alongside it — the net wants to exist before the fall.
 */
fun interface ContentRepair {

    /**
     * Try to put [contentId] back, for the file at [entityId]'s [propertyId].
     *
     * @return true if a re-emit was started. False means this device could not help — it holds no
     *   plaintext for that file, or the entity is not readable — which is the ordinary case on a
     *   device that never downloaded the content, and is not an error.
     */
    suspend fun repair(entityId: DataId, propertyId: String, contentId: String): Boolean
}

/**
 * The production [ContentRepair]: re-emit when this device has the plaintext, decline when it does
 * not.
 *
 * ⚠ A named function rather than a lambda in `initSyncService`, because everything interesting about
 * it is a *decision* — which files this device may put back — and a decision spelled out in wiring is
 * one nobody can test and nobody reads.
 *
 * @param emit the re-emit itself, `(entityId, propertyId, filePath) -> started`; production passes
 *   `SyncObserver::repairFileContent` — ⚠⚠ NOT `emitFileContent`, which skips content this runtime
 *   already published, and a repair is by definition content published before and since lost.
 *   It journals the intent before a byte moves, so a repair
 *   interrupted half-way is finished by the next drain rather than lost.
 */
fun localBytesContentRepair(
    readFilePath: (DataId) -> String?,
    hasLocalBytes: (String) -> Boolean,
    emit: suspend (entityId: DataId, propertyId: String, filePath: String) -> Boolean,
    repairableProperties: Set<String> = REPAIRABLE_PROPERTY_IDS,
): ContentRepair = ContentRepair { entityId, propertyId, contentId ->
    // ⚠⚠ **A file's bytes are not every property's bytes.** All five thumbnail properties are
    // `Syncable(OnDemand)` too, so a *thumbnail* resolve arrives here with the thumbnail's own
    // propertyId and its own derived contentId — while [readFilePath] answers with the FILE's path.
    // Without this guard the repair would re-emit the full-size file under the thumbnail's content
    // id, and it would do so in a state that is entirely ordinary: file downloaded, thumbnail not.
    //
    // ⚠ Declining is the right answer rather than a gap to fill later. A thumbnail is derived, so a
    // missing one degrades to the next class or the category icon — and it is regenerable from the
    // file, which is not true of the file itself. Repairing what cannot be re-derived is the job.
    if (propertyId !in repairableProperties) {
        return@ContentRepair false
    }
    val path = readFilePath(entityId)?.takeIf { it.isNotBlank() }
    // ⚠⚠ The guard that makes this both additive and race-free: no local bytes, no repair. A device
    // that never held the file cannot mistake an upload still in flight for a dangling reference,
    // because it cannot reach past here.
    if (path == null || !hasLocalBytes(path)) {
        false
    } else {
        println(
            "SyncProxy -> content $contentId is missing from the relay; re-emitting from local " +
                "bytes at $path (ST-3f)"
        )
        emit(entityId, propertyId, path)
    }
}

/**
 * The properties whose content a repair may put back: a file's own bytes, and a shared file's.
 *
 * ⚠⚠ Deliberately a **whitelist**. Every other `OnDemand` property either has bytes somewhere other
 * than the file's path (the five thumbnail classes, keyed by `contentId` under `/.thumbnails/…`) or
 * is derived and cheaper to regenerate than to re-upload. A blacklist would admit the next
 * `OnDemand` property added, silently, into a pass that uploads whatever is at the file's path.
 */
val REPAIRABLE_PROPERTY_IDS: Set<String> = setOf(
    // ⚠ The constants, never the literals. A hand-written id that drifts from the property would
    // not fail — it would silently stop repairing, which is the failure mode nobody notices.
    File.Data.id,
    SharedFile.DATA_PROPERTY_ID,
)

/**
 * Late binding for [ContentRepair], mirroring `SyncProcessorHolder`.
 *
 * ⚠ Needed because the repair emits through the `SyncObserver`, which `initSyncService` builds
 * *after* the processor that has to call it. A holder keeps that ordering an implementation detail
 * rather than a constructor puzzle.
 */
class ContentRepairHolder {
    var repair: ContentRepair? = null

    /**
     * The same decision, for a caller that has NOT just read the content on the device that needs it:
     * the declaration backfill, which learns "the relay holds no manifest for this content" on the
     * device that uploaded it. See [localBytesContentEnsure].
     */
    var ensure: ContentRepair? = null
}

/**
 * [localBytesContentRepair]'s decision with a gentler act: **ensure**, not force.
 *
 * ⚠⚠ Why the backfill may not use the forced re-emit. A reader that finds no manifest never held the
 * file, so it cannot be racing its upload. The backfill runs on the UPLOADING device, where "no
 * manifest yet" is also what an upload still in flight, or journalled behind a closed gate, looks
 * like. A forced re-emit there mints a second chunk set under a fresh key, a duplicate that leaves
 * one of the two undescribed (see `EmitKeyLocks`). `ensureNamedContentOnRelay` asks again under the
 * content's emit key, declares when the content has landed meanwhile, upserts a journalled intent
 * instead of duplicating it, and re-emits only when the relay still has nothing.
 *
 * The case it exists for: a file whose chunks reached the relay and whose manifest never did, on a
 * device that holds the bytes and no upload intent. Nothing retries it, and the reader-side repair
 * cannot help: no other device can read a file that has no manifest, and the uploader never resolves
 * its own content.
 *
 * ⚠⚠ Only while the holder still NAMES the content. The pass hands over the id
 * its walk read, and an overwrite since then leaves that id absent for good. A holder that moved on
 * is declined; its new content is the overwrite's own upload and the pass's revalidation.
 * ⚠ [NamedContentEnsure.UNREADABLE] THROWS: the row was readable a line earlier, so the pass counts
 * a failed repair and runs again, where declining would let the marker latch over a file nobody
 * repaired.
 *
 * @param ensure `(entityId, propertyId, filePath, contentId)`; production passes
 *   `SyncObserver::ensureNamedContentOnRelay`, which compares and uploads from one read of the row.
 *   The answer here is whether this device **acted**, not what the relay turned out to hold.
 */
fun localBytesContentEnsure(
    readFilePath: (DataId) -> String?,
    hasLocalBytes: (String) -> Boolean,
    ensure: suspend (entityId: DataId, propertyId: String, filePath: String, contentId: String) -> NamedContentEnsure,
    repairableProperties: Set<String> = REPAIRABLE_PROPERTY_IDS,
): ContentRepair = ContentRepair { entityId, propertyId, contentId ->
    // The same two guards as [localBytesContentRepair], for the same reasons: a thumbnail's content
    // is not at the file's path, and a device without the bytes has nothing to put back.
    if (propertyId !in repairableProperties) return@ContentRepair false
    val path = readFilePath(entityId)?.takeIf { it.isNotBlank() }
    if (path == null || !hasLocalBytes(path)) return@ContentRepair false
    println(
        "SyncProxy -> content $contentId has no manifest on the relay; ensuring it from local " +
            "bytes at $path"
    )
    when (ensure(entityId, propertyId, path, contentId)) {
        NamedContentEnsure.ACTED -> true
        NamedContentEnsure.MOVED_ON -> {
            println("SyncProxy -> $path names other content now; $contentId not ensured")
            false
        }
        NamedContentEnsure.UNREADABLE ->
            error("cannot read which content the row at $path names now; $contentId not ensured")
    }
}

/** What `SyncObserver.ensureNamedContentOnRelay` did with a content id its caller read earlier. */
enum class NamedContentEnsure {
    /** The holder still names it, and it was ensured (declared, kept journalled, or re-emitted). */
    ACTED,

    /** The holder names other content now. Nothing was probed and nothing was sent. */
    MOVED_ON,

    /** The holder's content could not be read: retired or unreadable, which cannot be told apart. */
    UNREADABLE,
}

/**
 * Gives both late-bound repairs their body. ⚠ Out of `initSyncService` on purpose: that function is at
 * the size D8 will not dex (`tech-debt.md`), so wiring that needs two lambdas lives here.
 */
internal fun ContentRepairHolder.bindLocalBytes(
    executionContext: ExecutionContext,
    fileDatabase: FileDatabase,
    syncObserver: SyncObserver,
) {
    val readFilePath: (DataId) -> String? = { entityId ->
        runCatching {
            (executionContext.database.getData(entityId, asEntity = true) as? Entity<*>)
                ?.optionalFieldValue<TString>(File.Field.PATH)?.get()
        }.getOrNull()
    }
    val hasLocalBytes: (String) -> Boolean = { path -> fileDatabase.fileSize(path) != null }
    // Forced: the relay lost this content, so the "already published" skip must not apply.
    repair = localBytesContentRepair(readFilePath, hasLocalBytes, emit = syncObserver::repairFileContent)
    ensure = localBytesContentEnsure(readFilePath, hasLocalBytes, ensure = syncObserver::ensureNamedContentOnRelay)
}
