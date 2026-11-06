package org.tenshu.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.toTString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The third forced pair behind `docs/tech-debt.md`'s gate-flake sighting of 2026-09-14 (second of
 * the day), and the one the other two do not cover: a subscriber that registers **while a live
 * element lands**.
 *
 * [StreamInteractorImpl] decided *what* to deliver under its mutex but delivered *outside* it. So
 * thread A (`registerCallback`) could add the callback and take its replay list — the snapshot —
 * release the lock, and only then call `element(snapshot)`; thread B (`dispatchElement(patch)`)
 * could enter the lock in between, see the callback already registered, and deliver the patch
 * first. The subscriber then saw patch, then snapshot: the "initial emission already carries the
 * preview" shape, with every frame delivered in wire order up to that point.
 *
 * The forcing: the callback's `element(snapshot)` — the replay — waits (bounded) for the patch to
 * arrive, which is exactly thread A being descheduled between releasing the lock and delivering.
 * Red when delivery happens outside the lock (the patch overtakes: `[patch, snapshot]`); green when
 * it happens inside (the patch cannot enter until the replay is delivered — the wait times out,
 * the snapshot is recorded, and the patch follows: `[snapshot, patch]`). Both arms terminate.
 */
class StreamInteractorImplDeliveryOrderTest {

    private val context = createTestContext(createSyntheticTestRegistry())

    private fun frame(id: Long, text: String): StreamElement<Data> =
        StreamElementValueDto(streamId = 7L, payload = "$id:$text".toTString(context))

    private fun StreamElement<Data>.label(): String =
        ((this as StreamElement.Value<Data>).payload as org.tenshu.core.TString).get().substringAfter(':')

    @Test
    fun aSubscriberRegisteringWhileAnElementLands_seesTheReplayBeforeTheLiveElement() = runBlocking {
        val interactor = StreamInteractorImpl<Data>(
            onEndStream = {},
            onCleanup = {},
            context = context,
        )
        interactor.setInitialResult(TResult.Success.create<Data?>(context = context, value = null))
        interactor.dispatchElement(frame(1, "snapshot")) // no subscriber yet: buffered for replay

        val replayEntered = CountDownLatch(1)
        val patchDelivered = CountDownLatch(1)
        val seen = mutableListOf<String>()
        val subscriber = object : StreamCallback<Data> {
            override fun start(error: TError?) {}
            override fun element(streamElement: StreamElement<Data>) {
                val label = streamElement.label()
                if (label == "snapshot") {
                    // Thread A, between the lock and the delivery: hold here while the patch tries
                    // to land. Bounded, so the arm where the lock keeps the patch out terminates.
                    replayEntered.countDown()
                    patchDelivered.await(1, TimeUnit.SECONDS)
                }
                synchronized(seen) { seen += label }
                if (label == "patch") patchDelivered.countDown()
            }
            override fun end(error: TError?) {}
        }

        val registering = async(Dispatchers.Default) { interactor.registerCallback(subscriber) }
        withContext(Dispatchers.Default) { replayEntered.await(5, TimeUnit.SECONDS) }
        val dispatching = async(Dispatchers.Default) { interactor.dispatchElement(frame(2, "patch")) }
        registering.await()
        dispatching.await()

        assertEquals(
            listOf("snapshot", "patch"),
            synchronized(seen) { seen.toList() },
            "a subscriber must receive its replay before any element dispatched after it registered",
        )
    }
}
