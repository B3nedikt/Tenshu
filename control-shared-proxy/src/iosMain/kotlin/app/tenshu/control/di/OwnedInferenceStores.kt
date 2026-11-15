package app.tenshu.control.di

import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TByteArray
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ReplicaEpochSource
import org.tenshu.inference.proxy.DefaultEmbeddingStore
import org.tenshu.inference.proxy.DefaultFaceTemplateStore
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.InferenceRealm
import org.tenshu.inference.proxy.RealmReading
import org.tenshu.inference.proxy.InferenceStoreCustody
import org.tenshu.inference.proxy.InferenceStoreGate
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.catalog.FileModelIndexStorage
import org.tenshu.inference.proxy.catalog.ModelIndexStorage
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.coreml.iosRuntimeFingerprint
import org.tenshu.sync.common.SyncManager
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * The bindings of the **one** route-1 app that owns inference on this device — Photos
 * (PIO-D10, `ios-on-device-inference.md` §3.1). Applied last, over [buildPlatformProxyModule],
 * which binds every iOS graph the stores that cannot write.
 *
 * What it moves into the app's own container, at [root]:
 * - the vector and face-template logs;
 * - the model bytes, **and their index**: the Keychain survives an uninstall and this container
 *   does not, so an index left there would report a model whose bytes are gone;
 * - the realm stamp and the runtime fingerprint that keep the logs honest ([InferenceStoreCustody]).
 *
 * ⚠⚠ No backend, no indexing conditions and no model runtime are bound here
 * (`photosOnDeviceAiModule` binds them); the stamp and its four checks hold whether or not one is.
 *
 * @param deviceRealmOwner the realm the DEVICE is on, as Control's hand-off states it. ⚠ Not this
 *   graph's `SyncManager`: a resident runtime keeps the owner it was assembled with across a reset
 *   it did not see, and that reset is what the stamp exists to catch.
 * @param excludeFromBackup marks a directory excluded from backup and answers why it could not, or
 *   `null`. A test's seam; production is [OwnedInferenceStores.excludeFromBackup].
 * @param storeEpoch the shared store's `meta.epoch` now, or `null` when it cannot be told. A test's
 *   seam; `null` (production) reads this graph's own store, [OwnedInferenceStores.replicaEpochOf].
 */
fun ownedInferenceStoresModule(
    root: String = OwnedInferenceStores.privateRoot(),
    deviceRealmOwner: () -> String? = { RealmKeyMaterialBridge.read()?.ownerId },
    excludeFromBackup: (String) -> String? = OwnedInferenceStores::excludeFromBackup,
    storeEpoch: (() -> Long?)? = null,
): Module = module {
    single<FileDatabase>(named(INFERENCE_FILE_DATABASE)) { OwnedInferenceStores.openDatabase(root, excludeFromBackup) }
    // ⚠⚠ B.16 (247): built, and so their logs read, only on first use after a stopped graph's
    // writer has ended; resolving them early (every resolver of these two) reads nothing.
    single<EmbeddingStore> {
        val files: FileDatabase = get(named(INFERENCE_FILE_DATABASE))
        AfterPriorWriterEmbeddingStore { DefaultEmbeddingStore(files, INFERENCE_EMBEDDINGS_PATH) }
    }
    single<FaceTemplateStore> {
        val files: FileDatabase = get(named(INFERENCE_FILE_DATABASE))
        AfterPriorWriterFaceTemplateStore { OwnedInferenceStores.templatesOver(files) }
    }
    single<ModelIndexStorage> { FileModelIndexStorage(get(named(INFERENCE_FILE_DATABASE))) }
    single {
        InferenceStoreCustody(
            files = get(named(INFERENCE_FILE_DATABASE)),
            embeddings = get(),
            faces = get(),
            cancelPass = { get<MediaIndexRunner>().cancelRunningPass() },
            // IN-D6a: the arrived paths are the realm's too, cleared with its stores.
            arrivals = getOrNull(),
            readRealm = {
                // ⚠⚠ Both halves, or no answer (`ios-face-grouping.md` §2.2 item 8, threat model
                // A40). The owner alone cannot see a reset that keeps it; the store's epoch moves
                // at every wipe and migration. ⚠⚠ An epoch that cannot be read is CannotTell:
                // neither "the same one" (this asker is refused) nor "another one" (no running pass
                // is stopped, nothing is cleared). A read refused for a moment — a sibling's -wal
                // reset, a deferred admission, protected data — must not stop indexing.
                val owner: String? = deviceRealmOwner()
                if (owner == null) {
                    RealmReading.None
                } else {
                    // ⚠ A seam's `null` is its answer, never a cue to read the graph's store instead.
                    // ⚠ A closed graph's store cannot be resolved: that too is "cannot tell".
                    val epoch: Long? = if (storeEpoch != null) {
                        storeEpoch()
                    } else {
                        runCatching { get<ObjectStore>() }.getOrNull()?.let(OwnedInferenceStores::replicaEpochOf)
                    }
                    if (epoch == null) {
                        RealmReading.CannotTell(owner, "the shared store's epoch could not be read")
                    } else {
                        RealmReading.Of(InferenceRealm(ownerId = owner, storeEpoch = epoch.toString()))
                    }
                }
            },
            runtimeServes = { realm -> get<SyncManager>().residentOwnerId == realm.ownerId },
            // ⚠ In the custody, not beside it: the gate a query or a pass reaches first must not
            // open on the realm alone.
            currentFingerprint = { iosRuntimeFingerprint(get<ModelStore>().installed()) },
        )
    }
    // ⚠⚠ ios-shared-store-sqlite.md B.16 (246): the gate a query or a pass reaches first waits
    // for a stopped graph's writer before the custody may open the files.
    single<InferenceStoreGate> { WriterBarrierGate(get<InferenceStoreCustody>()) }
}

