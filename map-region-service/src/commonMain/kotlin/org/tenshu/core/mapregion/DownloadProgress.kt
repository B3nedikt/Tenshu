package org.tenshu.core.mapregion

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.tenshu.core.Operation
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.asStreamInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.StreamOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * A **push stream** of [RegionDownloadProgress] ticks for an in-flight [downloadRegion]
 * (`maps-region-delivery.md` §7.1, the download-UX slice). Each streamed chunk the proxy writes to the
 * staging file emits one tick ([RegionDownloadProgress.writtenBytes] of
 * [RegionDownloadProgress.totalBytes]); the stream *ends* when the download finishes.
 *
 * **Push, not poll (and device-local).** This replaced a one-shot `downloadProgress(): TInteger` that the
 * app polled every 700 ms — a hand-rolled poll of a number that a real push channel can deliver. The push
 * reuses the framework's [StreamCallback]/[StreamElement] wire primitives, but its source is a device-local,
 * in-memory progress holder in the proxy, **not** a `select{}.flow()` over the synced object store: region
 * install/progress is a coarse-location signal deliberately kept off the synced realm (D5), so it must not
 * be a `DynamicProperty`.
 *
 * Success vs failure of the whole download is carried by [downloadRegion]'s own return (it yields the
 * [InstalledRegion] or throws), so this stream needs no phase field — it just drives the progress bar and
 * completes when the download does.
 */
fun MapRegionService.downloadProgress(regionId: String): Flow<RegionDownloadProgress> = flow {
    val requestObject = findOperation<DownloadProgress>(DownloadProgress.ID).downloadProgress(regionId)
    val interactor = requestObject.asStreamInteractor<RegionDownloadProgress>()
    // Single-producer/single-consumer: callbacks (which may fire on parallel coroutines) only
    // `trySend`; this flow{} loop is the sole consumer — mirrors Stream.kt's `.flow()`, minus the
    // list fold (each Value payload IS the current progress, there is no collection to merge).
    val signals = Channel<StreamSignal>(Channel.UNLIMITED)

    val listener = object : StreamCallback<RegionDownloadProgress> {
        override fun start(error: TError?) {
            if (error != null) signals.close()
        }

        override fun element(streamElement: StreamElement<RegionDownloadProgress>) {
            signals.trySend(StreamSignal.Event(streamElement))
        }

        override fun end(error: TError?) {
            signals.close()
        }
    }

    try {
        interactor.registerCallback(listener)
        for (signal in signals) {
            when (signal) {
                is StreamSignal.Event -> when (val element = signal.element) {
                    is StreamElement.Value -> emit(element.payload)
                    // A single-value stream never removes; End (with or without error) just completes
                    // the flow — the caller reads terminal success/failure off downloadRegion's return.
                    is StreamElement.Removed -> Unit
                    is StreamElement.End -> break
                }
            }
        }
    } finally {
        withContext(NonCancellable) { interactor.endStream(listener) }
    }
}

private sealed interface StreamSignal {
    class Event(val element: StreamElement<RegionDownloadProgress>) : StreamSignal
}

interface DownloadProgress : Operation {

    override val schema: OperationSchema get() = Schema

    fun downloadProgress(regionId: String): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/downloadProgress"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                string(Params.REGION_ID)
                int(Params.STREAM_ID)
            },
            traits = listOf(TerminalOperation.Schema, StreamOperation.Schema)
        )
    }

    object Params {
        const val REGION_ID = "$ID/regionId"
        const val STREAM_ID = "$ID/streamId"
    }
}
