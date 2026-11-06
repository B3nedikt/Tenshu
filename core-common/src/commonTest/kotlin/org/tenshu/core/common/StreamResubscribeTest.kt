package org.tenshu.core.common

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.core.TContext
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client half of resubscription-after-a-proxy-restart
 * (`docs/proposals/core-client-resubscription.md` R1).
 *
 * The proxy's `StreamRegistry` is in-memory and dies with its process, so after a restart every
 * stream a client holds is subscribed to nothing while the transport quietly recovers — the client
 * renders pre-restart values indefinitely. `prepareForResubscribe` is what makes replaying the
 * subscription safe.
 */
class StreamResubscribeTest {

    private class RecordingCallback : StreamCallback<Data> {
        val elements = mutableListOf<StreamElement<Data>>()
        var starts = 0
        var resets = 0
        var ends = 0

        override fun start(error: TError?) { starts++ }
        override fun element(streamElement: StreamElement<Data>) { elements += streamElement }
        override fun reset() { resets++ }
        override fun end(error: TError?) { ends++ }
    }

    private fun context(): TContext = createTestContext(createSyntheticTestRegistry())

    private fun interactor(): StreamInteractorImpl<Data> =
        StreamInteractorImpl(onEndStream = {}, onCleanup = {})

    private fun TContext.success(): TResult<Data?> =
        @Suppress("UNCHECKED_CAST")
        (TResult.Success.create(this, "ok".toTString(this)) as TResult<Data?>)

    private fun value(ctx: TContext, id: Long) =
        StreamElementValueDto(streamId = id, payload = "row-$id".toTString(ctx))

    @Test
    fun resubscribe_tellsConsumersToDropStateAccumulatedAgainstTheDeadProxy() = runTest {
        // THE correctness point. A consumer merges values by id and only drops rows on an explicit
        // `Removed`; every `Removed` issued while the transport was down died with the old proxy's
        // registry. Merging the fresh snapshot over the old state would therefore resurrect deleted
        // rows — so the consumer must be told to clear first.
        val ctx = context()
        val subject = interactor()
        val callback = RecordingCallback()

        subject.setInitialResult(ctx.success())
        subject.registerCallback(callback)
        subject.dispatchElement(value(ctx, 1))
        assertEquals(1, callback.elements.size)

        assertTrue(subject.prepareForResubscribe(), "a live stream is resubscribable")

        assertEquals(1, callback.resets, "the consumer must be told to reset exactly once")
    }

    @Test
    fun resubscribe_dropsTheBufferedDeltasSoALateCallbackIsNotReplayedStaleRows() = runTest {
        // `registerCallback` replays buffered elements to whoever joins next. Those are deltas
        // relative to a proxy that no longer exists, so keeping them would hand a post-reconnect
        // subscriber rows the new snapshot may no longer contain.
        val ctx = context()
        val subject = interactor()

        subject.setInitialResult(ctx.success())
        subject.dispatchElement(value(ctx, 1))
        subject.dispatchElement(value(ctx, 2))

        subject.prepareForResubscribe()

        val late = RecordingCallback()
        subject.registerCallback(late)
        assertTrue(late.elements.isEmpty(), "buffered pre-restart deltas must not be replayed")
    }

    @Test
    fun resubscribe_leavesTheWriteOnceInitialResultIntact() = runTest {
        // Deliberate: the initial payload reaches consumers as stream *elements*, not through
        // `initialResult` — `dispatchElement` only needs it to be a non-null Success, which it
        // already is. So resubscription needs no re-seed and the write-once contract is untouched.
        val ctx = context()
        val subject = interactor()
        val seeded = ctx.success()

        subject.setInitialResult(seeded)
        subject.prepareForResubscribe()

        assertEquals(seeded, subject.initialResult)
    }

    @Test
    fun resubscribe_isRefusedForStreamsWhoseConsumerHasAlreadyGone() = runTest {
        val ctx = context()

        // Ended: the proxy sent a terminal End, so the consumer's channel is closed. Re-registering
        // would leak a proxy-side listener nobody reads.
        val ended = interactor()
        ended.setInitialResult(ctx.success())
        ended.dispatchElement(
            org.tenshu.core.common.impl.StreamElementEndDto(streamId = 1L, error = null),
        )
        assertFalse(ended.prepareForResubscribe(), "an ended stream must not be resubscribed")

        // Never successfully subscribed in the first place.
        val neverStarted = interactor()
        assertFalse(neverStarted.prepareForResubscribe(), "an unseeded stream must not be resubscribed")
    }
}
