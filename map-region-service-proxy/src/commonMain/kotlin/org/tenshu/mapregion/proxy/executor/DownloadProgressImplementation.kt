package org.tenshu.mapregion.proxy.executor

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
import org.tenshu.core.core.dataFactory
import org.tenshu.core.mapregion.DownloadProgress
import org.tenshu.core.mapregion.RegionDownloadProgress
import org.tenshu.core.number.TInteger
import org.tenshu.mapregion.proxy.RegionDownloadProgressHolder
import org.tenshu.mapregion.proxy.RegionDownloadProgressSnapshot
import org.tenshu.mapregion.proxy.RegionDownloader

/**
 * Serves the [DownloadProgress] **stream** op: pushes a [RegionDownloadProgress] element to the
 * subscribing app on every tick of the device-local [RegionDownloadProgressHolder], and a terminal
 * `StreamElement.End` when the download finishes (`maps-region-delivery.md` §7.1, the download-UX slice).
 *
 * This is the device-local push that replaced the old 700 ms client poll: it reuses the framework's
 * stream wire (`clientConnection.send(StreamElement…)` keyed by the client-allocated `streamId`) but its
 * source is an in-memory `StateFlow`, **not** a `StreamRegistry` array listener over the synced store — so
 * region progress never touches the synced realm (D5).
 *
 * Lifecycle: the collector runs on [ExecutionContext.sessionScope], so it dies with the session (client
 * disconnect). It also self-terminates when the holder entry disappears (download done) — the generic
 * `EndStream` a client sends on unsubscribe only tears down `StreamRegistry` streams, not this one, so a
 * client that navigates away mid-download leaves the collector running until the download completes
 * (bounded, harmless — its sends land on a streamId the client no longer listens to).
 */
internal class DownloadProgressImplementation(
    private val holder: RegionDownloadProgressHolder,
    private val downloader: RegionDownloader,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val regionId: String = params.read<TString>(DownloadProgress.Params.REGION_ID).get()
        val streamId: Long = params.read<TInteger>(DownloadProgress.Params.STREAM_ID).get()

        val context = executionContext.context
        val connection = executionContext.clientConnection

        executionContext.sessionScope.launch {
            // Nothing to report and nothing coming — END rather than wait forever. Reached when the
            // proxy restarted under a subscriber (its in-memory holder died with it, and the download
            // with it: staging files are dropped, never resumed), or when `downloadRegion` failed
            // before it could register. Both used to fall into the "keep waiting" branch below and
            // hang the client's flow open with a progress bar that never moved and never errored.
            if (!awaitRegistration(regionId)) {
                connection.send(endElement(context, streamId))
                return@launch
            }

            var seen = false
            holder.progress
                // `null` is the terminal sentinel: once the entry existed and is now gone (the download
                // finished), emit it and stop collecting. A StateFlow emits the current map first, so a
                // late subscriber immediately sees an in-flight download's current bytes.
                .transformWhile { byRegion ->
                    val snapshot = byRegion[regionId]
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
                        // Not started yet — the paired downloadRegion call is registering it. Keep waiting.
                        else -> true
                    }
                }
                .collect { snapshot ->
                    if (snapshot != null) {
                        connection.send(StreamElementValueDto(streamId, snapshot.toWire(context, regionId)))
                    } else {
                        connection.send(endElement(context, streamId))
                    }
                }
        }

        return null as T
    }

    /**
     * Waits until [regionId] has a progress entry, returning false if one is never coming.
     *
     * The wait is **unbounded while a download is actually in flight** and only bounded otherwise.
     * That asymmetry is the point: [org.tenshu.mapregion.proxy.DefaultRegionDownloader] registers the
     * entry *after* `catalogSource.load()`, a network fetch that can legitimately take tens of
     * seconds, so a plain wall-clock timeout would cut off healthy downloads on a slow link. The
     * grace window covers only the client-side ordering race — `downloadProgress` may be subscribed a
     * moment before the paired `downloadRegion` registers.
     */
    private suspend fun awaitRegistration(regionId: String): Boolean {
        var waited = 0L
        while (true) {
            if (holder.progress.value[regionId] != null) return true
            if (downloader.isDownloading(regionId)) {
                // In flight but pre-catalogue: keep waiting, however long it takes.
                waited = 0L
            } else if (waited >= REGISTRATION_GRACE_MS) {
                return false
            }
            delay(REGISTRATION_POLL_MS)
            waited += REGISTRATION_POLL_MS
        }
    }

    private companion object {
        /** Covers only the subscribe-before-`downloadRegion`-registers ordering race. */
        const val REGISTRATION_GRACE_MS = 3_000L
        const val REGISTRATION_POLL_MS = 50L
    }
}

private fun RegionDownloadProgressSnapshot.toWire(
    context: org.tenshu.core.core.TContext,
    regionId: String,
): RegionDownloadProgress =
    RegionDownloadProgress.create(context, regionId, writtenBytes, totalBytes)

/** A no-error `StreamElement.End` for [streamId] — normal stream completion. Mirrors StreamRegistry. */
private fun endElement(context: org.tenshu.core.core.TContext, streamId: Long): Data =
    context.dataFactory().createComposite<StreamElement<*>>(
        type = StreamElement.End.ID,
        fields = listOf<Data?>(
            context.dataFactory().createValue("tenshu/core/number/integer", streamId),
        ),
    )
