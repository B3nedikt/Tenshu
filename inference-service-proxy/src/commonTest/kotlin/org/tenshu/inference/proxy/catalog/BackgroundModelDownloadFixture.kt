package org.tenshu.inference.proxy.catalog

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.InferenceCapability

/**
 * A disk for the background downloader's cases: an [InMemoryFileDatabase] that can be listed,
 * can run out of space, and can fail the way a phone's does.
 */
internal class DownloadDisk(private val disk: InMemoryFileDatabase = InMemoryFileDatabase()) : FileDatabase {

    /** How a read of one path fails. */
    enum class ReadFault {
        /** The read throws. */
        THROWS,

        /** `null` for a file a stat still finds: what `IosFileDatabase` answers for an unreadable one. */
        NULL_THOUGH_PRESENT,
    }

    val paths = linkedSetOf<String>()
    val deleted = mutableListOf<String>()
    var free: Long? = Long.MAX_VALUE
    val readFaults = mutableMapOf<String, ReadFault>()
    val failingWrites = mutableSetOf<String>()

    /** What happens while a path is being read: a teardown under a hash, in one case. */
    var whileReading: (String) -> Unit = {}

    fun delivered(dir: String): Set<String> = paths
        .filter { it.startsWith("$dir/") && it.endsWith(BackgroundModelDownloader.DELIVERED_SUFFIX) }
        .mapTo(mutableSetOf()) { it.removePrefix("$dir/").removeSuffix(BackgroundModelDownloader.DELIVERED_SUFFIX) }

    /**
     * The process is dead (§14.5): from here nothing it does reaches the disk, so the disk is
     * what a kill left. ⚠ Set at the moment of the kill, cleared for the next process.
     */
    var dead: Boolean = false

    override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
        if (dead) return
        if (filePath in failingWrites) throw IllegalStateException("I/O error writing $filePath")
        disk.writeRawByteArray(filePath, bytes)
        paths += filePath
    }

    override fun writeByteArray(filePath: String, bytes: TByteArray) = writeRawByteArray(filePath, bytes.get())

    override fun readBytes(filePath: String): TByteArray? = when (readFaults[filePath]) {
        ReadFault.THROWS -> throw IllegalStateException("I/O error reading $filePath")
        ReadFault.NULL_THOUGH_PRESENT -> null
        null -> disk.readBytes(filePath).also { whileReading(filePath) }
    }

    override fun fileSize(filePath: String): Long? = disk.fileSize(filePath)

    override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? = when (readFaults[filePath]) {
        ReadFault.THROWS -> throw IllegalStateException("I/O error reading $filePath")
        ReadFault.NULL_THOUGH_PRESENT -> null
        null -> disk.readBytesRange(filePath, offset, length).also { whileReading(filePath) }
    }

    override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
        if (dead) return
        disk.writeBytesRange(filePath, offset, bytes)
        paths += filePath
    }

    override fun moveFile(fromPath: String, toPath: String) {
        if (dead || disk.fileSize(fromPath) == null) return
        disk.moveFile(fromPath, toPath)
        paths -= fromPath
        paths += toPath
    }

    /** Paths whose delete fails with an I/O error. */
    val failingDeletes = mutableSetOf<String>()

    override fun deleteFile(filePath: String) {
        if (dead) return
        if (filePath in failingDeletes) throw IllegalStateException("I/O error deleting $filePath")
        if (disk.fileSize(filePath) != null) deleted += filePath
        disk.deleteFile(filePath)
        paths -= filePath
    }

    /** What happens while the volume is asked for its free space: a teardown, in one case. */
    var whileAskedForSpace: () -> Unit = {}

    override fun availableSpaceBytes(): Long? = free.also { whileAskedForSpace() }
}

