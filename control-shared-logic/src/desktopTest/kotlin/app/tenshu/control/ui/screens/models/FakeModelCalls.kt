package app.tenshu.control.ui.screens.models

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/**
 * The proxy, as the ledger calls it.
 *
 * ⚠⚠ It moves only when a case moves it. A download ends when the case ends it, a stream ticks
 * when the case ticks it, and a read answers what the case last set: nothing lands because it
 * was asked for. ⚠ And it can do what the product does on a bad day: throw from a read, never
 * answer one, fail or never end a stream, and hold a removal open.
 */
internal class FakeModelCalls(
    var installed: ModelRead = ModelRead.Got(emptyList()),
    var available: ModelRead = ModelRead.Got(emptyList()),
) : ModelCalls {
    var reads = 0
    var catalogReads = 0
    val downloads = mutableListOf<String>()
    val deletions = mutableListOf<String>()
    var deleteOutcome = ModelDeleteOutcome.Removed

    /** When set, the next read of that kind throws it. */
    var installedThrows: Exception? = null
    var availableThrows: Exception? = null
    var deleteThrows: Exception? = null

    /** While set, `available()` does not return. */
    var availableHangs = false

    /** When set, the catalog read waits for it. */
    var availableGate: CompletableDeferred<Unit>? = null

    /** When set, a removal waits for it. */
    var deleteGate: CompletableDeferred<Unit>? = null

    /** The calls that have not ended, by model. A case ends one by completing it. */
    val running = mutableMapOf<String, CompletableDeferred<ModelDownloadOutcome>>()

    /** The proxy's stream per model: absent ⇒ nothing downloads and the stream ends at once. */
    private val streams = mutableMapOf<String, MutableStateFlow<ModelBytes?>>()
    private val ended = ModelBytes("", -1, -1)
    var subscribes = 0

    /** When set, every stream fails with it on subscription. */
    var streamThrows: Exception? = null

    /** A download of [modelId] is running in the proxy, with no byte reported yet. */
    fun begin(modelId: String) { streams[modelId] = MutableStateFlow(null) }
    fun tick(modelId: String, written: Long, total: Long = 383) { streams.getValue(modelId).value = ModelBytes(modelId, written, total) }

    /**
     * When set, every subscription is handed THAT model's stream, as the real client hands a
     * second subscriber the first one's (it keys a stream by the call site, not by the model):
     * its ticks, stamped with its id, and its end.
     */
    var everySubscriptionGets: String? = null
    fun endStream(modelId: String) { streams.remove(modelId)?.value = ended }

    override suspend fun installed(): ModelRead {
        reads++
        // ⚠ A real read suspends: without this no two requests could ever overlap here.
        yield()
        installedThrows?.let { throw it }
        return installed
    }

    override suspend fun available(): ModelRead {
        catalogReads++
        if (availableHangs) awaitCancellation()
        availableGate?.await()
        availableThrows?.let { throw it }
        return available
    }

    /** When set, a download ends by itself after a moment, with this. Only for the case on real threads. */
    @Volatile var endsByItselfWith: ModelDownloadOutcome? = null
    private val downloadsNow = java.util.concurrent.atomic.AtomicInteger()
    val mostDownloadsAtOnce = java.util.concurrent.atomic.AtomicInteger()

    /** When set, a download ends inside the call, with this: before its caller can do anything else. */
    var endsAtOnceWith: ModelDownloadOutcome? = null

    /** A download and a removal were seen running at the same moment (the real-threads cases). */
    @Volatile var removedUnderADownload = false
    private val removalsNow = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun download(modelId: String): ModelDownloadOutcome {
        endsAtOnceWith?.let { downloads += modelId; return it }
        endsByItselfWith?.let { outcome ->
            mostDownloadsAtOnce.accumulateAndGet(downloadsNow.incrementAndGet(), ::maxOf)
            if (removalsNow.get() > 0) removedUnderADownload = true
            try { kotlinx.coroutines.delay(5) } finally { downloadsNow.decrementAndGet() }
            return outcome
        }
        downloads += modelId
        val call = CompletableDeferred<ModelDownloadOutcome>()
        running[modelId] = call
        return try { call.await() } finally { running.remove(modelId) }
    }

    override fun progress(modelId: String): Flow<ModelBytes> = flow {
        subscribes++
        streamThrows?.let { throw it }
        val stream = streams[everySubscriptionGets ?: modelId] ?: return@flow
        stream.takeWhile { it !== ended }.filterNotNull().collect { emit(it) }
    }

    override suspend fun delete(modelId: String): ModelDeleteOutcome {
        if (endsByItselfWith != null) {
            removalsNow.incrementAndGet()
            if (downloadsNow.get() > 0) removedUnderADownload = true
            try { kotlinx.coroutines.delay(5) } finally { removalsNow.decrementAndGet() }
            return deleteOutcome
        }
        deletions += modelId
        deleteGate?.await()
        deleteThrows?.let { throw it }
        return deleteOutcome
    }
}
