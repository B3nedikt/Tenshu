package org.tenshu.core.common

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.common.impl.StreamElementEndDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.core.TContext
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stream whose terminal `End` arrives **before** its initial result is seeded must still
 * terminate its consumer.
 *
 * The ordering is not exotic, it is the norm for any stream op that ends immediately.
 * `DefaultInteractorProvider.buildStreamInteractor` registers the interactor, *then* calls the op,
 * *then* seeds the result it returned — and the proxy-side collector is already running by the time
 * the call returns. So a proxy that answers "nothing to stream, here is the End" pushes that End
 * against a client that has not seeded yet, and wins the race whenever the subscribing thread is
 * descheduled in between.
 *
 * The failure it used to produce was silent and total: `dispatchElement` marked the stream closed
 * for the `End`, which made `setInitialResult` bail, which left `registerCallback` with no initial
 * result to replay against — so the consumer's flow hung open forever with no element, no end and
 * no failure. Exactly the frozen-progress-bar symptom
 * `MapRegionServiceTest.downloadProgressEndsWhenNoDownloadIsInFlight` exists to prevent, one layer
 * below where that test was looking. It surfaced as an unreproducible ~1-in-2 flake of that test
 * under full-gate load (docs/tech-debt.md); on an idle box the subscribing thread always won.
 */
class StreamEndBeforeInitialResultTest {

    private class RecordingCallback : StreamCallback<Data> {
        val elements = mutableListOf<StreamElement<Data>>()
        var starts = 0
        var ends = 0

        override fun start(error: TError?) { starts++ }
        override fun element(streamElement: StreamElement<Data>) { elements += streamElement }
        override fun end(error: TError?) { ends++ }
    }

    private fun context(): TContext = createTestContext(createSyntheticTestRegistry())

    private fun TContext.success(): TResult<Data?> =
        @Suppress("UNCHECKED_CAST")
        (TResult.Success.create(this, "ok".toTString(this)) as TResult<Data?>)

    @Test
    fun anEndThatBeatsTheSeedStillTerminatesALaterSubscriber() = runTest {
        val ctx = context()
        var cleanups = 0
        val subject = StreamInteractorImpl<Data>(onEndStream = {}, onCleanup = { cleanups++ })

        // Proxy-side ordering: the stream ends before the subscribing call has returned.
        subject.dispatchElement(StreamElementEndDto(streamId = 1L, error = null))
        subject.setInitialResult(ctx.success())

        // ...and only now does the consumer's flow{} body register its listener.
        val callback = RecordingCallback()
        subject.registerCallback(callback)

        assertEquals(1, callback.starts, "the subscriber must be started")
        assertEquals(1, callback.ends, "the buffered terminal must reach the subscriber")
        assertTrue(
            callback.elements.any { it is StreamElement.End },
            "the End element itself must be replayed, not just the end() signal",
        )
        assertEquals(1, cleanups, "the provider must drop the entry for a stream that has ended")
    }

    @Test
    fun anEndThatBeatsTheSeedStillTerminatesAnAlreadyRegisteredSubscriber() = runTest {
        // The other order of the same race: the subscriber got in first, the seed still comes last.
        val ctx = context()
        val subject = StreamInteractorImpl<Data>(onEndStream = {}, onCleanup = {})
        val callback = RecordingCallback()

        subject.registerCallback(callback)
        subject.dispatchElement(StreamElementEndDto(streamId = 1L, error = null))
        subject.setInitialResult(ctx.success())

        assertEquals(1, callback.starts, "the subscriber must be started")
        assertEquals(1, callback.ends, "the buffered terminal must reach the subscriber")
    }

    @Test
    fun valuesBufferedAheadOfTheSeedAreReplayedBeforeTheTerminal() = runTest {
        // Values were never the broken half — they are buffered and replayed — but the terminal now
        // travels the same path, so pin the ordering the consumer's fold depends on.
        val ctx = context()
        val subject = StreamInteractorImpl<Data>(onEndStream = {}, onCleanup = {})

        subject.dispatchElement(StreamElementValueDto(streamId = 1L, payload = "row".toTString(ctx)))
        subject.dispatchElement(StreamElementEndDto(streamId = 1L, error = null))
        subject.setInitialResult(ctx.success())

        val callback = RecordingCallback()
        subject.registerCallback(callback)

        assertEquals(2, callback.elements.size, "value then terminal")
        assertTrue(callback.elements[0] is StreamElement.Value, "the value must arrive first")
        assertTrue(callback.elements[1] is StreamElement.End, "the terminal must arrive last")
        assertEquals(1, callback.ends)
    }

    @Test
    fun aStreamTornDownByItsConsumerIsStillNotSeeded() = runTest {
        // The guard that must survive the fix: `endStream` closes a stream because its consumer is
        // gone, and that kind of closed really does have nothing left to deliver. Only a stream
        // closed by an *End* carries a terminal worth seeding for.
        val ctx = context()
        val subject = StreamInteractorImpl<Data>(onEndStream = {}, onCleanup = {})
        val callback = RecordingCallback()

        subject.registerCallback(callback)
        subject.endStream(callback)
        subject.setInitialResult(ctx.success())

        assertEquals(null, subject.initialResult, "a torn-down stream must not be seeded")
        assertEquals(0, callback.starts, "a departed consumer must not be started after the fact")
    }
}