/**
 * The transport as the downloader meets it, **in the shape the real one has**: a session that
 * holds tasks, the real [ModelTransferLedger] deciding who holds which ref, and the calls
 * `photos-ios/Sources/ModelDownload.swift` makes on that ledger, in its order.
 * ⚠ It outlives a downloader on purpose, as `nsurlsessiond` outlives the app; the ledger does
 * not, and [relaunched] is a new process's.
 *
 * What it models, because each is where the product broke:
 * - a start is two moments, the asking and the session's answer ([holdStarts], [answerStarts]);
 * - a transfer ends in the session first, and is reported later, through
 *   [BackgroundModelDownloader.transferFinished], which queues it ([end], [Ended.report]): a
 *   report can be held back and arrive behind a cancel, a resume or another download;
 * - what the downloader is told is live is the ledger's answer, not the task list;
 * - a task of an earlier process is in the session and unknown to this process's ledger until
 *   the session lists it or it reports ([live]`[ref] = …`).
 *
 * It can answer what a real network can: a whole body, a short one, an error page, an error
 * with no response, a stall, a phone that is on cellular only; and a transport that cannot say
 * what it holds.
 *
 * ⚠ What it is not: a `URLSession`. That the Swift makes these calls is only compiled.
 */
internal class FakeTransport(private val disk: DownloadDisk, private val dir: String) : ModelTransferPort {

    sealed interface Served {
        /** A response with [status] and [bytes] as its body. */
        class Body(val bytes: ByteArray, val status: Int = 200) : Served

        /** No bytes move and nothing is reported. */
        data object Stall : Served

        /** The transfer ends with no response at all. */
        class Error(val message: String) : Served
    }

    enum class Network { WIFI, CELLULAR }

    /** This process's. ⚠ [relaunched] replaces it: a new process knows of no task. */
    var ledger = ModelTransferLedger()
        private set

    class Task(val id: Long, val request: ModelTransferRequest)

    /** The session's tasks that are running or waiting. */
    private val tasks = LinkedHashMap<Long, Task>()
    private var nextTask = 1L

    private fun listing(): List<ModelSessionTransfer> = tasks.values.map { ModelSessionTransfer(it.id, it.request.ref) }

    /** Every request a task was made for, in order. */
    val started = mutableListOf<ModelTransferRequest>()

    /** Every ref the downloader asked to have started, whether or not a task was made. */
    val asked = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val served = mutableMapOf<String, Served>()
    var network: Network = Network.WIFI

    /** `false`: the transport does not answer what it holds. */
    var answersWhatIsLive: Boolean = true

    /** What happens while the transport is being asked what it holds: a teardown, in some cases. */
    var whileAsked: () -> Unit = {}

    /** A new process: the session and its tasks stay, the ledger starts empty. */
    fun relaunched() {
        ledger = ModelTransferLedger()
        pendingStarts.clear()
    }

    /**
     * The session's task list, as a case reads and bends it. ⚠ Not what the downloader is told
     * is live: that is the ledger's answer ([liveRefs]).
     */
    inner class Live {
        val keys: Set<String> get() = tasks.values.mapTo(linkedSetOf()) { it.request.ref }
        val size: Int get() = tasks.size

        operator fun get(ref: String): ModelTransferRequest? = tasks.values.firstOrNull { it.request.ref == ref }?.request

        /** A task of an EARLIER process: in the session, and unknown to this process's ledger. */
        operator fun set(ref: String, request: ModelTransferRequest) {
            val id: Long = nextTask++
            tasks[id] = Task(id, request)
        }

        /** The system ended the task and reported a cancel (the user force-quit the app). */
        fun remove(ref: String) {
            tasks.values.filter { it.request.ref == ref }.forEach { task ->
                tasks.remove(task.id)
                ledger.completed(task.id, ref, cancelled = true)
            }
        }

        fun clear() = keys.toList().forEach(::remove)
    }

    val live = Live()

    /** What happens after a delivery was let through and before its file is moved into place. */
    var whileDelivering: () -> Unit = {}

    /** What happens after the file was moved and before the ledger is asked again. */
    var afterTheMove: () -> Unit = {}

