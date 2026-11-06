package org.tenshu.inference

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
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.StreamOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * A **push stream** of [MediaIndexProgress] ticks for this device's index pass (IN-D6, the 1e-3
 * slice) — the surface over the holder 1e-2 built.
 *
 * ⚠ Push, not poll, and **device-local**: the source is the proxy's in-memory progress holder, not a
 * `select{}.flow()` over the synced object store. How far through its library a device is is device
 * state (IN-D4/D5), the same reasoning that keeps model-download progress off the realm.
 *
 * ⚠ No parameters beyond the stream id. There is at most one pass per device — the schedule carries
 * a `dedupKey` — so there is nothing to address.
 *
 * ⚠⚠ **The stream is bounded by the pass, and always emits before it ends.** [downloadProgress] has
 * to disambiguate an absent holder entry, because a download registers its entry *late* and absence
 * therefore means either "not yet" or "never" — hence its `isDownloading` asymmetry. Nothing is
 * ambiguous here: `start()` is the pass's first act and the pass is the holder's only writer, so an
 * absent entry means exactly *"no pass is running"*. That is a fact to report, so the stream reports
 * it — a final `indexing = false` tick — and completes. See `inference-service.md` §30.2 for why it
 * does not instead stay open waiting for the next pass.
 */
fun InferenceService.indexProgress(): Flow<MediaIndexProgress> = flow {
    val requestObject = findOperation<IndexProgress>(IndexProgress.ID).indexProgress()
    val interactor = requestObject.asStreamInteractor<MediaIndexProgress>()
    // Single-producer/single-consumer: callbacks (which may fire on parallel coroutines) only
    // `trySend`; this flow{} loop is the sole consumer — the `downloadProgress` shape exactly.
    val signals = Channel<IndexProgressSignal>(Channel.UNLIMITED)

    val listener = object : StreamCallback<MediaIndexProgress> {
        override fun start(error: TError?) {
            if (error != null) signals.close()
        }

        override fun element(streamElement: StreamElement<MediaIndexProgress>) {
            signals.trySend(IndexProgressSignal.Event(streamElement))
        }

        override fun end(error: TError?) {
            signals.close()
        }
    }

    try {
        interactor.registerCallback(listener)
        for (signal in signals) {
            when (signal) {
                is IndexProgressSignal.Event -> when (val element = signal.element) {
                    is StreamElement.Value -> emit(element.payload)
                    // A single-value stream never removes; End just completes the flow.
                    is StreamElement.Removed -> Unit
                    is StreamElement.End -> break
                }
            }
        }
    } finally {
        withContext(NonCancellable) { interactor.endStream(listener) }
    }
}

// ⚠ Named apart from `DownloadProgress.kt`'s identical `StreamSignal`, not out of taste: a private
// top-level *interface* still compiles to a class in the package, so two files declaring the same
// one collide at the JVM level rather than staying file-private.
private sealed interface IndexProgressSignal {
    class Event(val element: StreamElement<MediaIndexProgress>) : IndexProgressSignal
}

interface IndexProgress : Operation {

    override val schema: OperationSchema get() = Schema

    fun indexProgress(): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/indexProgress"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                int(Params.STREAM_ID)
            },
            traits = listOf(TerminalOperation.Schema, StreamOperation.Schema),
        )
    }

    object Params {
        const val STREAM_ID = "$ID/streamId"
    }
}
