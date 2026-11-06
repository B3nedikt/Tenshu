package org.tenshu.inference.proxy.catalog

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.util.Sha256Digest

/**
 * Which download an outcome is about: **everything of the catalog entry that the store keeps**,
 * which is everything an [InstalledModelData] holds but where the files are.
 *
 * ⚠⚠ Not the bytes alone, and not a hand-kept choice of fields. A signed catalog can change
 * `modelVersion`, `labelSetVersion` or `licence`, or give the same bytes to another **role**, and
 * leave every digest as it was; compared on anything less, each would be "the same download", and
 * the store would keep the old record for good. ⚠ What is *not* here is not persisted by the store
 * either: `name`, `capabilities`, `runtime`, `artifactVersion`, an artifact's `path`.
 */
data class ModelDownloadKey(
    val modelId: String,
    val modelVersion: Int,
    val labelSetVersion: Int,
    val licence: String,
    val artifacts: List<Artifact>,
) {
    /** One artifact as the store keeps it: which role it plays, which bytes, how many. */
    data class Artifact(val role: String, val sha256: String, val byteSize: Long)
}

/** Why a download was refused **before anything was fetched**. What 5d's Models screen words. */
sealed interface ModelDownloadRefusal {
    /** Fewer than [neededBytes] free: what is left to fetch, the prepare step and the margin. */
    data class InsufficientStorage(val neededBytes: Long, val freeBytes: Long) : ModelDownloadRefusal

    /** The volume's free space could not be read, so it is not assumed. */
    data object FreeSpaceUnknown : ModelDownloadRefusal

    /** No transport that can be held to Wi-Fi is installed. Nothing falls back to one that cannot. */
    data object NoTransport : ModelDownloadRefusal

    /** The download journal is there and could not be read. */
    data object JournalUnreadable : ModelDownloadRefusal

    /** The model store refused: its index cannot be read, or this process may not write it. */
    data class StoreRefused(val message: String) : ModelDownloadRefusal

    /**
     * The transport did not say which transfers it holds (it is given seconds to), so nothing
     * was started: a transfer that may be running is not started a second time. Ask again.
     */
    data object TransfersUnknown : ModelDownloadRefusal

}

/** How a download ended, **as the store confirmed it**, and which download that was. */
sealed interface ModelDownloadOutcome {
    val key: ModelDownloadKey

    /** [model] is the record the store's commit returned. */
    data class Installed(override val key: ModelDownloadKey, val model: InstalledModelData) : ModelDownloadOutcome

    data class Refused(override val key: ModelDownloadKey, val reason: ModelDownloadRefusal) : ModelDownloadOutcome

    /**
     * @param integrity the delivered bytes were not the signed catalog's. Asking again fetches
     *   the same bytes from the same place; every other failure is worth a retry.
     */
    data class Failed(override val key: ModelDownloadKey, val integrity: Boolean, val message: String) : ModelDownloadOutcome

    data class Cancelled(override val key: ModelDownloadKey) : ModelDownloadOutcome
}

/** Fewer free bytes than the download and its prepare step need (§3.4). Nothing was fetched. */
class ModelStorageInsufficientException(val neededBytes: Long, val freeBytes: Long) :
    Exception("This model needs about $neededBytes bytes free to download and prepare; the device has $freeBytes.")

/** The free space could not be read. Nothing was fetched. */
class ModelFreeSpaceUnknownException :
    Exception("The device's free space could not be read, so the model was not downloaded.")

/** No Wi-Fi-holding transport is installed. Nothing was fetched, and nothing else was tried. */
class ModelTransportUnavailableException :
    Exception("No background transfer is available for model downloads on this device.")

/** The transport did not say which transfers it holds. Nothing was started; asking again asks it again. */
class ModelTransfersUnknownException :
    Exception("The background transfers could not be listed just now, so nothing was started; try again.")

/** The download journal is there and could not be read. Nothing was fetched or deleted. */
class ModelDownloadJournalUnreadableException :
    Exception("The model download journal could not be read; nothing was started.")

/** The download was cancelled, superseded by another, or its runtime was torn down. */
class ModelDownloadCancelledException(message: String) : Exception(message)

/** A download of this model with the other cellular choice is running. */
class ModelDownloadChoiceConflictException :
    Exception("A download of this model is running with another cellular choice; cancel it first.")

/**
 * What a download of this catalog entry is keyed by: the key its outcome carries
 * ([BackgroundModelDownloader.lastOutcome]). ⚠ For comparing; the downloader keys its own
 * records from what it journals, and a test holds the two equal.
 */
fun ModelEntryData.downloadKey(): ModelDownloadKey = ModelDownloadKey(
    modelId, modelVersion, labelSetVersion, licence,
    artifacts.map { ModelDownloadKey.Artifact(it.role, it.sha256, it.byteSize) },
)

/** A download the journal names, as a screen may know it (§14.18). */
data class JournalledModelDownload(val key: ModelDownloadKey, val allowsCellular: Boolean)

/**
 * What a process knows of the download journal. ⚠ Four values and no accessor that folds them:
 * *not looked yet* is not *names nothing*, and neither is *could not be read*.
 */
sealed interface ModelDownloadJournalView {
    /** This process has not read the journal yet. Nothing is known. */
    data object NotLookedYet : ModelDownloadJournalView

    /**
     * As the last step of this process read or wrote it: these models had a download then.
     * ⚠ A memory of that read, not the journal: as old as the last step.
     */
    data class Read(val named: Map<String, JournalledModelDownload>) : ModelDownloadJournalView

    /**
     * The last read failed. [namedBefore] is what an earlier read of this process named;
     * ⚠ a model missing from it is **not known** to have no download.
     */
    data class Unreadable(val namedBefore: Map<String, JournalledModelDownload>) : ModelDownloadJournalView

    /** The graph was torn down. It says nothing more, and nothing it said before stands. */
    data object Retired : ModelDownloadJournalView
}

/**
 * [ModelDownloader] over a transport that **outlives the process** — iOS's background
 * `URLSession` (`ios-on-device-inference.md` §5, §14.11). The twin of
 * [DefaultModelDownloader] for a platform where an in-process fetch stops the moment the app is
 * suspended, and where D6 has to be enforced by the transport.
 *
 * **One download, in order:**
 * 1. the verified catalog entry;
 * 2. what an earlier attempt left staged for each artifact, re-hashed from disk;
 * 3. the free-space check ([additionalDiskNeed] against the volume), **before** anything is fetched;
 * 4. the journal: the entry, each artifact's digest, size and transfer ref, the cellular choice;
 * 5. only then, one transfer per artifact still missing;
 * 6. as each is delivered: size, then sha256 **from disk**, then it is moved into the store's
 *    staging ([ModelStore.adoptStaged]). A file that fails either is deleted, never staged;
 * 7. when every artifact is staged and was hashed **by this process**: the commit, inside
 *    [changes] (readiness's writer 3), and the journal record goes.
 *
 * ⚠⚠ **Nothing is remembered that the disk does not hold.** Every step reads the journal again;
 * where an artifact stands is derived from the delivered file, the store's staging and the
 * transport's live transfers. A process that starts with no memory of a download ([resume])
 * therefore verifies and installs it from the journal alone.
 *
 * ⚠⚠ **Every outcome reported is the store's.** [download] returns the record `commitStaged`
 * returned, and [outcomeFor] answers only for the key the outcome was about.
 *
 * ⚠⚠ **An unreadable journal stops everything that reads it**: no transfer is started, none is
 * cancelled, no delivered file is deleted and nothing is written. A journal that was read and is
 * not this build's record is no journal, by decision ([ModelDownloadJournal]).
 *
 * ⚠⚠ **A retired graph neither writes nor installs** ([retired]). A teardown can land at any
 * moment of a step: while it waits for the catalog, for the transport to say what it holds or
 * for [changes], and while it hashes half a gigabyte. So the question is not asked once per
 * step. **Every act asks it, immediately before it acts** ([alive]): each transfer started or
 * cancelled, each journal write, each file deleted, moved or staged, and the commit. The entry
 * checks of [resume] and [settleFinished] only spare a retired graph the work.
 * ⚠ What stays open is the instant between one such question and its one act; see [alive].
 *
 * ⚠ No fallback to an in-process fetch when no transport is installed: that fetch cannot be held
 * to Wi-Fi. It is a refusal.
 *
 * @param transport the installed transport, or `null` when there is none.
 * @param changes what wraps the commit: on iOS `AiReadiness.storeChanging`.
 * @param additionalDiskNeed free bytes needed, from the bytes still to be prepared (every
 *   artifact not installed already) and the bytes still to be fetched.
 * @param deliveredRefs the refs that have a delivered file, or `null` when that cannot be listed.
 *   [FileDatabase] cannot list a directory.
 * @param scope where a transfer's completion is settled when nobody is waiting on it.
 */
