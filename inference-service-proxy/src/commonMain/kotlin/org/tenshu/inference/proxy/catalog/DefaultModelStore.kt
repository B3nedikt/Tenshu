package org.tenshu.inference.proxy.catalog

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.FileDatabase

/**
 * The proxy's canonical model store (IN-D10 / `maps-region-delivery.md` D5), backed by the
 * same two **device-local, never synced** mechanisms `DefaultRegionStore` uses:
 *
 * - the small install **index** as a JSON blob in [SecureStorageDelegate] (through
 *   [ModelIndexStorage]; the iOS owner keeps it beside the bytes instead), and
 * - the model **bytes** on the local filesystem via [FileDatabase].
 *
 * ⚠⚠ The realm DB and the preference service are deliberately not used: those persist to the
 * *synced* realm, and a model is a public artifact any device can re-fetch. Syncing one would push
 * hundreds of megabytes through the relay and charge them to the user's quota for nothing (IN-D4).
 *
 * ⚠⚠ **F2v: bytes land under a content-addressed path**, [modelsDir]`/<id>.<role>.<digest>.model`,
 * one file per artifact, so a commit never overwrites bytes a live install record points at
 * (`photos-people-and-pets.md` §12.12, *F2v as built*). A path therefore names one content for its
 * whole life, which is also what lets a backend cache a loaded runtime by path. ⚠ Records written
 * before content addressing name the flat `<id>.<role>.model` path. They keep resolving untouched —
 * the record stores its path, so there is nothing to migrate — until the next update moves them.
 *
 * ⚠⚠ **A file is deleted only against an index a process READ BACK, never one it only wrote**
 * (`photos-people-and-pets.md` §12.12, *F2v as built — reclamation waits for the next process*).
 * [SecureStorageDelegate.storeString] promises nothing about durability, and on Android it has
 * none when it returns: `AndroidSecureStorageDelegate` ends in `SharedPreferences.apply()`, which
 * updates memory and writes the file later. A delete is in the kernel at once. Sweeping right after
 * the index write would therefore let a process death keep the deletes and drop the write, and the
 * next start would read the OLD record over files that were gone. So a commit and an
 * eviction only *declare*; the one [sweep] is the first index read of a process, whose index is by
 * definition what survived. ⚠ The price: a replaced or evicted generation stays on disk until this
 * process is replaced. ⚠ Do not move the sweep back next to a write without a delegate that vouches
 * for that write's durability.
 *
 * ⚠⚠ **Nothing is answered, written or deleted from an index assumed empty**
 * (`ios-on-device-inference.md` §13.7). The index is the only record of which bytes are verified,
 * so a read has four results and only two of them are an index:
 * - *absent* and *decoded* are what they say, and the only two the [sweep] runs against;
 * - *unreadable* (the storage threw, or said the value is there and gave no bytes) and
 *   *undecodable* (JSON that is not this build's record) throw [ModelIndexUnreadableException]
 *   from every method that reads the index: no answer, no write, no delete, no fetch begun;
 * - bytes that are *not JSON at all* are no index, by decision: they can never be read as
 *   records by any build, so the next commit writes over them — but nothing is swept against them.
 *
 * ⚠⚠ **One writer at a time** ([lock]; same section, *one writer at a time*). A commit's new paths
 * are declared garbage before they are renamed into, and only the index record it has not written
 * yet keeps them alive — so a sweep that runs inside that window deletes them, and the commit then
 * indexes an artifact that is not there. The sweep that can still do that is the first read's: a
 * backend's `pathOf` racing a commit. And the index and the garbage list are both
 * read-modify-write, and two models commit concurrently (`DefaultModelDownloader` runs one job per
 * model): an unserialised pair loses one model's record while its paths stay declared, so the next
 * start deletes an install that had completed. Every method that touches the index or the garbage
 * list therefore holds [lock] for its whole body, **reads included**. Only the per-`(model, role)`
 * staging calls stay outside it — they name files no other model can.
 * ⚠⚠ [lock] is per **process**, so the rule also needs exactly one *process* that writes and
 * sweeps: [ownership], asked once at construction (same section, *one process at a time*). A store
 * that is refused still answers [installed] and [pathOf] from the index it read, but it never
 * sweeps and every mutation throws [ModelStoreNotOwnedException]. Desktop takes a file lock, because
 * two Controls can run there; Android and iOS are [ModelStoreOwnership.SoleProcess].
 */
