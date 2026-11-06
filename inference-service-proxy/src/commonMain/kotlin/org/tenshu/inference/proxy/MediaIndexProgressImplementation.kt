package org.tenshu.inference.proxy

import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger
import org.tenshu.inference.IndexProgress
import org.tenshu.inference.MediaIndexProgress

/**
 * Serves the [IndexProgress] **stream** op (IN-D6, 1e-3): pushes a [MediaIndexProgress] element to
 * the subscribing app on every tick of the device-local [IndexProgressHolder], then a final
 * `indexing = false` tick and a terminal `StreamElement.End` when the pass ends.
 *
 * ⭐ [ModelDownloadProgressImplementation]'s twin with **one deliberate difference: no registration
 * asymmetry, because there is nothing to disambiguate.** A download registers its holder entry
 * *after* a catalog fetch, so an absent entry there means either "late" or "never" and the
 * implementation needs `isDownloading` to tell them apart. `MediaIndexer` calls `start()` as its
 * first act and is the holder's only writer, so an absent entry here means exactly **"no pass is
 * running"** — a fact, reported as a tick, not an ambiguity to wait out.
 *
 * ⚠⚠ It therefore also never hangs: an idle device gets one tick and a completion rather than an
 * open flow behind a bar that never moves (the region twin's R3 regression), and a subscriber that
 * arrives mid-pass is replayed the current count by the `StateFlow` immediately.
 *
 * ⚠ The consequence, stated rather than hidden: **a pass that starts after you subscribed is not
 * reported.** Staying open for it would leak — `EndStream` tears down `StreamRegistry` listeners,
 * not an implementation's own launched job, so every subscribe would strand a collector sending to
 * a dead streamId for the life of the session — and it would be waiting for something that cannot
 * arrive while anyone is watching: the pass runs only when the device is **idle** and charging, and
 * a device with this screen open is not idle. A subscriber that must see later passes asks again;
 * Control's Models screen, which shares this process, reads the holder directly instead
 * (`IndexProgressSource`) and needs neither.
 *
 * Lifecycle: the collector runs on [ExecutionContext.sessionScope], so it dies with the session. A
 * client that navigates away mid-pass leaves it running until that pass ends — bounded, and its
 * sends land on a streamId nobody is listening to. Same trade as the download twin.
 */
internal class MediaIndexProgressImplementation(
    private val holder: IndexProgressHolder,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val streamId: Long = params.read<TInteger>(IndexProgress.Params.STREAM_ID).get()

        val context = executionContext.context
        val connection = executionContext.clientConnection

        executionContext.sessionScope.launch {
            holder.progress
                // Emit, then stop once the value was absent. A `StateFlow` replays its current
                // value, so this is one pass: idle at subscribe ⇒ exactly one terminal tick.
                .transformWhile { snapshot ->
                    emit(snapshot)
                    snapshot != null
                }
                .collect { snapshot ->
                    val tick = snapshot
                        ?.let { MediaIndexProgress.create(context, true, it.done, it.total) }
                        ?: MediaIndexProgress.idle(context)
                    connection.send(StreamElementValueDto(streamId, tick))
                    // ⚠ The idle tick goes FIRST, so a UI watching a pass to its end learns it
                    // finished rather than inferring it from a completed flow.
                    if (snapshot == null) connection.send(endElement(context, streamId))
                }
        }

        return null as T
    }
}