    /** `true`: the session does not answer a start until [answerStarts]. */
    var holdStarts: Boolean = false
    private val pendingStarts = mutableListOf<Pair<ModelTransferRequest, Long>>()

    override fun start(request: ModelTransferRequest) {
        asked += request.ref
        // ⚠ Numbered when it is asked for, as the Swift does; decided when the session answers.
        val at: Long = ledger.startAsked(request.ref)
        if (holdStarts) pendingStarts += request to at else answer(request, at)
    }

    /** The session answers every start it was asked for and had not answered. */
    fun answerStarts() {
        val waiting = pendingStarts.toList()
        pendingStarts.clear()
        waiting.forEach { (request, at) -> answer(request, at) }
    }

    private fun answer(request: ModelTransferRequest, at: Long) {
        if (!ledger.admit(request.ref, at, listing())) return
        val id: Long = nextTask++
        // A cancel asked for while the task was being made: it never runs.
        if (!ledger.bind(request.ref, at, id)) {
            ledger.completed(id, request.ref, cancelled = true)
            return
        }
        tasks[id] = Task(id, request)
        started += request
    }

    /** Completed by a case to let the transport answer; `null` answers at once. */
    var answerWhen: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** How many askers wait for [answerWhen]. */
    var unanswered: Int = 0
        private set

    override suspend fun liveRefs(): Set<String>? {
        answerWhen?.let { gate ->
            unanswered++
            gate.await()
            unanswered--
        }
        val answer: Set<String>? = if (answersWhatIsLive) ledger.liveRefs(listing()).toSet() else null
        whileAsked()
        return answer
    }

    override fun cancel(ref: String) {
        cancelled += ref
        val cancel = ledger.cancelAsked(ref)
        (cancel.taskIds + ledger.cancelFromSession(ref, cancel.asked, listing())).distinct().forEach { id ->
            // As the session does it: the task completes with `NSURLErrorCancelled`.
            if (tasks.remove(id) != null) ledger.completed(id, ref, cancelled = true)
        }
    }

    /**
     * A transfer the session is done with and that the ledger says is to be reported: what
     * `didCompleteWithError` hands to Kotlin. ⚠ Until [report] is called the report has not
     * been MADE, which is the delegate queue being slow; once made it is queued by the
     * downloader behind its lock.
     */
    inner class Ended(val ref: String, val status: Int, val message: String?, private val handle: ModelTransferReport) {
        /** Makes the report, as the transport does: it returns at once. Join the answer to wait for it. */
        fun report(downloader: BackgroundModelDownloader): kotlinx.coroutines.Job =
            downloader.transferFinished(ref, status, message, handle)

        /** Nobody took the report (no runtime, or the app is in the background): the ref is freed. */
        fun drop() = handle.settled()
    }

    /**
     * A task the session is done with and has not called back about yet: it is in no listing
     * any more, and neither its file nor its completion has reached this process.
     */
    inner class Finished(private val task: Task, private val status: Int, private val message: String?, private val body: ByteArray?) {
        /**
         * The delegate callbacks, in the session's order: the file, if the ledger says the task
         * speaks for the ref, then the completion, which is the answer when it is to be reported.
         */
        fun callBack(): Ended? {
            val ref: String = task.request.ref
            // ⚠ As the Swift does it: asked before the move and again after it.
            if (body != null && ledger.beginDelivery(task.id, ref)) {
                whileDelivering()
                disk.writeRawByteArray(BackgroundModelDownloader.deliveredPath(dir, ref), body)
                afterTheMove()
                if (!ledger.endDelivery(task.id, ref)) disk.deleteFile(BackgroundModelDownloader.deliveredPath(dir, ref))
            }
            return ledger.completed(task.id, ref, cancelled = false)?.let { Ended(ref, status, message, it) }
        }
    }

