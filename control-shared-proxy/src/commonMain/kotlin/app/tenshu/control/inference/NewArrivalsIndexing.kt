package app.tenshu.control.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isTrashedPath
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.inference.proxy.ArrivalQueue
import kotlin.time.TimeSource

/**
 * New arrivals are indexed without a press (`inference-service.md` IN-D6a) — the Android and desktop
 * half. Three pieces:
 *
 *  1. [NewArrivalsObserver] notices that a photograph **arrived** — created here, synced in from
 *     another device, or moved/renamed to a path no pass has seen — from the file service's own
 *     modification rows, as they are saved, and puts its PATH on the [ArrivalQueue]. ⚠⚠ Never a walk.
 *  2. An [ArrivalIndexScheduler] debounces the arrivals into **one** batch about
 *     [NEW_ARRIVALS_DEBOUNCE_MS] after the last of them, and never later than
 *     [NEW_ARRIVALS_MAX_LATENCY_MS] after the first: Android's WorkManager one-time job
 *     ([ThrottledArrivalScheduler] → `NewArrivalsIndexWorker`), desktop's [InProcessArrivalScheduler].
 *  3. The batch indexes the queued paths and nothing else — `runArrivalsBatch` over
 *     `MediaIndexRunner.runPathsBatch`, the ONE runner, capped and never chained.
 */

/** About two minutes after the last arrival (IN-D6a). */
const val NEW_ARRIVALS_DEBOUNCE_MS: Long = 2L * 60 * 1000

/**
 * ⚠⚠ The debounce's ceiling: a batch is never pushed back past this long after the first arrival it
 * covers. Without it a multi-hour sync — an arrival every few seconds — restarts the two minutes
 * for hours and its photographs wait for the end.
 */
const val NEW_ARRIVALS_MAX_LATENCY_MS: Long = 10L * 60 * 1000

/**
 * How often Android looks again during a burst. ⚠ Every arrival re-arming the job would be a
 * WorkManager database write per photograph of a 5 000-photo sync; this is one per window.
 */
const val NEW_ARRIVALS_THROTTLE_MS: Long = 15_000L

/** Where a noticed arrival's path goes. ⚠ Must not block: it is called on the saving thread. */
fun interface ArrivalIndexScheduler {
    fun arrived(filePath: String)
}

/** Registers [NewArrivalsObserver] on this session. Control's platform init calls it. */
fun ExecutionContext.installNewArrivalsIndexing(scheduler: ArrivalIndexScheduler) {
    addDataObserver(NewArrivalsObserver(this, scheduler))
}

/**
 * Tells [scheduler] the path of each photograph that may have arrived — a [CreatedFileModification],
 * [MovedFileModification] or [RenamedFileModification] row saved for an image path outside the
 * trash, **whatever its origin**: a local upload and a row ingested from another device alike.
 *
 * ⚠⚠ Why the modification rows, and not the tree: photographs live in any folder, so no one array
 * holds them, and watching every folder's array is the walk this must not be. Every file write op
 * stamps a modification row and a peer's row syncs in with the file.
 * ⚠ Moved and renamed count: the stores are keyed by path. A content change at the same path does
 * not — the stores would not re-index it.
 * ⚠⚠ Two gaps, both known: a file op writes these rows only when it has a device id (production
 * always passes one); and a shared realm's `SharedFile` content writes none, so a photograph
 * arriving in a shared folder is not seen — the scheduled pass is its route.
 * ⚠ Cheap by construction: a type-id prefix test rejects every non-modification save before any
 * schema is looked up, and a bare composite carries its own schema.
 */
class NewArrivalsObserver(
    private val executionContext: ExecutionContext,
    private val scheduler: ArrivalIndexScheduler,
) : DataObserver {

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val schema = when (data) {
            is Entity<*> -> {
                val typeId = data.type.get()
                if (!typeId.startsWith(FileModification.ID)) return
                executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema
            }
            is Composite -> data.schema.takeIf { it.id.get().startsWith(FileModification.ID) }
            else -> null
        } ?: return
        if (ARRIVING_KINDS.none { schema.isSubtypeOf(it) }) return
        val path = pathOf(data) ?: return
        if (!isArrivingImagePath(path)) return
        scheduler.arrived(path)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit

    private fun pathOf(data: Data): String? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TString>(FileModification.Field.FILE_ID)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(FileModification.Field.FILE_ID).get() }.getOrNull()
        else -> null
    }

    private companion object {
        val ARRIVING_KINDS = listOf(CreatedFileModification.ID, MovedFileModification.ID, RenamedFileModification.ID)
    }
}

