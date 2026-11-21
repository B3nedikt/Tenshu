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
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.StreamOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * A **push stream** of this device's *People & pets* revision: a counter that moves once for every
 * index pass (or withdrawal) that changed the face or pet template store (`photos-pets.md` §10.19).
 *
 * ⚠⚠ Why it exists: face and pet groups are clustered from device-local logs, not realm rows, so
 * no chain read can observe them — `faceGroups` and `petGroups` answer once. Without this, Photos on
 * Android and desktop kept its pre-pass People & pets until the Collections tab was tapped again;
 * iOS reads the same counter in-process (`MediaIndexRunner.faceChanges`).
 *
 * ⚠ The first value is the counter at subscribe — a baseline, not a change. Every later value is a
 * change. A Control restart starts the counter again from 0 and the client resubscribes
 * (`core-client-resubscription.md`), so a reader compares values rather than counting emissions.
 *
 * ⚠ The stream stays open until the subscriber ends it; the proxy's feeder is bound to the stream
 * id (`ExecutionContext.launchStreamJob`), so an `EndStream` stops it.
 */
fun InferenceService.peopleIndexChanges(): Flow<Long> = flow {
    val requestObject = findOperation<PeopleIndexChanges>(PeopleIndexChanges.ID).peopleIndexChanges()
    val interactor = requestObject.asStreamInteractor<TInteger>()
    // Single-producer/single-consumer, `indexProgress`'s shape: callbacks only `trySend`.
    val signals = Channel<StreamElement<TInteger>>(Channel.UNLIMITED)
    var failure: TError? = null

    val listener = object : StreamCallback<TInteger> {
        override fun start(error: TError?) {
            if (error != null) {
                failure = error
                signals.close()
            }
        }

        override fun element(streamElement: StreamElement<TInteger>) {
            signals.trySend(streamElement)
        }

        override fun end(error: TError?) {
            failure = error
            signals.close()
        }
    }

    try {
        interactor.registerCallback(listener)
        for (element in signals) {
            when (element) {
                is StreamElement.Value -> emit(element.payload.get())
                is StreamElement.Removed -> Unit
                is StreamElement.End -> break
            }
        }
        // ⚠⚠ An ended stream is not "no more changes": a Control without the op, or one that
        // failed it, would otherwise read as a library that never changes again.
        failure?.let { throw PeopleIndexChangesEnded(it.toString()) }
    } finally {
        withContext(NonCancellable) { interactor.endStream(listener) }
    }
}

/** The stream ended with an error — an older Control without the op among them. */
class PeopleIndexChangesEnded(message: String) : RuntimeException(message)

interface PeopleIndexChanges : Operation {

    override val schema: OperationSchema get() = Schema

    fun peopleIndexChanges(): RequestObject

    companion object {
        const val ID = "${InferenceService.ID}/peopleIndexChanges"

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