    /** The network is done with the task that carries [ref]. `null`: the transfer waits, or there is none. */
    fun finish(ref: String): Finished? {
        val task: Task = tasks.values.firstOrNull { it.request.ref == ref } ?: return null
        // ⚠ D6, as the transport enforces it: a request that may not use cellular waits there.
        if (!task.request.allowsCellular && network == Network.CELLULAR) return null
        val finished: Finished = when (val answer: Served = served[task.request.url] ?: error("nothing served at ${task.request.url}")) {
            Served.Stall -> return null
            is Served.Error -> Finished(task, -1, answer.message, null)
            // Only a 2xx response is delivered as a file.
            is Served.Body -> Finished(task, answer.status, null, answer.bytes.takeIf { answer.status in 200..299 })
        }
        tasks.remove(task.id)
        return finished
    }

    /** [finish] and its callbacks at once: the usual case. */
    fun end(ref: String): Ended? = finish(ref)?.callBack()

    /** Lets every transfer that can finish do so, and reports each to [downloader], one at a time. */
    suspend fun run(downloader: BackgroundModelDownloader) {
        live.keys.toList().forEach { ref -> end(ref)?.report(downloader)?.join() }
    }

    /** The same, with nobody listening: what happens while the app is dead, or in the background. */
    fun runUnheard() {
        live.keys.toList().forEach { ref -> end(ref)?.drop() }
    }
}

/** A wrapper that records when the commit ran, and what the store held around it. */
internal class RecordingChanges(private val installedNow: () -> Int) : ModelStoreChanges {
    val installedOnEntry = mutableListOf<Int>()
    val installedOnExit = mutableListOf<Int>()

    /** What happens while the wrapper waits to run the change: a teardown, in one case. */
    var whileWaiting: () -> Unit = {}

    /**
     * Completed by a case to let the change run: the real wrapper (`AiReadiness.storeChanging`)
     * **suspends**, behind a pass being cancelled and sessions being closed. `null` runs at once.
     */
    var runWhen: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** How many changes wait for [runWhen]. */
    var waiting: Int = 0
        private set

    /** The model each change said it was about. */
    val changedFor = mutableListOf<String>()

    override suspend fun <T> changing(modelId: String, change: suspend () -> T): T {
        changedFor += modelId
        return changing(change)
    }

    override suspend fun <T> changing(change: suspend () -> T): T {
        installedOnEntry += installedNow()
        runWhen?.let { gate ->
            waiting++
            gate.await()
            waiting--
        }
        whileWaiting()
        return change().also { installedOnExit += installedNow() }
    }
}

internal object DownloadFixtures {
    const val DIR: String = BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR
    const val JOURNAL: String = "$DIR/${BackgroundModelDownloader.JOURNAL_FILE}"
    const val BASE: String = "https://models.example"
    const val MODEL: String = "siglip"

    val visionBytes = ByteArray(4096) { (it % 251).toByte() }
    val textBytes = ByteArray(8192) { (it % 241).toByte() }
    val vocabularyBytes = ByteArray(100) { (it % 7).toByte() }

    @OptIn(ExperimentalStdlibApi::class)
    fun sha256(bytes: ByteArray): String {
        val digest = Sha256Digest()
        digest.update(bytes, bytes.size)
        return digest.digest().toHexString()
    }

    fun artifact(role: String, path: String, bytes: ByteArray) =
        ModelArtifactData(role, path, sha256(bytes), bytes.size.toLong())

    fun entry(
        vision: ByteArray = visionBytes,
        text: ByteArray = textBytes,
        vocabulary: ByteArray = vocabularyBytes,
        version: Int = 1,
    ) = ModelEntryData(
        modelId = MODEL,
        name = "SigLIP",
        modelVersion = version,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "coreml",
        artifactVersion = 1,
        artifacts = listOf(
            artifact(ModelArtifactRole.VISION, "siglip/$version/vision.tar", vision),
            artifact(ModelArtifactRole.TEXT, "siglip/$version/text.tar", text),
            artifact(ModelArtifactRole.VOCABULARY, "siglip/$version/tokenizer.bin", vocabulary),
        ),
        licence = "Apache-2.0",
    )