class BackgroundModelDownloader(
    private val catalogSource: ModelCatalogSource,
    // ⚠⚠ `store`, `files`, `transport` and `changes` are NOT properties, on purpose: nothing below can
    // reach them but through [reads], which changes nothing, and [acts], where every call asks
    // [alive] first. A change made past the two does not compile.
    store: ModelStore,
    files: FileDatabase,
    private val progress: ModelDownloadProgressHolder,
    private val baseUrl: String,
    transport: () -> ModelTransferPort?,
    changes: ModelStoreChanges,
    /** ⚠ Readable, so the graph that wires it can be asked which budget it was given. */
    val additionalDiskNeed: (entryBytes: Long, remainingDownloadBytes: Long) -> Long,
    private val retired: () -> Boolean,
    private val deliveredRefs: () -> Set<String>?,
    private val scope: CoroutineScope,
    /**
     * The lock every step of every downloader **over this journal** runs under. ⚠⚠ Not this
     * instance's: a process has one journal, one store directory and one transport, and it can
     * have two graphs over them at once, one being torn down and its successor. See [mutex].
     * ⚠ No default, on purpose: whoever builds a downloader says whose lock it is.
     */
    steps: Mutex,
    private val downloadsDir: String = DEFAULT_DOWNLOADS_DIR,
    /**
     * Told, **after** the step that did it has ended, that a step answered *installed*: on iOS
     * the prepare step's trigger (`ModelPreparer.storeChanged`). ⚠ It must return at once;
     * whatever it starts takes the step lock for itself.
     */
    private val storeChanged: (modelId: String) -> Unit = {},
    /**
     * Run **inside** a removal's step, **before** the store removes the model: on iOS the
     * deletion of that model's compiled towers (`ModelPreparer.evicting`). ⚠ What it throws
     * fails the removal, and the record is then not removed. ⚠ Under the step lock.
     */
    private val beforeRemoval: suspend (modelId: String) -> Unit = {},
    /**
     * Run **inside** a removal's step, after the store removed the model and before the step
     * lock is released: on iOS the eviction of the compiled towers (`ModelPreparer.evicted`).
     * ⚠ It runs under the step lock and must take neither it nor anything taken before it.
     */
    private val afterRemoval: suspend (modelId: String) -> Unit = {},
    /**
     * Runs between an ending being counted and its step: nothing, in the product. ⚠ A test's
     * way to stand where a coroutine can be preempted, which no fake can otherwise reach.
     */
    private val betweenAnEndingAndItsStep: suspend () -> Unit = {},
    private val chunkBytes: Int = ModelDownloadChunkBytes,
) : ModelDownloader {

    private val reads: Reads
    private val acts: Acts

    init {
        val journal = ModelDownloadJournal(files, "$downloadsDir/$JOURNAL_FILE")
        reads = Reads(store, files, journal)
        acts = Acts(store, files, journal, transport, changes, alive = ::alive)
    }

    /** Everything this class **reads**. Nothing here changes a byte or a transfer. */
    private class Reads(
        private val store: ModelStore,
        private val files: FileDatabase,
        private val journal: ModelDownloadJournal,
    ) {
        fun installed(): List<InstalledModelData> = store.installed()

        fun readStaged(modelId: String, role: String, offset: Long, length: Int): ByteArray? =
            store.readStaged(modelId, role, offset, length)

        fun stagedBytes(modelId: String, role: String, sha256: String): Long = store.stagedBytes(modelId, role, sha256)

        fun fileSize(path: String): Long? = files.fileSize(path)

        fun readRange(path: String, offset: Long, length: Int): ByteArray? = files.readBytesRange(path, offset, length)

        fun freeSpace(): Long? = files.availableSpaceBytes()

        fun journal(): JournalRead = journal.read()
    }

    /**
     * Everything this class **changes**, and the only way to any of it: the store's staging and
     * its commit, the journal, the delivered files, the transport's transfers.
     *
     * ⚠⚠ **Each asks [alive] as its first line**, so a teardown that landed anywhere before it
     * is seen, whatever path led here. One exception, and it says so in its name.
     */
    private class Acts(
        private val store: ModelStore,
        private val files: FileDatabase,
        private val journal: ModelDownloadJournal,
        private val transport: () -> ModelTransferPort?,
        private val changes: ModelStoreChanges,
        private val alive: () -> Unit,
    ) {
        /** ⚠ An act although it answers a question: it writes a marker, and may drop what was staged for another digest. */
        fun beginStaging(modelId: String, role: String, sha256: String): Long {
            alive()
            return store.beginStaging(modelId, role, sha256)
        }

        fun abortStaging(modelId: String, role: String) {
            alive()
            store.abortStaging(modelId, role)
        }

        fun adoptStaged(modelId: String, role: String, sha256: String, fromPath: String): Long {
            alive()
            return store.adoptStaged(modelId, role, sha256, fromPath)
        }

        fun commitStaged(entry: ModelEntryData, stagedBytes: Map<String, Long>): InstalledModelData {
            alive()
            return store.commitStaged(entry, stagedBytes)
        }

        fun writeJournal(downloads: List<JournaledDownload>) {
            alive()
            journal.write(downloads)
        }

        /**
         * ⚠ **Not gated, on purpose, and only for this:** the store has confirmed the commit, so
         * the record is wrong whoever is alive, and leaving it costs the next graph a look.
         */
        fun writeJournalOnceTheStoreHasCommitted(downloads: List<JournaledDownload>) = journal.write(downloads)

        fun delete(path: String) {
            alive()
            files.deleteFile(path)
        }

        /**
         * **The one way a block that changes the store is run**: wait for [changes] (on iOS,
         * readiness's lock, behind a pass being cancelled and sessions being closed), **then**
         * ask, then run.
         *
         * ⚠⚠ The wait is where a teardown lands, so the question is asked after it and inside
         * it, by this function and not by each block: the commit's block and a removal's are
         * run the same way, and a block somebody else wrote (the removal is the operation's)
         * cannot forget.
         */
        suspend fun <T> changingTheStore(modelId: String, changes: ModelStoreChanges, change: suspend () -> T): T =
            // ⚠ Saying WHICH model: the wrapper stands down what serves that model, and nothing else.
            changes.changing(modelId) {
                alive()
                change()
            }

        /** The same, in this graph's own wrapper: the commit's. */
        suspend fun <T> changingTheStore(modelId: String, change: suspend () -> T): T = changingTheStore(modelId, changes, change)

        /** The transport, with its two acts gated. `null` when none is installed. */
        fun port(): ModelTransferPort? = transport()?.let { raw ->
            object : ModelTransferPort {
                override fun start(request: ModelTransferRequest) {
                    alive()
                    raw.start(request)
                }

                override suspend fun liveRefs(): Set<String>? = raw.liveRefs()

                override fun cancel(ref: String) {
                    alive()
                    runCatching { raw.cancel(ref) }
                }
            }
        }
    }

    /**
     * Serialises every step that reads the journal and then acts on it, **across every graph of
     * the process** (it is the `steps` this downloader was given).
     *
     * ⚠⚠ Not this instance's own: then nothing at all would serialise two graphs. Each reads the
     * journal at the start of a step and writes it seconds later (after a hash, a wait for the
     * transport, a commit), so whatever the other had written in between would be lost: a
     * retired graph could journal, start, cancel, remove a model, or erase its successor's
     * record in the cleanup after its own commit. One more question asked of [alive] cannot
     * close that, because [alive] can only stop an act that has not begun.
     * ⚠⚠ Under one lock the two cannot interleave: the successor's first step waits until the
     * retiring graph's step in flight has ended, and [alive] only has to stop the NEXT step.
     * The instant between a question and its act ([alive]) is not a window onto another
     * graph's writes: no other graph is in a step.
     * ⚠ What it does not serialise is in the plan (§14.11): the store's index, which each
     * graph's store read for itself.
     */
    private val mutex: Mutex = steps

    /**
     * One step: under [mutex]. ⚠ What the step before this one committed or removed, were it
     * another graph's, is what this one decides from without asking: the store does not serve
     * an index another store of the process has written since ([DefaultModelStore]).
     */
    private suspend inline fun <T> step(block: () -> T): T {
        var installed: Set<String> = emptySet()
        try {
            return mutex.withLock {
                installedInThisStep.clear()
                try {
                    block()
                } finally {
                    installed = installedInThisStep.toSet()
                }
            }
        } finally {
            // ⚠ After the lock is released: whoever is told takes the lock for itself.
            // ⚠⚠ For a step that answered *installed* by EITHER way ([finish]): the commit it
            // made, or a commit it found already in the store (an index write that landed and
            // whose step then threw). Not derived from a count of commits read outside the lock,
            // which would miss the second and count another step's commit as this one's.
            // ⚠ Each with the model it is about: whoever is told may care for one model only.
            installed.forEach { storeChanged(it) }
        }
    }

    /** Whether the step that holds [mutex] has answered *installed*. Under [mutex]. */
    private val installedInThisStep = mutableSetOf<String>()

    /**
     * Who is waiting for which model: **one entry per call of [download], made before that call
     * waits for anything**. Memory only: a relaunch has nobody waiting.
     *
     * ⚠⚠ One mechanism for every phase of a call. A caller can be held by the catalog fetch,
     * by [mutex] behind a step that is hashing, by the transport, by the store-change wrapper,
     * and by the transfers themselves. It waits on its own entry here through all of them, and
     * the step that works for it runs beside it, so whatever completes the entry releases the
     * caller at once ([tornDown] above all).
     * ⚠ Under [ticks], not [mutex]: [tornDown] may wait for nothing.
     */
    private val awaiting = mutableMapOf<String, MutableList<Waiter>>()

    /**
     * One call of [download].
     *
     * ⚠⚠ [joined] is what an ending looks at: a download that ends (installed, failed, refused
     * for now, cancelled, removed, replaced) answers **the calls that had joined its record**
     * and no other. A call that has not got that far (it is fetching the catalog, or waits for
     * [mutex] behind the very step that ends the download) is asking for a download that has
     * not begun; told of the old one's end it would report a cancel the user made *before*
     * asking again, and its own request would be lost.
     */
    private class Waiter(val asked: Long, val admitted: (JournalledModelDownload) -> Unit = {}) {
        val answer = CompletableDeferred<InstalledModelData>()

        /** Under [ticks]. Set by [begin] when the call's record is the journal's. */
        var joined: Boolean = false
    }

    /** ⚠ Set by [tornDown], under [ticks]: nobody is registered on a graph that is gone. */
    private var gone: Boolean = false

    /**
     * Model → how many times its download was **ended on purpose** (cancelled, or the model
     * removed). Under [ticks]; moved by [endedOnPurpose], at the moment the cancel or the
     * removal is **asked for**, which is before it waits for [mutex].
     *
     * ⚠⚠ What a call that has not journalled yet is held to. Such a call (it is fetching the
     * catalog, or waits for [mutex]) has no record, so a cancel or a removal finds nothing to end,
     * and the call would then begin: a model the user had just deleted would be downloaded and
     * installed again, and a download the user had just cancelled would start. A call notes the
     * count when it is **asked for** ([Waiter.asked]) and [begin] refuses it when the count has
     * moved since. ⚠ One mechanism for both, and by order of asking: a call made AFTER the cancel
     * has the new count, and is a new request.
     */
    private val endings = mutableMapOf<String, Long>()

    /**
     * The endings that were asked for and **have not run to their end yet**, by model and by
     * the count each one made.
     *
     * ⚠⚠ **An ending and the downloads around it take effect in the order they were asked
     * for.** The count alone gives half of that: a download asked for *before* an ending is
     * void. The other half: an ending is asked for (counted) and then waits for [mutex]; a
     * download asked for *after* it notes the new count, could reach the lock first, journal
     * and start, and the ending, arriving, would find "the record of this model" and end it:
     * the new request, not the one that was cancelled. So a
     * download does not begin while an ending asked for before it is still on its way.
     */
    private val pendingEndings = MutableStateFlow<Map<String, Set<Long>>>(emptyMap())

    /**
     * [modelId]'s download is to be cancelled, or the model removed: every call asked for
     * before now is void, and none asked for from now on begins until [ended] is called with
     * the number this answers. ⚠ One block under [ticks] with a call's noting of the count
     * ([register]): a call is on one side of an ending or the other.
     */
    private fun endedOnPurpose(modelId: String): Long = synchronized(ticks) {
        val number: Long = (endings[modelId] ?: 0L) + 1
        endings[modelId] = number
        pendingEndings.value = pendingEndings.value + (modelId to (pendingEndings.value[modelId].orEmpty() + number))
        number
    }

    /** The ending numbered [number] is over, however it went: what was asked for after it may begin. */
    private fun ended(modelId: String, number: Long) {
        synchronized(ticks) {
            val left: Set<Long> = pendingEndings.value[modelId].orEmpty() - number
            pendingEndings.value = if (left.isEmpty()) pendingEndings.value - modelId else pendingEndings.value + (modelId to left)
        }
    }

    /** Waits for every ending of [modelId] that was asked for before the call that noted [asked]. */
    private suspend fun afterTheEndingsAskedForBefore(modelId: String, asked: Long) {
        pendingEndings.first { pending -> pending[modelId].orEmpty().none { it <= asked } }
    }

    private fun register(modelId: String, admitted: (JournalledModelDownload) -> Unit): Waiter {
        val (mine: Waiter, refused: Boolean) = synchronized(ticks) {
            val made = Waiter(asked = endings[modelId] ?: 0L, admitted = admitted)
            if (!gone) awaiting.getOrPut(modelId) { mutableListOf() } += made
            made to gone
        }
        if (refused) mine.answer.completeExceptionally(ModelDownloadCancelledException(TORN_DOWN))
        return mine
    }

    private fun unregister(modelId: String, mine: Waiter) {
        synchronized(ticks) {
            val waiters = awaiting[modelId] ?: return
            waiters.remove(mine)
            if (waiters.isEmpty()) awaiting.remove(modelId)
        }
    }

    /** [mine]'s record is the journal's record of its model from here on. Under [mutex]. */
    private fun join(mine: Waiter) {
        synchronized(ticks) { mine.joined = true }
    }

    /**
     * The calls that had joined [modelId]'s record, taken out: the record is ending.
     * ⚠ A call that has not joined stays, and is answered by its own step.
     */
    private fun takeWaiters(modelId: String, but: Waiter? = null): List<CompletableDeferred<InstalledModelData>> =
        synchronized(ticks) {
            val waiters = awaiting[modelId] ?: return@synchronized emptyList()
            val taken = waiters.filter { it.joined && it !== but }
            waiters.removeAll(taken)
            if (waiters.isEmpty()) awaiting.remove(modelId)
            taken.map { it.answer }
        }

    /**
     * Refs whose bytes **this process** hashed against the journalled digest and then staged, or
     * found installed under that digest ([installedHolds]).
     * ⚠ The licence to commit: bytes staged by an earlier process are hashed again first.
     */
    private val verified = mutableSetOf<String>()

    /**
     * Refs whose bytes were there at the last look and **could not be read to the end**
     * ([Standing.INCONCLUSIVE]). Under [mutex]; rewritten for a download each time it is settled.
     */
    private val unread = mutableSetOf<String>()

    /** For the progress bar only; never consulted for a decision. */
    private val ticks = SynchronizedObject()
    private val tracked = mutableMapOf<String, Tracked>()

    /** What a record that is tracked is, for a screen: see [journalView]. */
    private class Tracked(val modelId: String, val byteSize: Long, var written: Long, val record: JournalledModelDownload)

    private val reported = MutableStateFlow<ModelDownloadOutcome?>(null)

    /**
     * The last thing said of a download, **with the key it is about**, for a screen (§14.18).
     * ⚠ The one value [outcomeFor] reads; [report] and [goesOn] are its only writers.
     * ⚠ In memory: a new process says nothing until something happens in it. It never
     * establishes that a download exists or that a model is installed; the journal and the
     * store say that.
     */
    val lastOutcome: StateFlow<ModelDownloadOutcome?> = reported.asStateFlow()

    /** How the last download about [key] ended, or `null`: it says nothing about any other. */
    fun outcomeFor(key: ModelDownloadKey): ModelDownloadOutcome? = reported.value?.takeIf { it.key == key }

    /** A view with the number of the change that made it: a later change's is never replaced by an earlier one's. */
    private class Published(val change: Long, val view: ModelDownloadJournalView)

    private val journalled = MutableStateFlow(Published(0L, ModelDownloadJournalView.NotLookedYet))

    /**
     * What this process knows the journal to name, for a screen (§14.18): the downloads that
     * are *running* in the only sense a screen may use, with the cellular choice each was
     * started under.
     *
     * ⚠⚠ **A view of what the last step READ, kept in memory.** It is not the journal: it is
     * [tracked], which every step that reads the journal replaces whole with what that read
     * named ([noteTheJournal]), and which a step that wrote the journal brings in line with
     * what it wrote. Between two steps it is as old as the last one.
     * ⚠⚠ **Built under [ticks], published after it.** [changing] is where [tracked],
     * [journalLookedAt], [lastReadFailed] and [gone] are written; it builds the view before it
     * lets go of the lock and sets it once it has. ⚠ Setting a `StateFlow` **does** run a
     * collector that sits on an immediate dispatcher (a view model's, on Kotlin/Native), inline,
     * in the setter: set inside the lock, that collector would run inside the lock, and inside a
     * teardown on the main thread.
     * ⚠ Not-looked-yet, unreadable and names-nothing are three values, and a graph that was
     * torn down says [ModelDownloadJournalView.Retired] and nothing after it.
     */
    val journalView: Flow<ModelDownloadJournalView> = journalled.map { it.view }.distinctUntilChanged()

    /** [journalView], as it is now. */
    fun journalNow(): ModelDownloadJournalView = journalled.value.view

    /** Under [ticks]. Whether the last read of the journal by a step failed. */
    private var lastReadFailed: Boolean = false

    /** Under [ticks]. How many changes [changing] has made. */
    private var changes: Long = 0L

    @Volatile
    private var insideTicks: Int = 0

    /** Whether some thread is inside [changing]'s lock now. For a test: no collector may see `true`. */
    internal fun changingNow(): Boolean = insideTicks > 0

    /**
     * Every write to [tracked], [journalLookedAt], [lastReadFailed] and [gone] is made inside
     * this. The view is built before the lock is released, and published after.
     */
    private inline fun <T> changing(change: () -> T): T {
        var made: Published? = null
        try {
            return synchronized(ticks) {
                insideTicks += 1
                try {
                    change()
                } finally {
                    changes += 1
                    made = Published(changes, viewUnderTheLock())
                    insideTicks -= 1
                }
            }
        } finally {
            // ⚠ Outside the lock, and never backwards: two threads may get here in either order.
            val next: Published? = made
            if (next != null) journalled.update { had -> if (had.change < next.change) next else had }
        }
    }

    /** Under [ticks]. */
    private fun viewUnderTheLock(): ModelDownloadJournalView {
        val named: Map<String, JournalledModelDownload> = tracked.values.associate { it.modelId to it.record }
        return when {
            gone -> ModelDownloadJournalView.Retired
            lastReadFailed -> ModelDownloadJournalView.Unreadable(named)
            !journalLookedAt -> ModelDownloadJournalView.NotLookedYet
            else -> ModelDownloadJournalView.Read(named)
        }
    }

    /** ⚠ Wi-Fi only (D6): the choice to use cellular data is [download]'s second parameter. */
    override suspend fun download(modelId: String): InstalledModelData = download(modelId, allowsCellular = false)

    /**
     * Downloads [modelId] and returns the record the store committed.
     *
     * ⚠⚠ **A caller that stops waiting stops nothing**, at whatever moment it stops: the step
     * that works for it runs on this downloader's own scope and is never cancelled on the
     * caller's account. So a download whose caller left while the catalog was being fetched
     * still begins, and one whose caller left after its record was written still starts its
     * transfers. [cancel] is what ends a download, and nothing else on the caller's side does.
     * ⚠ Were the step cancelled with its caller, a caller that left between the journal write
     * and the first start would leave a record no transfer runs for, until the next return to
     * the foreground.
     *
     * @throws ModelStorageInsufficientException, [ModelFreeSpaceUnknownException],
     *   [ModelTransportUnavailableException], [ModelDownloadJournalUnreadableException],
     *   [ModelDownloadChoiceConflictException] before anything is fetched; [ModelIntegrityException]
     *   or [ModelDownloadException] afterwards; and what [DefaultModelDownloader] throws for the
     *   catalog and the store.
     */
    suspend fun download(
        modelId: String,
        allowsCellular: Boolean,
        /**
         * Told, once, when **this call** has a record in the journal: the one it wrote, or the
         * running one it joined, with that record's key and choice. ⚠⚠ The one positive fact
         * *accepted* may rest on: whoever must answer at once waits for this and for nothing
         * it could infer from the journal view, which also changes for another caller's
         * download and for a teardown. ⚠ Called under the step lock: it must
         * not block, and must not call back in.
         */
        admitted: (JournalledModelDownload) -> Unit = {},
    ): InstalledModelData {
        // ⚠⚠ Registered before the first wait, and the step runs BESIDE this call, on the
        // downloader's own scope: so the caller is released the moment its entry is completed,
        // wherever the step is (the catalog fetch can take tens of seconds; a hash blocks).
        val mine: Waiter = register(modelId, admitted)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // ⚠ The catalog is fetched OUTSIDE the lock: it can take tens of seconds, and
                // nothing it needs is the journal's.
                val entry: ModelEntryData = entryOf(modelId)
                // ⚠⚠ Not before an ending that was asked for before this call: see [pendingEndings].
                afterTheEndingsAskedForBefore(modelId, mine.asked)
                step { begin(entry, allowsCellular, mine) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (tornDown: Retired) {
                mine.answer.completeExceptionally(ModelDownloadCancelledException(TORN_DOWN))
            } catch (refusal: Exception) {
                // ⚠ This call's alone: another caller joined to a running download is not told
                // of a refusal that was this one's (the other cellular choice, say).
                mine.answer.completeExceptionally(refusal)
            }
        }
        try {
            return mine.answer.await()
        } finally {
            // ⚠⚠ And the step is NOT cancelled: see the KDoc.
            unregister(modelId, mine)
        }
    }

    /**
     * Whether **this process** is working on a download of [modelId]: somebody waits on one
     * (from the moment [download] is called: the catalog fetch can take tens of seconds), or
     * one is tracked, by [download] or by [resume].
     *
     * ⚠ From memory, not from the journal: the progress stream polls this many times a second,
     * and what it needs to know is whether a progress entry is coming *in this process*. A
     * journalled download this process has not resumed yet answers false until [resume] has run.
     */
    override suspend fun isDownloading(modelId: String): Boolean {
        lookAtTheJournalOnce()
        return synchronized(ticks) { modelId in awaiting || tracked.values.any { it.modelId == modelId } }
    }

    /**
     * ⚠⚠ A process that was only just relaunched has transfers running in the session and
     * knows of none until [resume] has run, which waits for the store's own check and may be
     * hashing behind [mutex]. A screen that asked in between was told *not downloading*. So
     * the first question a process is asked reads the journal, once, and tracks what it names:
     * the download is *running*, and has a bar, from then on.
     *
     * ⚠ It reads and tracks; it starts, settles and writes nothing. It takes [mutex] **without
     * ever waiting for it** (below). A journal that could not be read, or a lock that was
     * taken, means the look is made again at the next question.
     */
    private fun lookAtTheJournalOnce() {
        if (journalLookedAt || retired()) return
        // ⚠⚠ Read AND tracked under [mutex], like every other write to what is tracked: read
        // outside it, a record could be committed or replaced (and untracked) between this
        // read and its tracking, and refs no journal names would be tracked for good.
        // ⚠ Never WAITED for: this is asked twenty times a second, and a step may hold the lock
        // for the length of a hash. A step that holds it has tracked what it read before it
        // began any long work, so there is nothing to add; a look that found the lock taken is
        // simply made again at the next question.
        if (!mutex.tryLock()) return
        try {
            noteTheJournal(readForAction().orElse { return })
        } finally {
            mutex.unlock()
        }
    }

    /**
     * What is tracked is brought in line with the journal **as a step just read it**. Under
     * [mutex], and the only way a ref comes to be tracked: every step that reads the journal
     * calls it before it does anything long.
     */
    private fun noteTheJournal(held: List<JournaledDownload>) {
        // ⚠⚠ REPLACED, not added to: what is tracked is what this read named, and a model the
        // journal does not name is not tracked. Only ever added to, a download whose record was
        // gone (a cancel whose cleanup threw after its journal write; a journal that went absent
        // or undecodable) would stay "running" for the rest of the process: a bar that never
        // ends, and a Cancel that finds no record and does nothing.
        // ⚠ In ONE change: nobody is shown "looked, and it names nothing" in between.
        val starts: List<Triple<String, Long, Long>> = changing {
            if (gone) return
            val named: Set<String> = held.mapTo(mutableSetOf()) { it.modelId }
            val ended: Set<String> = tracked.values.mapTo(mutableSetOf()) { it.modelId } - named
            tracked.entries.removeAll { (_, known) -> known.modelId in ended }
            journalLookedAt = true
            held.map { Triple(it.modelId, it.artifacts.sumOf { artifact -> artifact.byteSize }, trackUnderTheLock(it)) } +
                ended.map { Triple(it, NO_LONGER_NAMED, 0L) }
        }
        starts.forEach { (modelId, total, staged) ->
            if (total == NO_LONGER_NAMED) {
                progress.finish(modelId)
                // ⚠ Whoever had JOINED that record waits for a download nothing names any more.
                finishWaiting(modelId, ModelDownloadCancelledException("The download of '$modelId' is no longer journalled."))
            } else {
                startTheBar(modelId, total, staged)
            }
        }
    }

    @Volatile
    private var journalLookedAt: Boolean = false

    /**
     * Ends a journalled download of [modelId], if there is one, and runs [remove] before any
     * other step of this downloader can run.
     *
     * ⚠⚠ Without it a removed model comes back: the update that was downloading when the model
     * was deleted goes on, and its next settle commits it. ⚠ Read
     * from the **journal**, not from memory: a process that has only just been relaunched
     * knows of no download yet, and the transfers are live all the same.
     *
     * - The record goes first, then its transfers and delivered files, as in [cancel]. What it
     *   had staged goes with the model: the store's removal drops the model's staging.
     * - A journal that cannot be read throws [ModelDownloadJournalUnreadableException] and
     *   [remove] is not run. A retired graph ends nothing and removes nothing, **whenever the
     *   teardown landed**: before the journal was read, or while [changes] kept the removal
     *   waiting ([Acts.changingTheStore]).
     */
    override suspend fun <T> withoutDownloadOf(
        modelId: String,
        changes: ModelStoreChanges,
        remove: suspend () -> T,
    ): T {
        // ⚠⚠ Counted when the removal is ASKED FOR, before it waits for anything: see [endings].
        val number: Long = endedOnPurpose(modelId)
        try {
            betweenAnEndingAndItsStep()
            return withoutDownloadOfUnderTheLock(modelId, changes, remove)
        } finally {
            // ⚠ Whatever it did, also when it threw or its caller left: nothing waits on it for ever.
            ended(modelId, number)
        }
    }

    private suspend fun <T> withoutDownloadOfUnderTheLock(
        modelId: String,
        changes: ModelStoreChanges,
        remove: suspend () -> T,
    ): T = step {
        val held: List<JournaledDownload> = readForAction().orElse { throw ModelDownloadJournalUnreadableException() }
        noteTheJournal(held)
        val record: JournaledDownload? = held.firstOrNull { it.modelId == modelId }
        try {
            if (record != null) {
                drop(record, held, Staging.DROPPED)
                report(ModelDownloadOutcome.Cancelled(record.key()))
                finishWaiting(modelId, ModelDownloadCancelledException("'$modelId' was removed while it was downloading."))
            } else {
                alive()
            }
            // ⚠⚠ And once more INSIDE the wrapper, after its wait: the removal is not this
            // class's code, and it is not run on a graph that was torn down while it waited.
            // ⚠⚠ What only that model's record keeps alive goes FIRST, in this very step
            // ([beforeRemoval]), and the record is removed only if it went: the removal
            // returning means both are gone, and a removal that throws has removed nothing.
            alive()
            beforeRemoval(modelId)
            acts.changingTheStore(modelId, changes, remove).also { afterRemoval(modelId) }
        } catch (tornDown: Retired) {
            throw ModelDownloadCancelledException(tornDown.message.orEmpty())
        }
    }

    /**
     * At launch and on every return to the foreground: settles whatever was delivered while
     * nobody was listening, starts again what the transport no longer holds, and sweeps.
     *
     * **The sweep**, against a journal that was *read* (absent, held, or not this build's):
     * - a live transfer no record names is cancelled, when the transport could say what is live;
     * - a delivered file no record names is deleted, when the directory could be listed.
     *
     * ⚠⚠ Neither runs over an unreadable journal.
     */
    suspend fun resume() {
        step {
            if (retired()) return
            try {
                // ⚠⚠ A resume has no caller, but a `download` made earlier in this process may
                // have one, and this step was what would have started again whatever its
                // transfers lost. It could not: every caller that had joined a record is told.
                // (Which record is whose cannot be read either, so it is all of them.)
                val held: List<JournaledDownload> = readForAction().orElse {
                    val waiting: List<String> = synchronized(ticks) { awaiting.keys.toList() }
                    waiting.forEach { finishWaiting(it, ModelDownloadJournalUnreadableException()) }
                    return
                }
                noteTheJournal(held)
                val live: Set<String>? = settle(held)
                sweep(held, live)
            } catch (tornDown: Retired) {
                // The teardown landed under this step. Whatever it had not done yet is the next graph's.
                tornDown()
            }
        }
    }

    /**
     * The graph this downloader lives in was torn down: **whoever waits on it is told now**, and
     * its progress entries end.
     *
     * ⚠⚠ Without this a caller of [download] would wait for ever. A retired graph completes
     * nothing: it acts on nothing ([alive]), no transfer's report is routed to it any more, and
     * the graph that takes over has a downloader, and waiters, of its own.
     *
     * ⚠ Not a suspend function, and it takes neither [mutex] nor anything a step holds: a
     * teardown runs on the main thread, and a step may be hashing half a gigabyte under [mutex].
     * The journal and the files are not touched: the download itself is the next graph's.
     */
    fun tornDown() {
        val (waiters: List<CompletableDeferred<InstalledModelData>>, models: Set<String>) = changing {
            val waiting = awaiting.values.flatten().map { it.answer }
            val shown = tracked.values.mapTo(mutableSetOf()) { it.modelId } + awaiting.keys
            awaiting.clear()
            gone = true
            tracked.clear()
            // ⚠ Nothing is tracked again by a graph that is gone.
            journalLookedAt = true
            waiting to shown
        }
        waiters.forEach {
            it.completeExceptionally(ModelDownloadCancelledException(TORN_DOWN))
        }
        models.forEach { progress.finish(it) }
    }

    /** A transfer moved: [writtenBytes] of [ref]'s body have arrived. Display only. */
    fun transferProgressed(ref: String, writtenBytes: Long) {
        val (modelId: String, total: Long) = synchronized(ticks) {
            val entry: Tracked = tracked[ref] ?: return
            entry.written = writtenBytes.coerceIn(0L, entry.byteSize)
            entry.modelId to tracked.values.filter { it.modelId == entry.modelId }.sumOf { it.written }
        }
        progress.update(modelId, total)
    }

    /**
     * The transport's report that [ref] ended, from whatever thread it has. Returns at once.
     *
     * ⚠⚠ **A report is checked when it is made and acted on later**: this call returns, and the
     * settling waits for [mutex], behind a step that may be hashing or waiting for the
     * transport. So the transport must go on treating the transfer as the ref's holder until
     * [settled] is called, which is when the report has been acted on (or found to be
     * nobody's): otherwise the step that holds the lock is told the ref is free, starts a
     * replacement, and the queued failure then ends the download the replacement belongs to.
     *
     * ⚠⚠ **And a report is asked whether it still stands at the moment it is acted on**
     * ([ModelTransferReport.stands]), under [mutex]: a cancel of its ref, which also runs under
     * [mutex], may have been queued ahead of it, with the next download of the same ref behind
     * that. Such a report is nobody's, **whatever it says**: neither its failure nor its
     * success is the new download's.
     *
     * @param report [ModelTransferReport.settled] is called exactly once, whatever the settling
     *   did: it threw, the graph was torn down, the journal could not be read.
     * @return the settling, for a caller that needs to wait for it (a test; the transport does not).
     */
    fun transferFinished(
        ref: String,
        statusCode: Int,
        errorMessage: String?,
        report: ModelTransferReport = ModelTransferReport.Unquestioned,
    ): Job = scope.launch {
        try {
            settleFinished(ref, statusCode, errorMessage, report)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            println("BackgroundModelDownloader -> '$ref' not settled: $failure")
        } finally {
            report.settled()
        }
    }

    /** [transferFinished]'s work. ⚠ It does not call [ModelTransferReport.settled]. */
    suspend fun settleFinished(
        ref: String,
        statusCode: Int,
        errorMessage: String?,
        report: ModelTransferReport = ModelTransferReport.Unquestioned,
    ) {
        step {
            if (retired()) return
            // ⚠⚠ Asked HERE, under the lock, and not when the report was made.
            if (!report.stands()) return
            try {
                settleReport(ref, statusCode, errorMessage)
            } catch (tornDown: Retired) {
                // As in [resume].
                tornDown()
            }
        }
    }

    private suspend fun settleReport(ref: String, statusCode: Int, errorMessage: String?) {
        run {
            // ⚠⚠ Unreadable: the delivered file stays where it is and nothing is concluded.
            // ⚠⚠ But whoever waits IS told: this report was the transfer's last word, its hold
            // is released when this returns, and nothing else would ever wake the waiter.
            val held: List<JournaledDownload> = readForAction().orElse {
                journalUnreadableUnder(ref)
                return
            }
            noteTheJournal(held)
            val record: JournaledDownload? = held.firstOrNull { download -> download.artifacts.any { it.ref == ref } }
            if (record == null) {
                // A journal that was READ names no such transfer, so its bytes can never be
                // verified and never installed.
                if (isRef(ref)) acts.delete(deliveredPath(ref))
                return
            }
            if (reads.fileSize(deliveredPath(ref)) == null) {
                // Nothing was delivered. ⚠ What its siblings already delivered is staged first,
                // so a retry fetches only what failed; nothing is started meanwhile.
                settle(listOf(record), startMissing = false)
                // ⚠⚠ Read again, and a read that FAILS is not "the record is gone": read as that
                // (`.orEmpty()`), this step would return having concluded nothing, with the report
                // acknowledged, its hold released, and the waiter left waiting on a download no
                // transfer runs for.
                val now: List<JournaledDownload> = readForAction().orElse {
                    journalUnreadableFor(record)
                    return
                }
                if (now.none { it.modelId == record.modelId && it.key() == record.key() }) return
                // ⚠ Unless the artifact is staged all the same: a report that is late, or about
                // a transfer an earlier process had already settled, fails nothing.
                // ⚠⚠ And unless what is staged for it COULD NOT BE READ: that is not "nothing
                // was delivered". Nothing is concluded, the record stays, and the next settle
                // reads again (decision 18: no download is failed on a read that failed).
                if (ref in unread) return
                if (ref !in verified) {
                    fail(
                        record,
                        integrity = false,
                        ModelDownloadException(
                            "Download failed for '${record.modelId}' ($ref): " +
                                (errorMessage ?: "the transfer ended with status $statusCode and delivered nothing") + ".",
                        ),
                    )
                    return
                }
            }
            settle(listOf(record))
        }
    }

    /**
     * Ends [modelId]'s download: the record goes first, then its transfers, its delivered files
     * **and what it had staged** ([Staging.DROPPED]): a cancel is the user saying none of it is
     * wanted, and staging nothing names is never reclaimed. ⚠ So a download asked for again
     * fetches everything again (a maintainer decision, §14.11).
     * ⚠ Whoever had joined the download is told; a call that asked after the cancel is not.
     *
     * @throws ModelDownloadJournalUnreadableException and then nothing was cancelled.
     */
    suspend fun cancel(modelId: String) {
        // ⚠⚠ Counted when the cancel is ASKED FOR, before it waits for the lock: a download
        // still fetching its catalog is what the user cancelled, and it has no record for the
        // step below to find. And by the same order, a download asked for after this line is
        // a new request, although the step below has not run yet. See [endings].
        val number: Long = endedOnPurpose(modelId)
        try {
            betweenAnEndingAndItsStep()
            cancelUnderTheLock(modelId)
        } finally {
            // ⚠ Whatever it did, also when it threw or its caller left: nothing waits on it for ever.
            ended(modelId, number)
        }
    }

    private suspend fun cancelUnderTheLock(modelId: String) {
        step {
            val held: List<JournaledDownload> = readForAction().orElse { throw ModelDownloadJournalUnreadableException() }
            noteTheJournal(held)
            val record: JournaledDownload = held.firstOrNull { it.modelId == modelId } ?: return
            try {
                drop(record, held, Staging.DROPPED)
            } catch (tornDown: Retired) {
                // ⚠ A graph that was torn down cancels nothing: the download is the next graph's.
                throw ModelDownloadCancelledException(tornDown.message.orEmpty())
            }
            report(ModelDownloadOutcome.Cancelled(record.key()))
            finishWaiting(record.modelId, ModelDownloadCancelledException("The download of '$modelId' was cancelled."))
        }
    }

    // ─── Under [mutex] from here.

    /** The verified catalog's entry for [modelId]. Reads nothing of the journal and holds no lock. */
    private suspend fun entryOf(modelId: String): ModelEntryData {
        if (retired()) throw ModelDownloadCancelledException("This runtime was torn down; nothing is downloaded from it.")
        val catalog: ModelCatalogData = catalogSource.load()
            ?: throw ModelCatalogUnavailableException(
                "No trusted model catalog: it could not be fetched, or the pinned key refused it.",
            )
        return catalog.models.firstOrNull { it.modelId == modelId }
            ?: throw UnknownModelException("No model '$modelId' in the verified catalog.")
    }

    private suspend fun begin(entry: ModelEntryData, allowsCellular: Boolean, mine: Waiter) {
        val modelId: String = entry.modelId
        // ⚠⚠ Before anything else: a cancel or a removal made since this call was asked for
        // ends it too, although it had no record for them to find. See [endings].
        if (synchronized(ticks) { endings[modelId] ?: 0L } != mine.asked) {
            throw ModelDownloadCancelledException("'$modelId' was cancelled or removed before its download began.")
        }
        val wanted: JournaledDownload = entry.journalled(allowsCellular)
        val key: ModelDownloadKey = wanted.key()

        val port: ModelTransferPort = acts.port()
            ?: refuse(key, ModelDownloadRefusal.NoTransport, ModelTransportUnavailableException())
        val held: List<JournaledDownload> = readForAction()
            .orElse { refuse(key, ModelDownloadRefusal.JournalUnreadable, ModelDownloadJournalUnreadableException()) }
        noteTheJournal(held)

        val running: JournaledDownload? = held.firstOrNull { it.modelId == modelId }
        if (running != null && running.sameBytesAs(wanted)) {
            // ⚠ The first choice stands. Changing it means starting the transfers again, which
            // is the caller's to ask for, with [cancel], and not a side effect of asking twice.
            if (running.allowsCellular != allowsCellular) {
                // ⚠⚠ THROWN, and not reported as an outcome: it is this call's failure, and an
                // outcome is keyed by the record, which is the running download's too (the
                // key carries no cellular choice). Reported, it would show as why the running
                // download "is not moving", while it moves.
                throw ModelDownloadChoiceConflictException()
            }
            // ⚠⚠ The same bytes under a newer entry: the transfers and whatever is staged are
            // kept, since they are named by digest, and the RECORD becomes the newer entry.
            // ⚠⚠ Written whenever the record that WOULD be written is not the record that IS
            // there, compared whole, and not when some key says they differ: the key is for
            // what is installed, and the journal also holds what a relaunch fetches from (an
            // artifact's `path`). A catalog that only moved an artifact would leave the old path
            // in the journal, and a relaunch would ask the old URL.
            if (running != wanted) acts.writeJournal(held.filterNot { it.modelId == modelId } + wanted)
            // ⚠⚠ Asked HERE too: when the record is already the one wanted nothing above writes,
            // so nothing above asked, and a graph that was torn down would join the call to a
            // download it will never work on, and tell it so.
            alive()
            join(mine)
            track(wanted)
            // ⚠ After the record is the journal's and is tracked, before anything long.
            mine.admitted(JournalledModelDownload(wanted.key(), wanted.allowsCellular))
            settle(listOf(wanted))
            return
        }

        // What an earlier attempt left staged is not fetched again, and not budgeted for.
        // ⚠⚠ LOOKED at, not touched: this download may yet be refused, and until it is admitted
        // (its record written) it is entitled to change nothing. Asking the store the acting
        // way drops whatever is staged for another digest, which is the staging of the download
        // that is still running when the catalog has moved on.
        val staged: Set<String> = try {
            wanted.artifacts.filter { stagedWithoutTouching(wanted, it) }.mapTo(mutableSetOf()) { it.ref }
        } catch (refused: ModelStoreNotOwnedException) {
            refuse(key, ModelDownloadRefusal.StoreRefused(refused.message.orEmpty()), refused)
        } catch (unreadable: ModelIndexUnreadableException) {
            refuse(key, ModelDownloadRefusal.StoreRefused(unreadable.message.orEmpty()), unreadable)
        }
        val remaining: Long = wanted.artifacts.filterNot { it.ref in staged }.sumOf { it.byteSize }
        // ⚠⚠ Before the journal and before the first byte: a phone is never left hundreds of
        // megabytes into a download it cannot prepare. The same question is asked again at
        // every start ([noRoomFor]); here it also decides whether anything is journalled.
        noRoomFor(wanted, remaining)?.let { (reason, failure) -> refuse(key, reason, failure) }

        // ⚠⚠ The journal BEFORE any transfer: a transfer nothing recorded delivers bytes nobody
        // can verify. A write that fails throws from here, and nothing has been started.
        acts.writeJournal(held.filterNot { it.modelId == modelId } + wanted)
        // Admitted. What was hashed above was hashed by this process, and counts from here.
        // ⚠ An artifact the install record holds is not marked here: the settle below looks at
        // it the acting way ([stagingOf]), which is what drops a stale staging file beside it.
        wanted.artifacts.filter { it.ref in staged && !installedHolds(wanted, it) }.forEach(::markStaged)
        if (running != null) {
            // A newer catalog entry replaced the one that was running: its record is gone with
            // the write above, so its transfers and files are no longer named by anything.
            // ⚠ Except an artifact both entries share: its ref is the new record's too.
            release(
                running, port,
                keep = wanted.artifacts.mapTo(mutableSetOf()) { it.ref },
                staging = Staging.DROPPED_WHERE_NOT_TAKEN_OVER(wanted.artifacts.mapTo(mutableSetOf()) { it.role }),
            )
            // ⚠ Whoever had joined the download that was replaced; not the caller who replaced it.
            takeWaiters(modelId, but = mine).forEach {
                it.completeExceptionally(ModelDownloadCancelledException("A newer '$modelId' replaced the download that was running."))
            }
        }
        join(mine)
        track(wanted)
        mine.admitted(JournalledModelDownload(wanted.key(), wanted.allowsCellular))
        settle(listOf(wanted))
    }

    /**
     * Moves every download in [held] as far as the disk and the transport allow.
     *
     * @return the transport's live refs as they were asked for, `null` when it could not say.
     */
    private suspend fun settle(held: List<JournaledDownload>, startMissing: Boolean = true): Set<String>? {
        val port: ModelTransferPort? = acts.port()
        val live: Set<String>? = port?.let { asked ->
            try {
                asked.liveRefs()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }
        }
        for (download in held) {
            try {
                settleOne(download, port, live, startMissing)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (tornDown: Retired) {
                throw tornDown
            } catch (failure: Exception) {
                // The store refused, or the disk did. The record stays; whoever waits is told.
                println("BackgroundModelDownloader -> '${download.modelId}' not settled: $failure")
                failWaiting(download, failure)
            }
        }
        return live
    }

    private suspend fun settleOne(
        download: JournaledDownload,
        port: ModelTransferPort?,
        live: Set<String>?,
        startMissing: Boolean,
    ) {
        // A commit whose journal write did not land: the store already holds exactly this.
        // ⚠⚠ "Holds" is the record AND a stat of every file it names: a record whose file is
        // gone is not taken for the bytes, here as in [installedHolds]. Without the stat an
        // install with a lost tower answered "installed" for ever, and nothing fetched it again.
        reads.installed().firstOrNull { it.modelId == download.modelId }
            ?.takeIf { installed -> installed.isWhatWouldBeInstalled(download) }
            ?.takeIf { download.artifacts.all { artifact -> installedHolds(download, artifact) } }
            ?.let { installed ->
                finish(download, installed)
                return
            }

        // ⚠ Every artifact is looked at before anything is concluded: what arrived whole is
        // staged even when a sibling turns out corrupt, so the retry fetches only that one.
        val standings: List<Pair<JournaledArtifact, Standing>> = download.artifacts.map { it to stand(download, it) }
        standings.forEach { (artifact, standing) ->
            if (standing == Standing.INCONCLUSIVE) unread += artifact.ref else unread -= artifact.ref
        }
        standings.firstOrNull { it.second == Standing.WRONG_LENGTH }?.let { (artifact, _) ->
            // ⚠ Not an integrity failure: a body of another length is what a transfer cut
            // short, or a resume the CDN answered with a whole body, leaves, and asking again
            // works (§14.12). The digest is what says the bytes are not the catalog's.
            fail(
                download,
                integrity = false,
                ModelDownloadException(
                    "What was delivered for '${download.modelId}' artifact '${artifact.role}' is not " +
                        "${artifact.byteSize} bytes long; it was discarded.",
                ),
            )
            return
        }
        standings.firstOrNull { it.second == Standing.CORRUPT }?.let { (artifact, _) ->
            fail(
                download,
                integrity = true,
                ModelIntegrityException(
                    "What was delivered for '${download.modelId}' artifact '${artifact.role}' is not the " +
                        "catalog's ${artifact.byteSize} bytes with sha256 ${artifact.sha256}; it was discarded.",
                ),
            )
            return
        }
        if (standings.all { it.second == Standing.STAGED }) {
            commit(download)
            return
        }
        publishProgress(download)
        if (!startMissing) return
        val missing: List<JournaledArtifact> = standings.filter { it.second == Standing.MISSING }.map { it.first }
        if (missing.isEmpty()) {
            // ⚠⚠ Nothing is missing and not everything is staged: what is left COULD NOT BE READ.
            // No transfer is running for it and none is to be started, so no report will come
            // to settle it again. Nothing is concluded (the record and the bytes stay, and the
            // next resume reads again), and whoever waits is told, or they would wait until then.
            failWaiting(
                download,
                ModelDownloadException("What is stored for '${download.modelId}' could not be read just now; nothing was changed, and it is tried again."),
            )
            return
        }
        // ⚠⚠ **Every condition a download was admitted under holds again at every start**, or
        // the start is not made. A record outlives the step that admitted it by days, and the
        // transfers it restarts (at a relaunch, on a later foreground) are hundreds of megabytes:
        // the transport may be gone, and the disk may have filled.
        // ⚠ Refused here, the record, its staging and its running transfers all STAY: the
        // download is not over, it cannot go on now. Whoever waits is told why, the outcome
        // says so, and the next resume asks again.
        if (port == null) {
            notNow(download, ModelDownloadRefusal.NoTransport, ModelTransportUnavailableException())
            return
        }
        // ⚠ What the transport holds is not known: nothing is started, and nothing is concluded.
        // ⚠⚠ But it is SAID, like every start that cannot be made now. Silent, a download just
        // journalled would have no transfer, no step to come while the app stayed in the
        // foreground, and a caller waiting. The record and what
        // is staged stay; the caller asks again, which asks the transport again.
        if (live == null) {
            notNow(download, ModelDownloadRefusal.TransfersUnknown, ModelTransfersUnknownException())
            return
        }
        val toStart: List<JournaledArtifact> = missing.filter { it.ref !in live }
        if (toStart.isEmpty()) {
            // Every missing artifact has a transfer running.
            goesOn(download)
            return
        }
        val notStaged: Long = standings.filter { it.second != Standing.STAGED }.sumOf { it.first.byteSize }
        noRoomFor(download, notStaged)?.let { (reason, failure) ->
            notNow(download, reason, failure)
            return
        }
        toStart.forEach { artifact ->
            port.start(ModelTransferRequest(artifact.ref, "$baseUrl/${artifact.path}", download.allowsCellular))
        }
        // ⚠ After the starts, not before: said of what happened.
        goesOn(download)
    }

    /**
     * Why the volume has no room for what [download] still needs, or `null` when it has: the
     * bytes not staged yet ([notStagedBytes]), the prepare step of every artifact that is not
     * installed already, and the margin.
     *
     * ⚠ **One function for every start**: the one that admits a download and the ones that
     * restart a transfer of a download admitted long ago.
     * ⚠ Unknown free space is no room, there as here.
     * ⚠ An artifact still in flight is counted whole, although part of it is on the disk
     * already and so already missing from the free space: the check errs towards refusing.
     * ⚠ When every artifact is installed (only the record is to be rewritten) nothing is asked.
     */
    private fun noRoomFor(download: JournaledDownload, notStagedBytes: Long): Pair<ModelDownloadRefusal, Exception>? {
        // What the store already holds installed under the same digest is neither fetched nor
        // prepared again: its compiled model is keyed by that digest too.
        val toPrepare: Long = download.artifacts.filterNot { installedHolds(download, it) }.sumOf { it.byteSize }
        if (toPrepare == 0L) return null
        val needed: Long = additionalDiskNeed(toPrepare, notStagedBytes)
        val free: Long = reads.freeSpace()
            ?: return ModelDownloadRefusal.FreeSpaceUnknown to ModelFreeSpaceUnknownException()
        if (free >= needed) return null
        return ModelDownloadRefusal.InsufficientStorage(needed, free) to ModelStorageInsufficientException(needed, free)
    }

    /** A start that cannot be made now: said, to whoever waits and in the outcome; nothing ended. */
    private fun notNow(download: JournaledDownload, reason: ModelDownloadRefusal, failure: Exception) {
        report(ModelDownloadOutcome.Refused(download.key(), reason))
        finishWaiting(download.modelId, failure)
    }

    private enum class Standing { STAGED, MISSING, INCONCLUSIVE, WRONG_LENGTH, CORRUPT }

    private fun stand(download: JournaledDownload, artifact: JournaledArtifact): Standing {
        if (artifact.ref in verified) return Standing.STAGED
        val delivered: String = deliveredPath(artifact.ref)
        val size: Long? = reads.fileSize(delivered)
        if (size != null) {
            // ⚠⚠ Size, then every byte, from the disk: there is no running digest to trust, the
            // process that started this transfer may be long dead.
            if (size != artifact.byteSize) {
                acts.delete(delivered)
                return Standing.WRONG_LENGTH
            }
            val actual: String = hash(delivered, size)
                // ⚠ Not hashed is not wrong: the file is left, and nothing is concluded from it.
                ?: return Standing.INCONCLUSIVE
            if (actual != artifact.sha256) {
                acts.delete(delivered)
                return Standing.CORRUPT
            }
            acts.adoptStaged(download.modelId, artifact.role, artifact.sha256, delivered)
            markStaged(artifact)
            return Standing.STAGED
        }
        return stagingOf(download, artifact)
    }

    /**
     * Where the store's staging stands for [artifact]: staged when it holds the artifact whole,
     * **hashed now**. Bytes of the right length and the wrong content are dropped: they can
     * never become this artifact.
     *
     * ⚠⚠ Bytes of the right length that could not be read to the end are [Standing.INCONCLUSIVE],
     * not missing: nothing is fetched again on the strength of a read that failed.
     */
    private fun stagingOf(download: JournaledDownload, artifact: JournaledArtifact): Standing {
        if (artifact.ref in verified) return Standing.STAGED
        if (installedHolds(download, artifact)) {
            // ⚠⚠ Whatever is staged for this role is NOT this artifact's to keep: the commit
            // adopts the resident file, so staged bytes here are another digest's (an update
            // that failed or was taken over before the catalog came back to this one), or a
            // second copy. Left, they could be renamed onto the resident file.
            acts.abortStaging(download.modelId, artifact.role)
            markStaged(artifact)
            return Standing.STAGED
        }
        val staged: Long = acts.beginStaging(download.modelId, artifact.role, artifact.sha256)
        if (staged != artifact.byteSize) return Standing.MISSING
        val actual: String = hashOfStaged(download, artifact, staged) ?: return Standing.INCONCLUSIVE
        if (actual != artifact.sha256) {
            // ⚠ Hundreds of megabytes were hashed since the last question was asked.
            acts.abortStaging(download.modelId, artifact.role)
            return Standing.MISSING
        }
        markStaged(artifact)
        return Standing.STAGED
    }

    /**
     * Whether this install record is, field for field, the record a commit of [download] would
     * write. **One comparison of two whole records**: the second is built by the store's own
     * builder ([installedRecord]) from the entry, and placed at the paths the first one names,
     * which are the one thing a catalog entry does not decide.
     *
     * ⚠⚠ Not a list of fields kept by hand: such a list leaves out something the store persists
     * (the metadata, or the **role**, which `pathOf(role)` loads a tower by). Whatever a record
     * holds, now or later, is compared because the records are.
     */
    private fun InstalledModelData.isWhatWouldBeInstalled(download: JournaledDownload): Boolean =
        this == download.toEntry().installedRecord { role, catalogSize -> (pathOf(role) ?: "") to catalogSize }

    /**
     * Whether the store holds [artifact] **installed** under this very digest, its file there
     * at its recorded size. Then nothing is fetched or staged for it: the commit adopts the
     * resident file (`DefaultModelStore` never moves onto one), and only the record changes.
     *
     * ⚠ By the record's digest and a stat, not by hashing: an install record is the store's
     * word that verified bytes are there, which is what every reader of the store takes it for.
     */
    private fun installedHolds(download: JournaledDownload, artifact: JournaledArtifact): Boolean {
        val held: InstalledArtifactData = reads.installed().firstOrNull { it.modelId == download.modelId }
            ?.artifacts?.firstOrNull { it.role == artifact.role && it.sha256 == artifact.sha256 } ?: return false
        return held.byteSize == artifact.byteSize && reads.fileSize(held.localPath) == artifact.byteSize
    }

    /**
     * [stagingOf]'s question with none of its acts: whether [artifact] is installed, or staged
     * whole under its digest and hashed now. Bytes that are wrong, short or unreadable are
     * simply not counted, and stay where they are.
     */
    private fun stagedWithoutTouching(download: JournaledDownload, artifact: JournaledArtifact): Boolean {
        if (artifact.ref in verified || installedHolds(download, artifact)) return true
        val staged: Long = reads.stagedBytes(download.modelId, artifact.role, artifact.sha256)
        if (staged != artifact.byteSize) return false
        return hashOfStaged(download, artifact, staged) == artifact.sha256
    }

    /** The sha256 of what is staged for [artifact], or `null` when it could not all be read. */
    private fun hashOfStaged(download: JournaledDownload, artifact: JournaledArtifact, staged: Long): String? {
        val digest = Sha256Digest()
        var offset = 0L
        while (offset < staged) {
            val want: Int = minOf(chunkBytes.toLong(), staged - offset).toInt()
            val bytes: ByteArray = try {
                reads.readStaged(download.modelId, artifact.role, offset, want)
            } catch (failure: Exception) {
                null
            }?.takeIf { it.isNotEmpty() } ?: return null
            digest.update(bytes, bytes.size)
            offset += bytes.size
        }
        return hex(digest)
    }

    private fun markStaged(artifact: JournaledArtifact) {
        verified += artifact.ref
        synchronized(ticks) { tracked[artifact.ref]?.let { it.written = it.byteSize } }
    }

    /** The sha256 of the [size] bytes at [path], or `null` when they could not all be read. */
    private fun hash(path: String, size: Long): String? {
        val digest = Sha256Digest()
        var offset = 0L
        while (offset < size) {
            val want: Int = minOf(chunkBytes.toLong(), size - offset).toInt()
            val bytes: ByteArray = try {
                reads.readRange(path, offset, want)
            } catch (failure: Exception) {
                null
            }?.takeIf { it.isNotEmpty() } ?: return null
            digest.update(bytes, bytes.size)
            offset += bytes.size
        }
        return hex(digest)
    }

    private suspend fun commit(download: JournaledDownload) {
        alive()
        // ⚠⚠ Every artifact was hashed by this process, at its delivered path or in staging.
        check(download.artifacts.all { it.ref in verified }) { "commit of '${download.modelId}' before every artifact was hashed" }
        val installed: InstalledModelData = try {
            acts.changingTheStore(download.modelId) {
                acts.commitStaged(download.toEntry(), download.artifacts.associate { it.role to it.byteSize })
            }
        } catch (missing: ModelNotCommittableException) {
            // ⚠⚠ The store found an artifact neither installed nor staged for its digest: what
            // this process hashed is not what is there any more. That licenses nothing, so
            // every artifact is looked at again, from the disk, by the next settle.
            // ⚠ Only for this refusal. A commit that failed at its index write has made its
            // renames, and is finished by being made again with what was verified.
            download.artifacts.forEach { verified -= it.ref }
            throw missing
        }
        finish(download, installed)
    }

    /** The store holds [installed]. ⚠ That is what is reported, whatever the journal write does. */
    private fun finish(download: JournaledDownload, installed: InstalledModelData) {
        installedInThisStep += download.modelId
        download.artifacts.forEach { verified -= it.ref }
        runCatching { removeRecord(download) }
            // The record is still there; the next settle finds the model installed and tries again.
            .onFailure { println("BackgroundModelDownloader -> '${download.modelId}' is installed, its journal record was not removed: $it") }
        untrack(download)
        progress.finish(download.modelId)
        report(ModelDownloadOutcome.Installed(download.key(), installed))
        takeWaiters(download.modelId).forEach { it.complete(installed) }
    }

    /**
     * The download cannot go on. ⚠⚠ The record goes **first**: if that write fails nothing else
     * changes (no transfer cancelled, no file deleted, no staging touched) and the next settle
     * meets the same download again.
     * ⚠⚠ **Whoever waits is told all the same.** Otherwise the caller would wait on a download
     * whose failed transfer nothing would start again before the next return to the foreground.
     */
    private fun fail(download: JournaledDownload, integrity: Boolean, failure: Exception) {
        // ⚠⚠ The record could not be read, or could not be removed: it stays, nothing is
        // released, and the next settle meets the same download. But the transfer that brought
        // this step here has ended, so whoever waits is told now, with what is true: the
        // download is not over, and it did not go on.
        val held: List<JournaledDownload> = readForAction().orElse {
            journalUnreadableFor(download)
            return
        }
        try {
            drop(download, held, Staging.KEPT_FOR_A_RETRY)
        } catch (tornDown: Retired) {
            throw tornDown
        } catch (unwritten: Exception) {
            println("BackgroundModelDownloader -> '${download.modelId}' failed and its record could not be removed: $unwritten")
            // ⚠ What failed is said as it is (a wrong digest stays an integrity failure); that
            // the record is still there is what the next settle acts on.
            report(ModelDownloadOutcome.Failed(download.key(), integrity, failure.message.orEmpty()))
            finishWaiting(download.modelId, failure)
            return
        }
        report(ModelDownloadOutcome.Failed(download.key(), integrity, failure.message.orEmpty()))
        finishWaiting(download.modelId, failure)
    }

    /** Whoever waits is told; the record stays, so the next settle tries again. */
    private fun failWaiting(download: JournaledDownload, failure: Exception) {
        val refusal: ModelDownloadRefusal? = when (failure) {
            is ModelStoreNotOwnedException, is ModelIndexUnreadableException ->
                ModelDownloadRefusal.StoreRefused(failure.message.orEmpty())
            else -> null
        }
        report(
            if (refusal != null) {
                ModelDownloadOutcome.Refused(download.key(), refusal)
            } else {
                ModelDownloadOutcome.Failed(download.key(), integrity = false, failure.message.orEmpty())
            },
        )
        finishWaiting(download.modelId, failure)
    }

    /** Removes [download]'s record, then what only that record named. Throws when the write fails. */
    private fun drop(download: JournaledDownload, held: List<JournaledDownload>, staging: Staging) {
        acts.writeJournal(held.filterNot { it.modelId == download.modelId })
        try {
            release(download, acts.port(), staging = staging)
        } catch (failure: Exception) {
            // ⚠⚠ The record IS gone, whatever of its files could not be cleared: whoever waits
            // on the download is told so here, since the caller's own telling comes after a
            // call that has just thrown. Left waiting, they waited for a download no journal names.
            if (failure !is Retired) {
                finishWaiting(download.modelId, ModelDownloadCancelledException("The download of '${download.modelId}' was ended; its files could not all be cleared."))
            }
            throw failure
        }
    }

    /** ⚠ Only after the record that named them is gone from the disk. */
    private fun release(
        download: JournaledDownload,
        port: ModelTransferPort?,
        keep: Set<String> = emptySet(),
        staging: Staging,
    ) {
        // ⚠⚠ What the record had STAGED is named by nothing once the record is gone, and the
        // store cannot list its own staging: bytes left here that nothing later claims are
        // never reclaimed. So every path that ends a record says what becomes of them.
        // ⚠⚠ FIRST, before anything below that can throw: the record is gone from the disk, so
        // the download is not running, whatever becomes of its files.
        if (keep.isEmpty()) {
            untrack(download)
            progress.finish(download.modelId)
        }
        when (staging) {
            Staging.KEPT_FOR_A_RETRY -> Unit
            Staging.DROPPED -> download.artifacts.forEach { acts.abortStaging(download.modelId, it.role) }
            is Staging.DROPPED_WHERE_NOT_TAKEN_OVER ->
                download.artifacts.filterNot { it.role in staging.rolesTakenOver }
                    .forEach { acts.abortStaging(download.modelId, it.role) }
        }
        download.artifacts.filterNot { it.ref in keep }.forEach { artifact ->
            port?.cancel(artifact.ref)
            acts.delete(deliveredPath(artifact.ref))
            verified -= artifact.ref
        }
    }

    /** What becomes of a record's staged artifacts when the record ends. */
    private sealed interface Staging {
        /**
         * A transfer failed, or delivered the wrong bytes: the artifacts that did arrive are
         * what makes the retry cheap. ⚠ Reclaimed by that retry, or by the next download of
         * the model, role by role; by nothing if neither ever comes (`tech-debt.md`).
         */
        data object KEPT_FOR_A_RETRY : Staging

        /** The user cancelled, or removed the model: nothing of it is wanted. */
        data object DROPPED : Staging

        /**
         * A newer entry took over: its own settle replaces the staging of every role it has,
         * by `beginStaging` for a role it fetches and by dropping it for a role the install
         * record already holds ([stagingOf]).
         * A role only the older entry had (renamed, or gone) is dropped here, or nothing would.
         */
        class DROPPED_WHERE_NOT_TAKEN_OVER(val rolesTakenOver: Set<String>) : Staging
    }

    private fun removeRecord(download: JournaledDownload) {
        // ⚠ Unreadable: the record stays, and the next settle finds the model installed.
        val held: List<JournaledDownload> = readForAction().orElse { return }
        if (held.none { it.modelId == download.modelId && it.key() == download.key() }) return
        acts.writeJournalOnceTheStoreHasCommitted(held.filterNot { it.modelId == download.modelId })
    }

    /** The journal could not be read under a step that was [download]'s last: said, and nothing ended. */
    private fun journalUnreadableFor(download: JournaledDownload) =
        notNow(download, ModelDownloadRefusal.JournalUnreadable, ModelDownloadJournalUnreadableException())

    /**
     * The same, when the record itself is what could not be read: the model is the one [ref] is
     * tracked for. ⚠ No outcome is reported: an outcome is keyed by the record nobody could read.
     */
    private fun journalUnreadableUnder(ref: String) {
        val modelId: String = synchronized(ticks) { tracked[ref]?.modelId } ?: return
        finishWaiting(modelId, ModelDownloadJournalUnreadableException())
    }

    private fun sweep(held: List<JournaledDownload>, live: Set<String>?) {
        val named: Set<String> = held.flatMapTo(mutableSetOf()) { download -> download.artifacts.map { it.ref } }
        val port: ModelTransferPort? = acts.port()
        if (port != null && live != null) (live - named).forEach { ref -> port.cancel(ref) }
        val delivered: Set<String> = runCatching { deliveredRefs() }.getOrNull() ?: return
        (delivered - named).filter(::isRef).forEach { ref -> acts.delete(deliveredPath(ref)) }
    }

    /** The teardown landed: this graph acts no more. Never leaves this class but through [download] and [cancel]. */
    private class Retired : Exception("This runtime was torn down; it starts, writes and installs nothing more.")

    /**
     * Asked **immediately before every act**, and nowhere earlier on its behalf: a check made
     * before a wait says nothing about after it.
     *
     * ⚠⚠ What it cannot close: the instant between this read and the one act behind it. `retire`
     * is a flag set from the main thread and may take no lock, so a teardown can still land
     * there. ⚠ But the next graph is not in a step while it does: every graph's steps run under
     * one lock ([mutex]), so the act below is finished before the next graph reads anything.
     * Each act it can then let through is one whose effect the next graph absorbs:
     * - a transfer **started**: the transport refuses a ref it already holds
     *   ([ModelTransferPort.start]), and the next graph finds this one live;
     * - a transfer **cancelled**, or a delivered file **deleted**: the next graph finds it
     *   missing and starts it again;
     * - a journal **write**: the record is one the next graph reads. (It could race that
     *   graph's own write, and lose it, until the lock was the process's.)
     * - a file **moved into staging**: the next graph finds it staged and hashes it again;
     * - the **commit**: the store holds a model this graph committed and the next graph's
     *   store, which read its index earlier, does not know of until the process is replaced.
     */
    private fun alive() {
        // ⚠ [gone] as well as [retired]: [tornDown] has told whoever waited that nothing more
        // happens here, and a step still on its way (a caller's, which is never cancelled) must
        // then journal and start nothing, whether or not the flag it was given has been set yet.
        if (retired() || synchronized(ticks) { gone }) throw Retired()
    }

    /**
     * The journal, for a step that will start, cancel, delete or write on the strength of it.
     *
     * ⚠⚠ **Not a nullable list, on purpose.** With `null` for *could not be read*, one call site's
     * `.orEmpty()` turns a failed read into "no record", which is the one conclusion this code
     * forbids (absent, unreadable and undecodable are three things).
     * A [Journal] has no accessor that makes that choice silently: [orElse] is the only way to
     * the downloads, and its argument cannot return a value. Every call site says what an
     * unreadable journal means **there**.
     */
    private fun readForAction(): Journal {
        // ⚠⚠ Set in the branch, not asked afterwards with `journal is Journal.Unreadable`: on
        // Kotlin/Native that test answered TRUE for a `Journal.Read` here (a value typed by a
        // `when` whose first branch is the object), on the simulator and not on the JVM, and
        // every read was said to have failed (§14.18.10).
        var failed = false
        val journal: Journal = when (val read: JournalRead = reads.journal()) {
            JournalRead.Unreadable -> {
                failed = true
                Journal.Unreadable
            }
            JournalRead.Absent -> Journal.Read(emptyList())
            // ⚠ By decision: no journal. See [ModelDownloadJournal].
            JournalRead.Undecodable -> Journal.Read(emptyList())
            is JournalRead.Held -> Journal.Read(read.downloads)
        }
        // ⚠ Said here, at the one place a step reads the journal: that the last read failed, and
        // that a later one did not. What the journal NAMES is still only what a step tracked.
        changing { lastReadFailed = failed }
        return journal
    }

    private sealed interface Journal {
        class Read(val downloads: List<JournaledDownload>) : Journal

        data object Unreadable : Journal
    }

    /** The downloads, or [unreadable], which must leave: it returns, throws or refuses. */
    private inline fun Journal.orElse(unreadable: () -> Nothing): List<JournaledDownload> = when (this) {
        is Journal.Read -> downloads
        Journal.Unreadable -> unreadable()
    }

    private fun refuse(key: ModelDownloadKey, reason: ModelDownloadRefusal, failure: Exception): Nothing {
        report(ModelDownloadOutcome.Refused(key, reason))
        throw failure
    }

    private fun report(outcome: ModelDownloadOutcome) {
        reported.value = outcome
    }

    /**
     * [download] is going on: nothing refuses its next start, and nothing of it failed at this
     * look. Whatever was last said **of this key** no longer stands, and is withdrawn.
     *
     * ⚠⚠ Without it the last word would outlive what it described: a download refused for want
     * of room goes on once there is room, and [lastOutcome] would still say *refused*; one that
     * was cancelled and started again would still say *cancelled*. Nothing read the outcome but the
     * waiter it was made for, so nothing showed it (§14.18).
     * ⚠ Only this key's: what was said of another download is not this one's to withdraw.
     */
    private fun goesOn(download: JournaledDownload) {
        val key: ModelDownloadKey = download.key()
        reported.update { said -> if (said?.key == key) null else said }
    }

    private fun finishWaiting(modelId: String, failure: Exception) {
        takeWaiters(modelId).forEach { it.completeExceptionally(failure) }
    }

    /**
     * Tracks [download] **as the model's one record**: its refs, and no other ref of that model.
     *
     * ⚠⚠ What is tracked is a copy, in memory, of what the journal names, kept for two
     * questions that are asked too often to read a file for (is it running; how far is it).
     * A copy can drift, so: a ref is added only here, only under [mutex], only from a record a
     * step has just read or just written; whatever else was tracked for the model goes in the
     * same breath (a record that replaced another leaves none of the other's refs behind);
     * and nothing is tracked on a graph that is gone.
     */
    private fun track(download: JournaledDownload) {
        val staged: Long = changing {
            if (gone) return
            trackUnderTheLock(download)
        }
        startTheBar(download.modelId, download.artifacts.sumOf { it.byteSize }, staged)
    }

    /** Under [ticks], inside [changing]. Returns what is held of [download] already. */
    private fun trackUnderTheLock(download: JournaledDownload): Long {
        val refs: Set<String> = download.artifacts.mapTo(mutableSetOf()) { it.ref }
        val record = JournalledModelDownload(download.key(), download.allowsCellular)
        tracked.entries.removeAll { (ref, known) -> known.modelId == download.modelId && ref !in refs }
        download.artifacts.forEach { artifact ->
            // ⚠ The record is replaced on a ref two records share: every ref of a model names
            // the ONE record the journal holds for it, with what was written kept.
            val before: Tracked? = tracked[artifact.ref]
            val known = Tracked(download.modelId, artifact.byteSize, before?.written ?: 0L, record)
            tracked[artifact.ref] = known
            if (artifact.ref in verified) known.written = known.byteSize
        }
        return tracked.values.filter { it.modelId == download.modelId }.sumOf { it.written }
    }

    /**
     * ⚠ The first snapshot already carries what is held: a bar must not start again from 0.
     * ⚠ And a bar is the record's: when another record has taken the model over, its total is
     * that record's, or the bar would run past its end, or stop short of it.
     */
    private fun startTheBar(modelId: String, total: Long, staged: Long) {
        if (progress.progress.value[modelId]?.totalBytes != total) progress.start(modelId, total, staged)
    }

    private fun publishProgress(download: JournaledDownload) {
        val held: Long = synchronized(ticks) { tracked.values.filter { it.modelId == download.modelId }.sumOf { it.written } }
        progress.update(download.modelId, held)
    }

    private fun untrack(download: JournaledDownload) {
        changing { download.artifacts.forEach { tracked.remove(it.ref) } }
    }

    private fun ModelEntryData.journalled(allowsCellular: Boolean): JournaledDownload = JournaledDownload(
        modelId = modelId,
        name = name,
        modelVersion = modelVersion,
        capabilities = capabilities.map { it.id },
        labelSetVersion = labelSetVersion,
        runtime = runtime,
        artifactVersion = artifactVersion,
        licence = licence,
        allowsCellular = allowsCellular,
        artifacts = artifacts.map { JournaledArtifact(it.role, it.path, it.sha256, it.byteSize, refOf(modelId, it, allowsCellular)) },
    )

    private fun deliveredPath(ref: String): String = deliveredPath(downloadsDir, ref)

    @OptIn(ExperimentalStdlibApi::class)
    private fun hex(digest: Sha256Digest): String = digest.digest().toHexString()

    companion object {
        /** [noteTheJournal]: a model whose record the journal no longer holds. */
        private const val NO_LONGER_NAMED: Long = -1L

        /** Under the `inference/` tree the owner purges whole (§13.1). */
        const val DEFAULT_DOWNLOADS_DIR: String = "inference/downloads"
        const val JOURNAL_FILE: String = "journal.json"
        const val DELIVERED_SUFFIX: String = ".delivered"
        private const val TORN_DOWN: String = "This runtime was torn down; the download goes on in the next one."
        private const val REF_DIGEST_CHARS: Int = 16
        private const val REF_WIFI_ONLY: String = "wifi"
        private const val REF_ANY_NETWORK: String = "any"

        /** Where the transport puts [ref]'s body: the one path a transfer may write. */
        fun deliveredPath(downloadsDir: String, ref: String): String = "$downloadsDir/$ref$DELIVERED_SUFFIX"

        /**
         * ⚠ A ref becomes a file name and travels through the transport, so it is built from the
         * catalog's text and never taken from it as it is.
         *
         * ⚠⚠ **The cellular choice is part of the ref.** A transfer keeps the request it was
         * started with for its whole life, and a transfer the transport still holds is never
         * started again. So a ref that named the artifact alone would let a Wi-Fi-only download
         * be carried by a transfer an earlier download had started with cellular allowed (one
         * whose cancel never reached the transport: the process died first). Under another
         * choice the same artifact is another ref: the old transfer is one no record names,
         * and the sweep cancels it.
         */
        fun refOf(modelId: String, artifact: ModelArtifactData, allowsCellular: Boolean): String =
            (listOf(modelId, artifact.role, artifact.sha256.take(REF_DIGEST_CHARS)).map { part ->
                part.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
            } + if (allowsCellular) REF_ANY_NETWORK else REF_WIFI_ONLY).joinToString(".")

        /** Whether [ref] could be one of [refOf]'s: nothing else is ever turned into a path. */
        fun isRef(ref: String): Boolean =
            ref.isNotEmpty() && ref.length <= 200 && ref.first() != '.' &&
                ref.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } && ".." !in ref
    }
}