/**
 * What the owning runtime calls on [ownedInferenceStoresModule]'s graph. Here, and not in the
 * runtime, because the umbrella does not see `:inference-service-proxy`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object OwnedInferenceStores {

    /**
     * `<Application Support>/photos-private` in the calling app's own container — never the App
     * Group. ⚠ Not `inference`: the stores' logical paths already begin with it, and the logs
     * would land at `inference/inference/…`.
     */
    fun privateRoot(): String = autoreleasepool {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
            ?: error("No Application Support directory in this container.")
        "$support/$ROOT_DIRECTORY"
    }

    /**
     * The database at [root], with the directory marked **excluded from backup** — or, when the
     * mark cannot be made, a database that reads nothing and refuses to store anything.
     *
     * ⚠⚠ Application Support is backed up. Vectors are derived, models are public weights several
     * hundred MB large, and templates are biometric (threat model A40): none belongs in an
     * iCloud or Finder backup. So an exclusion that failed is not a warning:
     * - what the directory **already holds** is removed first ([purge]): a directory that lost
     *   its flag, or cannot be given it again, is being backed up with everything in it;
     * - then the refusing database: the custody's stamp write throws, it ends `ClearFailed` with
     *   this reason, nothing is usable and nothing is stored.
     *
     * ⚠ The flag lives on the directory entry, so it is set on every open: a directory that was
     * removed and made again has lost it. ⚠ The directory is made first because the flag needs
     * one; nothing is put in it before the flag is confirmed.
     *
     * @param purge removes what [root] holds and answers what it could not remove. A test's seam.
     */
    fun openDatabase(
        root: String,
        excludeFromBackup: (String) -> String? = ::excludeFromBackup,
        purge: (String) -> List<String> = ::purge,
    ): FileDatabase {
        val refused: String? = runCatching { excludeFromBackup(root) }.getOrElse { it.toString() }
        if (refused != null) {
            println("OwnedInferenceStores -> $root cannot be excluded from backup, inference stores unusable: $refused")
            // ⚠ Whatever the purge does, the store still comes up refusing — and goes on trying.
            return NotExcludedFromBackup(root, refused, purge).also { it.retryPurge() }
        }
        return IosFileDatabase(root)
    }

    /**
     * The template store over [database].
     *
     * ⚠⚠ Over the refusing database the store reads as empty **whatever is on disk**: a purge
     * that failed leaves files the store cannot see. Every caller that decides "nothing to
     * delete" from the store's own stamp — both consent-withdrawal paths do — would then skip its
     * clear and leave biometric templates in a directory that is being backed up. So here the
     * question itself retries the purge: asking this store for its stamp first removes what the
     * last purge could not. ⚠ Everything else the store is asked reaches the database, which
     * retries by itself; the stamp is the one answer given from memory.
     * ⚠ A healthy database gets the plain store. Its stamp is the truth, and "nothing to delete"
     * is right.
     */
    internal fun templatesOver(database: FileDatabase): FaceTemplateStore {
        val store = DefaultFaceTemplateStore(database, INFERENCE_FACES_PATH)
        if (database !is NotExcludedFromBackup) return store
        return object : FaceTemplateStore by store {
            override suspend fun provenance(): ModelProvenance? = database.retryPurge().let { store.provenance() }
        }
    }

    /**
     * Removes every inference file under [root] — the logs, the stamp, the fingerprint, **and the
     * models with their index** — and answers the paths still there afterwards.
     *
     * ⚠⚠ The models go too, unlike a realm mismatch, which never clears them. That rule is about
     * realms: weights are the same for every realm, so clearing them there buys nothing and costs
     * a download. Here the cost runs the other way: left in place they are several hundred MB
     * uploaded into the user's backup, and behind the refusing database nothing can read them. The
     * index goes with the bytes, so nothing reports a model that is gone.
     */
    fun purge(root: String): List<String> = autoreleasepool {
        val files = NSFileManager.defaultManager
        val tree = "$root/$LOGICAL_PREFIX"
        if (files.fileExistsAtPath(tree)) files.removeItemAtPath(tree, error = null)
        if (!files.fileExistsAtPath(tree)) {
            emptyList()
        } else {
            // ⚠ A directory cannot be removed while one entry in it cannot: say which are left.
            listOf(tree) + files.subpathsAtPath(tree).orEmpty().map { "$tree/$it" }
        }
    }

    /**
     * Makes [directory] and marks it excluded from backup. `null` when the mark is in place and
     * reads back; otherwise why not.
     */
    fun excludeFromBackup(directory: String): String? = autoreleasepool {
        memScoped {
            val failure = alloc<ObjCObjectVar<NSError?>>()
            if (!NSFileManager.defaultManager.createDirectoryAtPath(
                    path = directory,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = failure.ptr,
                )
            ) {
                return@autoreleasepool "cannot create the directory: ${failure.value?.localizedDescription}"
            }
            val url = NSURL.fileURLWithPath(directory, isDirectory = true)
            if (!url.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = failure.ptr)) {
                return@autoreleasepool "cannot set the exclusion: ${failure.value?.localizedDescription}"
            }
            // ⚠ Read back from a fresh URL: an NSURL caches its resource values.
            val read = alloc<ObjCObjectVar<Any?>>()
            NSURL.fileURLWithPath(directory, isDirectory = true)
                .getResourceValue(read.ptr, forKey = NSURLIsExcludedFromBackupKey, error = null)
            if ((read.value as? NSNumber)?.boolValue != true) "the exclusion did not read back" else null
        }
    }

    /**
     * The realm check and the runtime-fingerprint rule, in that order and as one step
     * ([InferenceStoreCustody.ensureCurrentRealm]). `install` and every return to the foreground
     * call this; the pass and `photosLike` reach the same check through [InferenceStoreGate].
     *
     * ⚠⚠ Off the main thread, always. The first call constructs the stores, which read their logs
     * whole, and a mismatch clears them.
     *
     * @return whether the stores are usable now.
     */
    suspend fun check(koin: Koin): Boolean {
        // ⚠⚠ B.16 (246): never before a stopped graph's index pass has really ended.
        awaitPriorWriter()
        return koin.get<InferenceStoreCustody>().ensureCurrentRealm()
    }

    /** A stopped graph's index pass being stopped (B.16 (241), (246)), or null. */
    @kotlin.concurrent.Volatile
    private var priorWriter: kotlinx.coroutines.Job? = null

    /**
     * ⚠⚠ B.16 (246): [writer] is a stopped graph's pass being cancelled and joined. Until it
     * completes, **no graph of this process opens the inference files**: [check] and every gate
     * wait for it, with no deadline (off the main thread, as both already are), and the stores
     * stay unusable meanwhile, as during any other pending check. A deadline would let the next
     * graph read a half-written log, or append over the old graph's write: each graph's stores
     * keep their own `Mutex` and their own append offsets.
     */
    fun holdOpensUntil(writer: kotlinx.coroutines.Job) {
        // ⚠⚠ (B.16 (248)) Chained, never overwritten: graph 1's writer hung, graph 2 fenced and
        // its writer done at once, and graph 3 must still wait for graph 1's.
        lock.withLockedValue {
            val previous = priorWriter
            priorWriter = chainScope.launch {
                previous?.join()
                writer.join()
            }
        }
    }

    /**
     * Waits for every writer [holdOpensUntil] was given, with no deadline. Cancellable. ⚠ A writer
     * that never ends leaves on-device AI not-ready until the next launch; said once, after about
     * [HELD_LOG_MS] (B.16 (248)).
     */
    internal suspend fun awaitPriorWriter() {
        val writer = priorWriter ?: return
        if (writer.isCompleted) return
        if (withTimeoutOrNull(HELD_LOG_MS) { writer.join() } == null) {
            if (!heldLogged) {
                heldLogged = true
                println("OwnedInferenceStores -> inference stores held: a stopped graph's index pass has not ended")
            }
            writer.join()
        }
    }

    @kotlin.concurrent.Volatile
    private var heldLogged = false

    private const val HELD_LOG_MS: Long = 5_000L

    private val lock = platform.Foundation.NSRecursiveLock()

    private val chainScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private inline fun <T> platform.Foundation.NSRecursiveLock.withLockedValue(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    /** ⚠ Test seam: forgets every writer held (each suite starts from a process with none). */
    fun clearPriorWritersForTests() {
        lock.withLockedValue { priorWriter = null }
        heldLogged = false
    }

    /**
     * The shared store's `meta.epoch` as [store] reads it **now** ([ReplicaEpochSource.currentEpoch]),
     * or `null`: the not-ready stand-in, a fenced handle, a refused access, or a store with no
     * epoch at all. ⚠ `null` is "cannot tell", never "unchanged" — see [ownedInferenceStoresModule].
     * ⚠ Blocking (one empty transaction on the SQLite store); the custody asks it off the main thread.
     */
    fun replicaEpochOf(store: ObjectStore): Long? = epochSourceOf(store)?.currentEpoch()

    /** A function of its own: the type test is never made in place on another call's value (K/N). */
    private fun epochSourceOf(store: ObjectStore): ReplicaEpochSource? = store as? ReplicaEpochSource

    /** Whether the stores may be read or written: what a backend's readiness consults. */
    fun usable(koin: Koin): Boolean = koin.get<InferenceStoreCustody>().usable()

    /**
     * ⚠ `ios-shared-store-sqlite.md` B.16 (241), (245): what stops this graph's running index pass
     * and waits for it, without clearing anything, **resolved now**: the caller runs it later, on a
     * scope of its own, by which time the graph's Koin may be closed (the rebuild closes it), and a
     * lookup then would find nothing to cancel. ⚠ Each graph's Koin has its own stores, each with
     * its own `Mutex`, so nothing serialises the two graphs' writes but this join: the rebuilt graph
     * must open the files only after it returns. Never throws.
     */
    fun writerStopper(koin: Koin): suspend () -> Unit {
        val runner: MediaIndexRunner? = runCatching { koin.getOrNull<MediaIndexRunner>() }.getOrNull()
        return {
            // ⚠⚠ (B.16 (249)) Every pass's end, whoever cancelled it: the graph's own unload
            // (`AiReadiness.stopServing`) may claim the pass first, and a bare cancel would then
            // find nothing and return while that pass still writes.
            runCatching { runner?.stopAndAwaitEnded() }
                .onFailure { println("OwnedInferenceStores -> stopping the pass failed: $it") }
        }
    }

    /**
     * Clears the vectors and the templates for a deprovision. Blocking, and **bounded**: this runs
     * on the launch path.
     *
     * - With a graph: the custody's own clear, given [RELINQUISH_BUDGET_MS]. The only wait in it
     *   without a bound of its own is a running pass letting go.
     * - Without one, or past the budget: the files by path, a handful of small writes and unlinks.
     *
     * ⚠ Either way the stamp goes first, so whatever is left when this returns is cleared by the
     * next check before anything reads it.
     *
     * @return `false` when something could not be cleared now.
     */
    fun relinquish(koin: Koin?, root: String = privateRoot()): Boolean = runCatching {
        val custody: InferenceStoreCustody? = koin?.getOrNull()
        val cleared: Boolean = custody != null && runCatching {
            runBlocking {
                withTimeoutOrNull(RELINQUISH_BUDGET_MS) {
                    custody.relinquish()
                    true
                }
            } == true
        }.getOrDefault(false)
        // ⚠ By path at [root], never through a database resolved from [koin]: in a graph that is
        // not the owner's, that name is the App Group's.
        // ⚠ A plain database, not [openDatabase]: erasing must not wait on the backup exclusion.
        if (!cleared) {
            InferenceStoreCustody.eraseAtRest(
                IosFileDatabase(root),
                // ⚠ IN-D6a's arrived paths too: names of the realm's photographs.
                // ⚠ And where the log was last read: no path, but this realm's.
                listOf(INFERENCE_EMBEDDINGS_PATH, INFERENCE_FACES_PATH, INFERENCE_ARRIVALS_PATH) + app.tenshu.control.inference.ARRIVALS_CURSOR_PATH,
            )
        }
    }.onFailure { println("OwnedInferenceStores -> relinquish failed, the next check clears: $it") }.isSuccess

    /** The directory at [root] is not excluded from backup, so nothing may be stored in it. */
    class NotExcludedFromBackupException(root: String, reason: String) :
        IllegalStateException("$root is not excluded from backup ($reason); nothing is written to it")

    /**
     * Reads as empty and refuses to store anything — see [openDatabase].
     *
     * ⚠⚠ **Removing is never refused.** A delete, and the empty write a store's `clear` makes
     * before it, go to the real directory: a realm wipe, a consent withdrawal and a deprovision
     * must still be able to erase whatever the purge left. ⚠ An empty write where nothing is does
     * nothing, so a clear of nothing creates no file.
     *
     * ⚠⚠ **And a purge that left something is retried at every access** ([retryPurge]), from here
     * and from the two stores built over this ([templatesOver]): hidden reads mean nobody above
     * can tell that files remain, so nobody above can be relied on to ask for them to go.
     */
    internal class NotExcludedFromBackup(
        private val root: String,
        private val reason: String,
        private val purge: (String) -> List<String>,
    ) : FileDatabase {
        private val real = IosFileDatabase(root)

        /** What the last purge left, or one entry saying it threw. Empty once a purge is clean. */
        @Volatile
        private var remaining: List<String> = listOf(NEVER_PURGED)

        /**
         * Purges again while the last purge left something. Never throws; logs what remains
         * whenever that changes. ⚠ Nothing can arrive after a clean purge — every store through
         * this database is refused — so a clean one ends the retrying.
         */
        fun retryPurge() {
            val before: List<String> = remaining
            if (before.isEmpty()) return
            val now: List<String> = runCatching { purge(root) }.getOrElse { listOf("the purge threw: $it") }
            remaining = now
            if (now.isNotEmpty() && now != before) {
                println("OwnedInferenceStores -> not purged, still in $root (not excluded from backup): $now")
            }
        }

        /** Every access is a chance to finish the purge. */
        private fun touched() = retryPurge()

        private fun refuse(): Nothing = throw NotExcludedFromBackupException(root, reason)
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            touched()
            if (bytes.isNotEmpty()) refuse()
            if (real.fileSize(filePath) != null) real.writeRawByteArray(filePath, bytes)
        }
        override fun writeByteArray(filePath: String, bytes: TByteArray) = writeRawByteArray(filePath, bytes.get())
        override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray): Unit = touched().let { refuse() }
        override fun moveFile(fromPath: String, toPath: String): Unit = touched().let { refuse() }
        override fun readBytes(filePath: String): TByteArray? = touched().let { null }
        override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? = touched().let { null }
        override fun fileSize(filePath: String): Long? = touched().let { null }
        override fun deleteFile(filePath: String) {
            touched()
            real.deleteFile(filePath)
        }

        private companion object {
            const val NEVER_PURGED = "everything: no purge has run"
        }
    }

    /** The first segment of every logical path the inference stores use under the root. */
    private const val LOGICAL_PREFIX = "inference"
    private const val ROOT_DIRECTORY = "photos-private"
    private const val RELINQUISH_BUDGET_MS = 2_000L
}

/**
 * The inference gate over [custody] that first waits for a stopped graph's writer (B.16 (246)).
 * [custody] is public so a wiring test can see what it gates.
 */
class WriterBarrierGate(val custody: InferenceStoreCustody) : InferenceStoreGate {
    override suspend fun open(fromPass: Boolean): Boolean {
        OwnedInferenceStores.awaitPriorWriter()
        return custody.open(fromPass)
    }
}