    fun catalogOf(entry: () -> ModelEntryData?) = ModelCatalogSource {
        entry()?.let { ModelCatalogData(schemaVersion = 1, buildDate = "2026-10-11", models = listOf(it)) }
    }

    fun storeOver(disk: FileDatabase): DefaultModelStore = DefaultModelStore(
        storageDelegate = FileModelIndexStorage(disk),
        fileDatabase = disk,
        modelsDir = DEFAULT_MODELS_DIR,
        digestOf = ::sha256,
        ownership = ModelStoreOwnership.SoleProcess,
    )

    fun key(entry: ModelEntryData) =
        ModelDownloadKey(
            entry.modelId, entry.modelVersion, entry.labelSetVersion, entry.licence,
            entry.artifacts.map { ModelDownloadKey.Artifact(it.role, it.sha256, it.byteSize) },
        )

    fun ref(entry: ModelEntryData, role: String, allowsCellular: Boolean = false): String =
        BackgroundModelDownloader.refOf(entry.modelId, entry.artifact(role)!!, allowsCellular)

    fun url(entry: ModelEntryData, role: String): String = "$BASE/${entry.artifact(role)!!.path}"
}

/**
 * One process's view of a phone: a fresh store and a fresh downloader over a disk and a transport
 * that were there before it and will be there after it.
 */
internal class DownloadProcess(
    val disk: DownloadDisk,
    val transport: FakeTransport,
    scope: CoroutineScope,
    entry: () -> ModelEntryData? = { DownloadFixtures.entry() },
    transportInstalled: () -> Boolean = { true },
    val retired: () -> Boolean = { false },
    /**
     * ⚠ The product's own budget by default (the disk of these cases is unbounded unless a case
     * says otherwise). It was `0` once, and no case that did not ask for it met the check.
     */
    need: (Long, Long) -> Long = CoreMlDiskBudget::additionalNeed,
    catalog: ModelCatalogSource = DownloadFixtures.catalogOf(entry),
    /** `false`: another graph of the SAME process (its ledger stays). */
    newProcess: Boolean = true,
    /** The process's lock for download steps: pass the first graph's to a second graph of the same process. */
    val steps: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
) {
    init {
        if (newProcess) transport.relaunched()
    }

    val store: DefaultModelStore = DownloadFixtures.storeOver(disk)

    /** The model each hook was told it is about, in order. */
    val changedModels = mutableListOf<String>()

    /** What a removal runs before it removes: throws, in one case. */
    var beforeRemoval: suspend () -> Unit = {}

    /** Each time the downloader said the store changed, and whether its step still held the lock. */
    val storeChanges = mutableListOf<String>()
    private val downloaderSteps = steps

    /** Holds a cancel or a removal between being counted and reaching the lock: a preemption. */
    var betweenAnEndingAndItsStep: suspend () -> Unit = {}
    val progress = ModelDownloadProgressHolder()
    val changes = RecordingChanges { store.installed().size }
    val downloader = BackgroundModelDownloader(
        catalogSource = catalog,
        store = store,
        files = disk,
        progress = progress,
        baseUrl = DownloadFixtures.BASE,
        transport = { transport.takeIf { transportInstalled() } },
        changes = changes,
        additionalDiskNeed = need,
        retired = { retired() },
        deliveredRefs = { disk.delivered(DownloadFixtures.DIR) },
        scope = scope,
        steps = steps,
        betweenAnEndingAndItsStep = { betweenAnEndingAndItsStep() },
        storeChanged = { changed ->
            changedModels += changed
            storeChanges += if (downloaderSteps.isLocked) "under the lock" else "after the step"
        },
        beforeRemoval = { removed ->
            changedModels += removed
            beforeRemoval()
        },
        afterRemoval = { removed ->
            changedModels += removed
            storeChanges += if (downloaderSteps.isLocked) "removal, in its step" else "removal, after its step"
        },
        // Small, so every artifact is hashed over several reads.
        chunkBytes = 1024,
    )
}
