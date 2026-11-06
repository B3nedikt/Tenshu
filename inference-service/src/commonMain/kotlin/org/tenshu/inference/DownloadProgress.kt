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
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.StreamOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * A **push stream** of [ModelDownloadProgress] ticks for an in-flight [downloadModel] (IN-2c, the
 * 1c-3 slice). Each streamed chunk the proxy writes to a staging file emits one tick; the stream
 * **ends** when the download finishes.
 *
 * ⚠ Push, not poll, and **device-local**: the source is the proxy's in-memory progress holder, not a
 * `select{}.flow()` over the synced object store. Model install progress is device state (IN-D4), so
 * it must not be a `DynamicProperty` — the same reasoning that keeps region progress off the realm.
 *
 * ⚠ Success or failure of the whole download is [downloadModel]'s own return — it yields the
 * [InstalledModel] or throws [ModelUnavailable]. This stream carries no phase field; it drives the
 * bar and completes when the download does.
 *
 * ⚠⚠ **The bar spans the checkpoint, not the file.** A checkpoint is three artifacts fetched in
 * sequence, and `downloadedBytes` accumulates across them — see [ModelDownloadProgress].
 */
fun InferenceService.downloadProgress(modelId: String): Flow<ModelDownloadProgress> = flow {
    val requestObject = findOperation<DownloadProgress>(DownloadProgress.ID).downloadProgress(modelId)
    val interactor = requestObject.asStreamInteractor<ModelDownloadProgress>()
    // Single-producer/single-consumer: callbacks (which may fire on parallel coroutines) only
    // `trySend`; this flow{} loop is the sole consumer — mirrors `Stream.kt`'s `.flow()`, minus the
    // list fold (each Value payload IS the current progress, there is no collection to merge).
    val signals = Channel<StreamSignal>(Channel.UNLIMITED)

    val listener = object : StreamCallback<ModelDownloadProgress> {
        override fun start(error: TError?) {
            if (error != null) signals.close()
        }

        override fun element(streamElement: StreamElement<ModelDownloadProgress>) {
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
                    // A single-value stream never removes; End just completes the flow — the caller
                    // reads terminal success/failure off `downloadModel`'s return.
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
    class Event(val element: StreamElement<ModelDownloadProgress>) : StreamSignal
}

interface DownloadProgress : Operation {

    override val schema: OperationSchema get() = Schema

    fun downloadProgress(modelId: String): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/downloadProgress"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                string(Params.MODEL_ID)
                int(Params.STREAM_ID)
            },
            traits = listOf(TerminalOperation.Schema, StreamOperation.Schema),
        )
    }

    object Params {
        const val MODEL_ID = "$ID/modelId"
        const val STREAM_ID = "$ID/streamId"
    }
}
