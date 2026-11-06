package org.tenshu.inference.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.TString
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.number.TInteger
import org.tenshu.inference.DownloadProgress
import org.tenshu.inference.ModelDownloadProgress
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressHolder
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressSnapshot
import org.tenshu.inference.proxy.catalog.ModelDownloader

/**
 * Serves the [DownloadProgress] **stream** op (IN-2c, 1c-3): pushes a [ModelDownloadProgress]
 * element to the subscribing app on every tick of the device-local [ModelDownloadProgressHolder],
 * and a terminal `StreamElement.End` when the download finishes.
 *
 * ⭐ `DownloadProgressImplementation`'s twin, one service over. Every ⚠ below was paid for once
 * already by region delivery; the two differences are that a checkpoint is several artifacts (so the
 * holder's byte count already spans them — see [ModelDownloadProgressHolder]) and that a model
 * download is minutes rather than seconds, which makes the wait asymmetry below matter more.
 *
 * ⚠ Its source is an in-memory `StateFlow`, **not** a `StreamRegistry` listener over the synced
 * store, so model progress never touches the synced realm (IN-D4/D5).
 *
 * Lifecycle: the collector runs on [ExecutionContext.sessionScope], so it dies with the session. It
 * also self-terminates when the holder entry disappears, so a client that navigates away mid-download
 * leaves it running only until the download completes — bounded, and its sends land on a streamId
 * nobody is listening to.
 */
internal class ModelDownloadProgressImplementation(
    private val holder: ModelDownloadProgressHolder,
    private val downloader: ModelDownloader,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val modelId: String = params.read<TString>(DownloadProgress.Params.MODEL_ID).get()
        val streamId: Long = params.read<TInteger>(DownloadProgress.Params.STREAM_ID).get()

        val context = executionContext.context
        val connection = executionContext.clientConnection

        executionContext.sessionScope.launch {
            // ⚠⚠ Nothing to report and nothing coming — END rather than wait forever. Reached when
            // the proxy restarted under a subscriber (its in-memory holder died with it), or when
            // `downloadModel` failed before registering. Without this the client's flow stays open
            // behind a bar that never moves and never errors.
            if (!awaitRegistration(modelId)) {
                connection.send(endElement(context, streamId))
                return@launch
            }

            var seen = false
            holder.progress
                // `null` is the terminal sentinel: once the entry existed and is now gone, emit it
                // and stop. A StateFlow replays the current map, so a late subscriber immediately
                // sees an in-flight download's current bytes rather than waiting for the next chunk.
                .transformWhile { byModel ->
                    val snapshot = byModel[modelId]
                    when {
                        snapshot != null -> {
                            seen = true
                            emit(snapshot)
                            true
                        }
                        seen -> {
                            emit(null)
                            false
                        }
                        // Not started yet — the paired downloadModel call is registering it.
                        else -> true
                    }
                }
                .collect { snapshot ->
                    if (snapshot != null) {
                        connection.send(StreamElementValueDto(streamId, snapshot.toWire(context)))
                    } else {
                        connection.send(endElement(context, streamId))
                    }
                }
        }

        return null as T
    }

    /**
     * Waits until [modelId] has a progress entry, returning false if one is never coming.
     *
     * ⚠⚠ The wait is **unbounded while a download is actually in flight** and bounded otherwise, and
     * that asymmetry is the whole point. [org.tenshu.inference.proxy.catalog.DefaultModelDownloader]
     * registers the entry *after* `catalogSource.load()` — a network fetch that can legitimately take
     * tens of seconds — and then, on a resume, after replaying staged bytes through a digest, which
     * for a partly-downloaded 280 MB tower is longer still. A plain wall-clock timeout would cut off
     * healthy downloads on a slow link. The grace window covers only the client-side ordering race:
     * `downloadProgress` may be subscribed a moment before the paired `downloadModel` registers.
     */
    private suspend fun awaitRegistration(modelId: String): Boolean {
        var waited = 0L
        while (true) {
            if (holder.progress.value[modelId] != null) return true
            if (downloader.isDownloading(modelId)) {
                // In flight but pre-registration: keep waiting, however long it takes.
                waited = 0L
            } else if (waited >= REGISTRATION_GRACE_MS) {
                return false
            }
            delay(REGISTRATION_POLL_MS)
            waited += REGISTRATION_POLL_MS
        }
    }

    private companion object {
        /** Covers only the subscribe-before-`downloadModel`-registers ordering race. */
        const val REGISTRATION_GRACE_MS = 3_000L
        const val REGISTRATION_POLL_MS = 50L
    }
}

private fun ModelDownloadProgressSnapshot.toWire(context: TContext): ModelDownloadProgress =
    ModelDownloadProgress.create(context, modelId, writtenBytes, totalBytes)

/**
 * A no-error `StreamElement.End` for [streamId] — normal completion. Mirrors StreamRegistry.
 *
 * ⚠ `internal` rather than private so [MediaIndexProgressImplementation] shares it: a stream op's
 * terminal element is framework shape, and two copies of it are two things to get differently wrong.
 */
internal fun endElement(context: TContext, streamId: Long): Data =
    context.dataFactory().createComposite<StreamElement<*>>(
        type = StreamElement.End.ID,
        fields = listOf<Data?>(
            context.dataFactory().createValue("tenshu/core/number/integer", streamId),
        ),
    )