/** An image the index pass would walk to: not in the trash, and an image by its name. */
fun isArrivingImagePath(path: String): Boolean =
    !isTrashedPath(path) && fileCategoryForName(path.substringAfterLast('/')) == FileCategory.Image

/** Where Android's one-time job stands, as far as arming it goes. */
enum class ArrivalJob {
    /** None, or the last one finished. */
    NONE,

    /** Armed and waiting — its delay, or its battery constraint. */
    WAITING,

    /** Indexing now. ⚠⚠ Never replaced: a `REPLACE` would cancel the batch. */
    RUNNING,
}

/**
 * ⚠ The ceiling is per PROCESS: the first-arm time is not persisted, so a Control restarted mid-stream
 * starts a fresh window. ⚠ And a re-arm (`REPLACE`) of a job that is waiting on a retry resets its
 * WorkManager attempt count — the retry bound is per armed job, not per batch.
 *
 * Android's scheduler: queues each arrival's path, and (re-)arms the debounced job ([arm] is
 * `REPLACE` + a two-minute delay) at most once per [throttleMs]:
 *
 * - **No job, or a finished one** → arm it; the first arrival arms it at once.
 * - **A waiting job** → push it back two minutes — ⚠⚠ but only until [maxLatencyMs] after it was
 *   first armed; from then on it keeps its place, so a stream of arrivals cannot starve it.
 * - ⚠⚠ **A running job** → left alone, and looked at again after the window: an arrival must never
 *   cancel a batch. Its path waits on the queue for the next one.
 *
 * ⚠ [queue] is flushed before every arm, so the job a cold-woken Control runs finds the paths on disk.
 */
class ThrottledArrivalScheduler(
    scope: CoroutineScope,
    private val queue: ArrivalQueue,
    private val job: suspend () -> ArrivalJob,
    private val throttleMs: Long = NEW_ARRIVALS_THROTTLE_MS,
    private val maxLatencyMs: Long = NEW_ARRIVALS_MAX_LATENCY_MS,
    /** The delay [arm] gives the job — what a push-back adds, so the ceiling subtracts it. */
    private val debounceMs: Long = NEW_ARRIVALS_DEBOUNCE_MS,
    private val now: () -> Long = monotonicMs(),
    private val arm: suspend () -> Unit,
) : ArrivalIndexScheduler {

    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var firstArmedAt: Long? = null

    init {
        scope.launch {
            for (signal in signals) {
                try {
                    queue.flush()
                    when (job()) {
                        ArrivalJob.RUNNING -> signals.trySend(Unit)
                        ArrivalJob.NONE -> {
                            firstArmedAt = now()
                            arm()
                        }
                        ArrivalJob.WAITING -> {
                            val first = firstArmedAt ?: now().also { firstArmedAt = it }
                            // ⚠⚠ A push-back makes the job due [debounceMs] from now, so the last
                            // one allowed is the one that is still due by first + max.
                            if (now() + debounceMs <= first + maxLatencyMs) arm()
                        }
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // ⚠ A failed arm must not end the loop: the next arrival tries again.
                    println("NewArrivals -> arming failed: $failure")
                }
                delay(throttleMs)
            }
        }
    }

    override fun arrived(filePath: String) {
        queue.add(filePath)
        signals.trySend(Unit)
    }
}

/**
 * Desktop's scheduler — no WorkManager there, and Control is a long-lived process: [run] fires
 * [debounceMs] after the last arrival, or [maxLatencyMs] after the first, whichever is sooner.
 *
 * ⚠ Batches run one after another, never two at once, and an arrival never cancels one: it waits on
 * the queue for the batch after, which is a new arrival's batch — not a chain.
 */
class InProcessArrivalScheduler(
    scope: CoroutineScope,
    private val queue: ArrivalQueue,
    debounceMs: Long = NEW_ARRIVALS_DEBOUNCE_MS,
    maxLatencyMs: Long = NEW_ARRIVALS_MAX_LATENCY_MS,
    now: () -> Long = monotonicMs(),
    private val run: suspend () -> Unit,
) : ArrivalIndexScheduler {

    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (first in signals) {
                val deadline = now() + maxLatencyMs
                while (true) {
                    val wait = minOf(debounceMs, deadline - now())
                    if (wait <= 0) break
                    withTimeoutOrNull(wait) { signals.receive() } ?: break
                }
                try {
                    queue.flush()
                    run()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // ⚠ Named, and the loop lives on: one failed batch must not end the watch.
                    println("NewArrivals -> batch failed: $failure")
                }
            }
        }
    }

    override fun arrived(filePath: String) {
        queue.add(filePath)
        signals.trySend(Unit)
    }
}

private fun monotonicMs(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
