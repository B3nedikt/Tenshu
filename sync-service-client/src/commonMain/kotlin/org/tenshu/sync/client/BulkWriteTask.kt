package org.tenshu.sync.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.sync.GenericTaskSpec

/**
 * Run [block] as ONE registered bulk task, so every write inside joins the task's sync lane and
 * rides the relay as `DeltaBatchPayload` blocks (~N/500) instead of one `SyncBlock` per row
 * (`core-background-processing.md` §10.3, TS-3…TS-5).
 *
 * ## Why this exists as a shared function
 *
 * It is the **fourth** copy of the same eleven lines. TS-4 wrote it inside Fit
 * (`FitProjectionRepository.withImportTask`), TS-5 wrote it again inside Drive
 * (`TenshuFilesRepository.withBulkUploadTask`), and `sync-device-catch-up.md` C4's audit found two
 * more bulk writers wanting it. The lifecycle is small but not obvious — a cancelled scope must
 * still reach the proxy, and a failed registration must degrade rather than throw — so a fourth
 * hand-written copy is how one of those subtleties gets dropped.
 *
 * ## The two things it gets right that a naive wrapper does not
 *
 * ⚠ **Degradation is silent and total.** If the task cannot be registered — an older Control, a
 * runtime with no sync client module, a proxy that is simply not reachable — [block] still runs,
 * with `task = null`, and its writes take the ordinary per-save path. Batching is an optimisation;
 * losing it must never cost a write. Every existing test and mixed-version pairing keeps working.
 *
 * ⚠⚠ **The terminal transition rides `NonCancellable` on cancellation.** By the time a
 * `CancellationException` reaches here the calling scope is already being torn down, so any further
 * suspension in it throws immediately — including the `cancel()` that tells the proxy to close the
 * lane. Without the `NonCancellable` the lane would stay open until its lease expired, and every
 * delta staged in it would wait for journal replay.
 *
 * [block] receives the handle (or `null` when degraded) so a long loop can heartbeat: the task's
 * lease is what keeps the lane alive, and a lane whose lease expires silently reverts to per-save
 * writes. Callers that write steadily can renew every N records; callers whose single unit of work
 * can take minutes (Drive's per-file upload) must renew per unit.
 */
suspend fun <T> TenshuClient.withBulkWriteTask(
    kind: String,
    block: suspend (task: BackgroundTaskHandle<*>?) -> T,
): T {
    val task = runCatchingCancellable {
        taskService.register(GenericTaskSpec.create(environment.tContext, kind))
    }.getOrNull() ?: return block(null)

    return try {
        val result = withTask(task) { block(task) }
        bestEffort("BulkWriteTask -> completing the task") { task.complete() }
        result
    } catch (e: CancellationException) {
        withContext(NonCancellable) { runCatching { task.cancel() } }
        throw e
    } catch (t: Throwable) {
        bestEffort("BulkWriteTask -> failing the task") { task.fail() }
        throw t
    }
}