class DefaultModelStore(
    /** Where the index and the garbage list live — see [ModelIndexStorage] for why it is a port. */
    private val storageDelegate: ModelIndexStorage,
    private val fileDatabase: FileDatabase,
    private val modelsDir: String,
    private val digestOf: (ByteArray) -> String,
    ownership: ModelStoreOwnership,
    private val indexKey: String = DEFAULT_INDEX_KEY,
) : ModelStore {

    constructor(
        storageDelegate: SecureStorageDelegate,
        fileDatabase: FileDatabase,
        modelsDir: String,
        digestOf: (ByteArray) -> String,
        ownership: ModelStoreOwnership,
        indexKey: String = DEFAULT_INDEX_KEY,
    ) : this(SecureStorageModelIndex(storageDelegate), fileDatabase, modelsDir, digestOf, ownership, indexKey)

    /** Why this process may not write or sweep, or `null` when it may. Decided once — see [ModelStoreOwnership]. */
    private val refusal: ModelStoreRefusal? = ownership.refusal()

    private fun requireOwned() {
        refusal?.let { throw ModelStoreNotOwnedException(it) }
    }

    /** F2v: paths declared for deletion, pending the next process's [sweep]. Device-local, like the index. */
    private val garbageKey = "$indexKey.garbage"

    /** Serialises every index / garbage mutation and every index read — see the class KDoc. */
    private val lock = SynchronizedObject()

    override fun installed(): List<InstalledModelData> = synchronized(lock) { readIndex() }

    /**
     * Lands a whole checkpoint from memory and indexes it — only if **every** artifact matches the
     * signed catalog's digest.
     *
     * ⚠ Each artifact goes to a staging path and is then **renamed** into place rather than
     * overwritten in situ. The case that matters is an update over a model already resident and in
     * use: writing a multi-hundred-megabyte artifact over the live file means that for the whole
     * write the file is neither the old model nor the new one, and a process death leaves a
     * **truncated file the index still vouches for**. A rename is atomic, so a reader sees the old
     * file or the whole new one.
     *
     * ⚠⚠ In-memory, and therefore for fixtures only — a real checkpoint uses the staged path.
     */
    override fun add(entry: ModelEntryData, bytes: Map<String, ByteArray>): InstalledModelData? {
        requireOwned()
        val checked = entry.artifacts.map { artifact ->
            val actual = bytes[artifact.role] ?: return null
            if (digestOf(actual) != artifact.sha256) return null
            artifact to actual
        }
        // ⚠ Every digest is checked BEFORE a single byte is written: a checkpoint whose third
        // artifact is wrong must not leave the first two on disk under their final names.
        return synchronized(lock) {
            // ⚠ Before a byte is staged: an index that cannot be read refuses the whole call.
            readIndex()
            checked.forEach { (artifact, actual) ->
                fileDatabase.writeRawByteArray(stagingPath(entry.modelId, artifact.role), actual)
            }
            // ⚠ Staged three lines up, under [lock], from bytes this call digested: no marker is asked for.
            synchronized(process) {
                commitLocked(entry, checked.associate { (a, b) -> a.role to b.size.toLong() }, stagedByThisCall = true)
            }
        }
    }

    override fun beginStaging(modelId: String, role: String, expectedSha256: String): Long {
        // ⚠ Here rather than only at the commit: a download in a process that cannot commit must
        // fail before it fetches hundreds of megabytes, and staging paths are shared too.
        requireOwned()
        // ⚠ And for the same reason, an index this process cannot read: the commit at the end of
        // the download would be refused, after the fetch. One memoised read once the index is held.
        synchronized(lock) { readIndex() }
        val marker = fileDatabase.readBytesRange(markerPath(modelId, role), 0, MARKER_MAX_BYTES)
            ?.decodeToString()
            ?.trim()
        // ⚠⚠ A marker that is THERE and gave no bytes is not a marker for another digest. Read
        // as one, the staged bytes below it were deleted and the artifact fetched again from
        // zero, on a read that failed (absent and unreadable are two things here as
        // everywhere). Nothing is deleted; the caller is told and tries again.
        if (marker == null && fileDatabase.fileSize(markerPath(modelId, role)) != null) {
            throw IllegalStateException("What is staged for '$modelId' $role could not be read; nothing was changed.")
        }
        val staged = fileDatabase.fileSize(stagingPath(modelId, role)) ?: 0L
        if (marker == expectedSha256 && staged > 0L) return staged

        fileDatabase.deleteFile(stagingPath(modelId, role))
        // Written even for a fresh start, so the *next* attempt can resume this one.
        fileDatabase.writeRawByteArray(markerPath(modelId, role), expectedSha256.encodeToByteArray())
        return 0L
    }

    /** [beginStaging]'s two reads and neither of its writes. */
    override fun stagedBytes(modelId: String, role: String, expectedSha256: String): Long {
        val marker = fileDatabase.readBytesRange(markerPath(modelId, role), 0, MARKER_MAX_BYTES)
            ?.decodeToString()
            ?.trim()
        if (marker != expectedSha256) return 0L
        return fileDatabase.fileSize(stagingPath(modelId, role)) ?: 0L
    }

    override fun readStaged(modelId: String, role: String, offset: Long, length: Int): ByteArray? =
        fileDatabase.readBytesRange(stagingPath(modelId, role), offset, length)

    override fun writeStaged(modelId: String, role: String, offset: Long, bytes: ByteArray) {
        requireOwned()
        fileDatabase.writeBytesRange(stagingPath(modelId, role), offset, bytes)
    }

    /**
     * Promotes every staged artifact, then writes the index once.
     *
     * ⚠⚠ **The renames come first and the index last, for the whole checkpoint.** A crash between
     * two renames leaves files nothing references, which the next [sweep] deletes. A crash before
     * the index write — or after it, but before the delegate made it durable — leaves the previous
     * record and the previous record's *bytes*: nothing here deletes them. The next
     * attempt re-downloads and nothing ever resolves to a mix.
     * ⚠ There is deliberately no ordering here that produces an index record naming files that are
     * only partly present: that is a device whose text tower is a different checkpoint from its
     * vision tower, which yields cosines that are meaningless *and computable* (§21.1).
     */
    override fun commitStaged(
        entry: ModelEntryData,
        stagedBytes: Map<String, Long>,
    ): InstalledModelData {
        requireOwned()
        return synchronized(lock) { synchronized(process) { commitLocked(entry, stagedBytes, stagedByThisCall = false) } }
    }

    /**
     * [commitStaged]'s body. ⚠⚠ Declare → rename → index must run as ONE critical section: between
     * the declare and the index write, the new paths are live only by intent.
     * ⚠⚠ A promotion never targets a file that is already there and whole: the
     * resident file is adopted and the staged copy discarded.
     * ⚠⚠ It ends at the index write. The previous generation is declared, not deleted — see the
     * class KDoc for why the delete belongs to the next process.
     */
    private fun commitLocked(
        entry: ModelEntryData,
        stagedBytes: Map<String, Long>,
        stagedByThisCall: Boolean,
    ): InstalledModelData {
        // ⚠⚠ From the disk, not the memo: see [rereadIndex].
        val previous = rereadIndex().firstOrNull { it.modelId == entry.modelId }
        val record = entry.installedRecord { role, catalogSize ->
            val sha256 = entry.artifacts.first { it.role == role }.sha256
            installPath(entry.modelId, role, sha256) to (stagedBytes[role] ?: catalogSize)
        }
        // ⚠⚠ A destination that already holds the whole artifact is ADOPTED, never moved onto.
        // `FileDatabase.moveFile` is delete-then-rename on every platform, so moving onto a
        // resident file opens a gap in which the path is empty — and the path may be one a durable
        // record names: an update that keeps one artifact's digest, a re-install, or a rollback
        // whose previous index write is still only in memory. Content-addressing makes the resident
        // file the artifact, so there is nothing to move. ⚠ "Whole" is a SIZE check against **the
        // record's** size for the artifact (the caller's staged size, else the catalog's).
        // Everything this store can leave at such a path arrived by an atomic rename of a verified
        // file, except `moveFile`'s copy fallback dying part-way, which leaves a SHORTER file. A
        // digest would cost a read of the whole artifact under [lock]; the commit path verifies
        // nothing itself either — the caller owns the digest (see [commitStaged]). ⚠⚠ NEVER against
        // the size of whatever sits in staging. Staging is not always this commit's: a staging file
        // left by a download of ANOTHER digest of the same role (an update that failed, then a
        // catalog that came back to the installed digest) has another length, so the resident file
        // would read as "not whole" and the stale bytes would be renamed onto it — the wrong bytes
        // under the right sha256, with the index vouching for them.
        val adopted = entry.artifacts.filter { artifact ->
            val resident = fileDatabase.fileSize(record.pathOf(artifact.role)!!) ?: return@filter false
            resident == record.artifacts.first { it.role == artifact.role }.byteSize
        }.mapTo(mutableSetOf()) { it.role }
        // ⚠⚠ And what is not adopted is promoted only when it is THIS commit's: the marker beside
        // the staging file names the digest being committed, and the file is there. Checked for every artifact before anything is declared or renamed:
        // a commit that cannot be whole changes nothing.
        entry.artifacts.filterNot { it.role in adopted }.forEach { artifact ->
            val claimed = fileDatabase.readBytesRange(markerPath(entry.modelId, artifact.role), 0, MARKER_MAX_BYTES)
                ?.decodeToString()
                ?.trim()
            val promotable = (stagedByThisCall || claimed == artifact.sha256) &&
                fileDatabase.fileSize(stagingPath(entry.modelId, artifact.role)) != null
            if (!promotable) {
                throw ModelNotCommittableException(
                    "'${entry.modelId}' ${artifact.role} is neither installed under ${artifact.sha256} " +
                        "nor staged for it; nothing was committed.",
                )
            }
        }
        // ⚠⚠ F2v: declared BEFORE the first rename — the paths this commit will CREATE, so a death
        // before the index flip survives leaves no orphan, and the paths it REPLACES. Never a path
        // it adopts: that file was there before this commit and the new record names it, so no
        // outcome of this commit makes it garbage. Whatever the process dies after, the next
        // process's sweep deletes what no record it reads back names.
        val created = record.artifacts.filterNot { it.role in adopted }.map { it.localPath }
        val kept = record.artifacts.mapTo(mutableSetOf()) { it.localPath }
        declareGarbage(created + previous.paths().filterNot { it in kept })
        entry.artifacts.forEach { artifact ->
            val staging = stagingPath(entry.modelId, artifact.role)
            if (artifact.role in adopted) {
                // ⚠ Whatever is staged for an adopted role goes, whoever staged it and for whichever digest.
                fileDatabase.deleteFile(staging)
            } else {
                // ⚠⚠ Never a whole file a record names. What it may replace is a file of the wrong
                // length, which is broken already. This is what closes the equal-length window: the
                // old record keeps pointing at the old bytes, untouched, until the index write below
                // flips to a record whose files are all there.
                fileDatabase.moveFile(staging, record.pathOf(artifact.role)!!)
            }
            // The staging file is gone, so its marker could only mislead the next attempt.
            fileDatabase.deleteFile(markerPath(entry.modelId, artifact.role))
        }
        // An update lands as add-over-existing, keyed by model id.
        writeIndex(rereadIndex().filterNot { it.modelId == entry.modelId } + record)
        return record
    }

    override fun abortStaging(modelId: String, role: String) {
        requireOwned()
        fileDatabase.deleteFile(stagingPath(modelId, role))
        fileDatabase.deleteFile(markerPath(modelId, role))
    }

    /**
     * ⚠⚠ What was staged goes **before** the move, not with it: a move that moved nothing must
     * leave nothing here to be counted as the delivered file. ⚠ The marker is written last, so
     * a move that died part-way leaves bytes no marker claims for this digest.
     */
    override fun adoptStaged(modelId: String, role: String, expectedSha256: String, fromPath: String): Long {
        requireOwned()
        // ⚠ As [beginStaging]: an index that cannot be read refuses before anything is moved.
        synchronized(lock) { readIndex() }
        val staging = stagingPath(modelId, role)
        fileDatabase.deleteFile(markerPath(modelId, role))
        fileDatabase.deleteFile(staging)
        fileDatabase.moveFile(fromPath, staging)
        val staged = fileDatabase.fileSize(staging)
            ?: throw IllegalStateException("'$fromPath' could not be moved into staging for '$modelId' $role.")
        fileDatabase.writeRawByteArray(markerPath(modelId, role), expectedSha256.encodeToByteArray())
        return staged
    }

    /**
     * Evicts a model.
     *
     * ⚠ The **index is cleared first**, and the bytes go at the next process's [sweep]. Deleting
     * them here would be deleting ahead of an index write that may not have reached disk, and a
     * death in that gap leaves an index entry vouching for files that are gone: a model that
     * reports itself installed and then loads nothing.
     * ⚠⚠ So an eviction stops the model being served at once, but frees its bytes only once this
     * process has been replaced.
     */
    override fun remove(modelId: String) {
        requireOwned()
        synchronized(lock) { synchronized(process) { removeLocked(modelId) } }
    }

    private fun removeLocked(modelId: String) {
        // ⚠⚠ From the disk, not the memo: see [rereadIndex].
        val record = rereadIndex().firstOrNull { it.modelId == modelId }
        // ⚠ Declared before the index write, so the bytes are reclaimed whichever of the two survives.
        declareGarbage(record.paths())
        writeIndex(readIndex().filterNot { it.modelId == modelId })
        record?.artifacts?.forEach { artifact -> abortStaging(modelId, artifact.role) }
    }

    private fun InstalledModelData?.paths(): List<String> = this?.artifacts?.map { it.localPath }.orEmpty()

    /**
     * Records [paths] as candidates for deletion (F2v). ⚠ A candidate is a claim that a path *may*
     * be unreferenced, never that it is: [sweep] decides against the live index at the time it runs.
     * ⚠⚠ A list that cannot be read throws, before the caller has renamed or indexed anything:
     * written back from a list assumed empty, the earlier declarations would be lost and their
     * files never reclaimed.
     */
    private fun declareGarbage(paths: List<String>) {
        if (paths.isEmpty()) return
        val pending = readGarbage()
        storageDelegate.storeString(garbageKey, json.encodeToString(pathsSerializer, (pending + paths).distinct()))
    }

    /**
     * Deletes every declared path **no live record names**, then forgets the declarations.
     *
     * ⚠⚠ Called from exactly one place: the owning process's first index read ([readIndex]). The index
     * it filters against was therefore read back from storage, so it is one a later start can only
     * see again or see replaced by a newer one — never an older one that still needs these files.
     * ⚠⚠ The live-record filter is the rest of the safety argument: a declaration may name the files
     * the index still points at (a death before the index write became durable, or a re-install of
     * identical bytes at the same content-addressed path), and those must survive.
     * ⚠ Forgetting the declarations may itself not survive; the next start then repeats the same
     * deletes against an index that is the same or newer, which is harmless.
     */
    private fun sweep(live: List<InstalledModelData>) {
        val pending = try {
            readGarbage()
        } catch (unreadable: ModelIndexUnreadableException) {
            // ⚠ Not retried later in this process: by then the list may hold this process's own
            // declarations. The files wait for the next process, as they would after a death.
            println("DefaultModelStore -> nothing reclaimed, the garbage list could not be read: ${unreadable.message}")
            return
        }
        if (pending.isEmpty()) return
        val named = live.flatMapTo(mutableSetOf()) { it.paths() }
        pending.filterNot { it in named }.forEach(fileDatabase::deleteFile)
        storageDelegate.remove(garbageKey)
    }

    /**
     * The declared paths. ⚠ A list that was read and does not decode holds no declaration, by
     * decision: nothing in it can name a path again, and the cost is files that are never
     * reclaimed, not files that are deleted. One that could not be read throws.
     */
    private fun readGarbage(): List<String> =
        read(garbageKey)
            ?.let { runCatching { json.decodeFromString(pathsSerializer, it) }.getOrNull() }
            .orEmpty()

    /** [ModelIndexStorage.getString], with every failure to read as the one typed exception. */
    private fun read(key: String): String? = try {
        storageDelegate.getString(key)
    } catch (unreadable: ModelIndexUnreadableException) {
        throw unreadable
    } catch (failure: Exception) {
        throw ModelIndexUnreadableException("The model store's '$key' could not be read: $failure", cause = failure)
    }

    /** Where the bytes are, for a backend that loads them. */
    override fun pathOf(modelId: String, role: String): String? = synchronized(lock) {
        readIndex().firstOrNull { it.modelId == modelId }?.pathOf(role)
    }

    /**
     * The index, **memoised in memory** after the first read.
     *
     * ⚠⚠ Not a micro-optimisation: [pathOf] is on the *per-image* path. `OnnxInferenceBackend`
     * resolves the model for every `supports` check and every embed, so without this a library pass
     * pays a [SecureStorageDelegate] read plus a JSON parse per photograph — ten thousand of them
     * over a real library, against §15.12's 121 ms/image budget. The locator it replaced was a bare
     * `isFile`, and a wiring change must not make the hot path more expensive than the thing it
     * wired.
     *
     * ⚠ Safe because this store is a process-wide `single` and nothing else writes [indexKey]:
     * every mutation goes through [writeIndex], which refreshes the cache in the same call. ⚠⚠ Only
     * ever called under [lock] — the lazy load is where the recovery sweep runs, and two threads
     * racing it let a stale first read sweep a concurrent commit's new paths.
     * ⚠ The lock costs [pathOf]'s hot path an uncontended monitor enter, nanoseconds against
     * §15.12's 121 ms/image; it contends only for the length of a commit's renames. ⚠ It is
     * deliberately *not* a cache of the resolved file — `pathOf`'s caller still stats the path, so
     * an artifact deleted from under the index is still noticed.
     */
    private var memo: Memo? = null

    /**
     * What this store believes storage holds: **one value, replaced whole** by every read and
     * every write. ⚠⚠ Not two fields, the records and a flag for "those bytes were not an
     * index": a write that replaced the first and left the second would have the store answer a
     * valid empty index while still saying it held none. Two facts about
     * one file are one fact.
     */
    private sealed interface Memo {
        val records: List<InstalledModelData>

        /** Absent, or records this build wrote. */
        class Index(override val records: List<InstalledModelData>) : Memo

        /** Bytes that were not JSON at all: answered as no records, by decision, and said. */
        data object NotAnIndex : Memo {
            override val records: List<InstalledModelData> = emptyList()
        }
    }

    override fun read(): InstalledIndex = try {
        // ⚠ One look, under the lock: the records and whether they came from an index.
        synchronized(lock) {
            val records: List<InstalledModelData> = readIndex()
            if (memo is Memo.NotAnIndex) InstalledIndex.NotAnIndex else InstalledIndex.Records(records)
        }
    } catch (unreadable: Exception) {
        InstalledIndex.Unreadable(unreadable.message ?: unreadable.toString())
    }

    override fun holdsAnIndex(): Boolean = synchronized(lock) {
        readIndex()
        memo !is Memo.NotAnIndex
    }

    /** Whether this instance's one sweep has been reached. Under [lock]. */
    private var swept: Boolean = false

    /** [indexWrites] as it was when [memo] was read or written. Under [lock]. */
    private var readAt: Long = -1

    /**
     * Why the stored index is not this build's record, once that has been read. ⚠ Kept for the
     * life of the process, unlike a read that failed: nothing writes over such an index, so the
     * bytes cannot change under this process, and [pathOf] must not parse them again per image.
     */
    private var undecodable: String? = null

    /**
     * The index: the memo while **no store of this process has written an index since it was
     * read** ([indexWrites]), and otherwise the disk's, read again.
     *
     * ⚠⚠ The memo is this instance's, and "nothing else writes [indexKey]" is true of a process
     * with one store. On iOS a process can have two (a graph being torn down and its successor),
     * and the successor's memo can predate the other's commit: it would report an installed model
     * absent, fetch it again, and write its own stale view back over the index. A refresh asked
     * for by the downloader's steps alone would leave every other reader (`InstalledModels`,
     * [pathOf], readiness) answering from the stale memo until such a step ran. So the store knows
     * for itself: one counter for the process, moved by
     * every index write; a memo read at another count is not served.
     * ⚠ [pathOf]'s hot path pays one volatile read and a comparison.
     */
    private fun readIndex(): List<InstalledModelData> {
        memo?.let { if (readAt == indexWrites) return it.records }
        return rereadIndex()
    }

    /**
     * The index **as storage holds it now**, replacing what was memoised. Under [lock], and
     * under [process] for its whole body.
     *
     * - **Every write of the index is a read-modify-write of this** ([commitLocked],
     *   [removeLocked]), never of the memo.
     * - ⚠⚠ A read that fails **forgets the memo and throws**: memory is not served once the
     *   disk has been asked and did not answer. The next read asks again.
     * - ⚠⚠ **The sweep runs here once per instance, on the first read that yields an index,
     *   and under [process]**: see the comment at it.
     */
    private fun rereadIndex(): List<InstalledModelData> = synchronized(process) {
        undecodable?.let { throw ModelIndexUnreadableException(it, undecodable = true) }
        memo = null
        val at: Long = indexWrites
        // ⚠⚠ A read that fails is neither cached nor swept against: the next call reads again.
        val stored = read(indexKey)
        // ⚠ Blank is absent: it holds nothing any build could have written as a record.
        val records = if (stored.isNullOrBlank()) emptyList() else decode(stored)
        memo = if (records == null) Memo.NotAnIndex else Memo.Index(records)
        readAt = at
        if (!swept) {
            swept = true
            // ⚠⚠ Once per process, on the first read that yields an index, and the ONLY sweep there
            // is. It reclaims what the previous process's commits and evictions replaced — or
            // deletes the renamed files of a commit whose index write never became durable, which
            // is correct: that generation never went live and will re-download. Safe only because
            // it runs under [lock] before any mutation of this process (a mutation reads the index
            // first, and is refused until that read succeeds), so every declaration it sees belongs
            // to a dead process, never a pending commit, and `records` is what storage held, never
            // what this process wrote. ⚠⚠ And only in the process that owns the store: to any
            // other, a declaration may belong to a LIVE process's pending commit, or name a path
            // that process has re-installed since. ⚠⚠ And never against bytes that were not an
            // index (`records == null`): the previous process's installs are declared, and only a
            // record that was READ keeps them. ⚠⚠ **And under [process], which every commit and
            // removal of every store of this process holds from its declaration to its index
            // write.** "Every declaration it sees belongs to a dead process" is false of a process
            // with two stores: a second store's first read, landing inside the first one's commit,
            // would see that commit's declared paths with no record naming them yet and delete the
            // files the commit had just renamed; the commit would then write a record for files
            // that were gone. Held, the first read waits for the commit's index write and finds its
            // paths named.
            if (refusal == null && records != null) sweep(records)
        }
        records ?: emptyList()
    }

    /**
     * The records in [stored], or `null` when it is not JSON at all — no index, by decision (see
     * the class KDoc). ⚠⚠ JSON that does not decode throws instead, and is remembered: it may be
     * the record of a build whose shape this one cannot read, and treating it as empty would
     * sweep that build's installs and let the next commit write over their record.
     */
    private fun decode(stored: String): List<InstalledModelData>? {
        runCatching { json.decodeFromString(indexSerializer, stored) }.onSuccess { return it }
        if (runCatching { json.parseToJsonElement(stored) }.isFailure) {
            println("DefaultModelStore -> '$indexKey' is not JSON; read as no index, and nothing is reclaimed against it")
            return null
        }
        val reason = "The model store's '$indexKey' is not an index this build can read; it is left as it is."
        undecodable = reason
        throw ModelIndexUnreadableException(reason, undecodable = true)
    }

    /** ⚠ Under [process]: its callers hold it from before they read what they write back. */
    private fun writeIndex(records: List<InstalledModelData>) {
        storageDelegate.storeString(indexKey, json.encodeToString(indexSerializer, records))
        indexWrites += 1
        memo = Memo.Index(records)
        readAt = indexWrites
    }

    /**
     * Where an installed artifact lives: named by its **digest**, so two different contents never
     * share a path (F2v). ⚠ Sanitised and bounded because a digest is catalog-supplied text that
     * becomes a filename; 32 hex characters are 128 bits, ample to separate two versions of one
     * model.
     */
    private fun installPath(modelId: String, role: String, sha256: String): String {
        val tag = sha256.filter { it.isLetterOrDigit() }.lowercase().take(DIGEST_TAG_CHARS)
        return "$modelsDir/$modelId.$role.$tag$MODEL_SUFFIX"
    }

    /**
     * The flat path of records written before content addressing. ⚠ Not an install destination;
     * it survives as the staging stem, so a `.part` download begun under an older build still
     * resumes, and as the path such a record names.
     */
    private fun legacyPath(modelId: String, role: String): String =
        "$modelsDir/$modelId.$role$MODEL_SUFFIX"

    private fun stagingPath(modelId: String, role: String): String =
        "${legacyPath(modelId, role)}$STAGING_SUFFIX"

    private fun markerPath(modelId: String, role: String): String =
        "${stagingPath(modelId, role)}$MARKER_SUFFIX"

    private companion object {
        /**
         * **The process's**, shared by every store in it: held by a commit and a removal from
         * the garbage declaration to the index write, and by a read of the index from storage
         * (the first one, with its sweep, above all). Taken inside an instance's own lock,
         * never the other way round; nothing is awaited under it, and [pathOf]'s memoised path
         * does not take it.
         * ⚠ One for the process, not one per index: two stores over different files (tests)
         * wait for each other for the length of a commit's renames, which costs nothing real.
         */
        val process = SynchronizedObject()

        /**
         * How many index writes any store of this process has made. Moved under [process].
         * ⚠ For the process too, and deliberately not keyed by where an index is kept: a store
         * over **another** directory (a test's; a second store, should there ever be one)
         * moves it as well, and the cost is one small read that changes nothing. Keying it
         * would need two instances to agree that two paths are one file, which is what a
         * wrong answer here would be made of.
         */
        @kotlin.concurrent.Volatile
        var indexWrites: Long = 0

        const val DEFAULT_INDEX_KEY = "inference.models.index"
        const val MODEL_SUFFIX = ".model"
        const val STAGING_SUFFIX = ".part"
        const val MARKER_SUFFIX = ".sha"
        const val MARKER_MAX_BYTES = 128
        const val DIGEST_TAG_CHARS = 32
        val json = Json { ignoreUnknownKeys = true }
        val indexSerializer = ListSerializer(InstalledModelData.serializer())
        val pathsSerializer = ListSerializer(String.serializer())
    }
}
