package org.tenshu.file.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.file.GetPreview
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.file.proxy.ThumbnailLadderRenderer
import org.tenshu.file.proxy.ThumbnailLadderRenderer.Outcome
import org.tenshu.file.test.createFileProxy
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P1a's minter half (`media-processing-pipeline-sweep.md` §4.1): [ThumbnailClassMinter.renderStills]
 * takes the ladder where a platform provides one and keeps [ThumbnailClassMinter.renderStill]'s
 * contract rung for rung — a decline stays a decline, a throw stays a failure, the cap is the mint's.
 *
 * ⚠⚠ Common code on purpose: this runs on Kotlin/Native too, where a `Declined`-initialised local that
 * is later reassigned threw a ClassCastException and turned every iOS rung into `Failed` for nine days
 * while the JVM stayed green. Every answer here crosses the same suspend boundary that bug lived on.
 */
class ThumbnailLadderSeamTest {

    private val rungs = ThumbnailClasses.STILLS

    @Test
    fun theLadderAnswersEveryRungInOrder_andGetPreviewIsNeverAsked() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val previews = CountingPreview()
        proxy.additionalImplementations[GetPreview.ID] = previews
        val asked = mutableListOf<ThumbnailLadderRenderer.Rung>()
        val minter = minterWith { _, _, requested ->
            asked += requested
            requested.map { Outcome.Encoded(byteArrayOf(it.px.toByte())) }
        }

        val renders = minter.renderStills(proxy.executionContext, "/a.jpg", byteArrayOf(9), rungs, GetPreview.Params.FORMAT_JPEG)

        assertEquals(rungs.map { it.px.toByte() }, renders.map { assertIs<ThumbnailClassMinter.Render.Bytes>(it).bytes.single() })
        assertEquals(0, previews.calls, "a handled ladder never falls back to a per-rung render")
        assertEquals(rungs.map { it.maxBytes }, asked.map { it.maxBytes }, "each rung carries its own cap")
        assertEquals(
            ThumbnailClassMinter.STEP_DOWN_QUALITIES,
            asked.first().qualities,
            "the step-down policy stays the minter's, whichever path renders",
        )
    }

    @Test
    fun aDeclineStaysADecline_anOverCapEncodeIsDeclined_anEmptyEncodeIsDeclined() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val minter = minterWith { _, _, requested ->
            listOf(
                Outcome.Declined,
                Outcome.Encoded(ByteArray(requested[1].maxBytes + 1)),
                Outcome.Encoded(ByteArray(0)),
                Outcome.Encoded(ByteArray(requested[3].maxBytes)),
            )
        }

        val renders = minter.renderStills(proxy.executionContext, "/a.jpg", byteArrayOf(9), rungs, GetPreview.Params.FORMAT_JPEG)

        assertIs<ThumbnailClassMinter.Render.Declined>(renders[0])
        assertIs<ThumbnailClassMinter.Render.Declined>(renders[1], "the cap is the mint's invariant, re-checked here")
        assertIs<ThumbnailClassMinter.Render.Declined>(renders[2])
        assertIs<ThumbnailClassMinter.Render.Bytes>(renders[3], "exactly at the cap is within it")
    }

    @Test
    fun aThrowingLadderIsAFailureAtEveryRung_neverADecline() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val minter = minterWith { _, _, _ -> error("codec fell over") }

        val renders = minter.renderStills(proxy.executionContext, "/a.jpg", byteArrayOf(9), rungs, GetPreview.Params.FORMAT_JPEG)

        assertEquals(rungs.size, renders.size)
        // ⚠ The backfill records a decline for good and retries a failure; a crash read as a decline
        // would leave the file without its rungs on every device, permanently.
        renders.forEach { assertIs<ThumbnailClassMinter.Render.Failed>(it) }
    }

    @Test
    fun aLadderThatDoesNotHandleTheFile_fallsBackToOneGetPreviewPerRung() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val previews = CountingPreview()
        proxy.additionalImplementations[GetPreview.ID] = previews
        val minter = minterWith { _, _, _ -> null }

        val renders = minter.renderStills(proxy.executionContext, "/a.pdf", byteArrayOf(9), rungs, GetPreview.Params.FORMAT_JPEG)

        assertEquals(rungs.size, previews.calls, "today's path: one GetPreview per rung")
        renders.forEach { assertIs<ThumbnailClassMinter.Render.Bytes>(it) }
    }

    @Test
    fun noLadderWired_isTodaysPerRungPath() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val previews = CountingPreview()
        proxy.additionalImplementations[GetPreview.ID] = previews
        val minter = ThumbnailClassMinter(fileDatabase = InMemoryFileDatabase())

        val renders = minter.renderStills(proxy.executionContext, "/a.jpg", byteArrayOf(9), rungs, GetPreview.Params.FORMAT_JPEG)

        assertEquals(rungs.size, previews.calls)
        assertTrue(renders.all { it is ThumbnailClassMinter.Render.Bytes })
    }

    /**
     * P3 (D4): every render runs on the media lane, the ladder's included — it decodes and encodes
     * inline, so off the lane it would run uncapped on the caller's thread.
     */
    @Test
    fun theLadderRendersOnTheMediaLane() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val lane = CountingLane()
        val minter = ThumbnailClassMinter(
            fileDatabase = InMemoryFileDatabase(),
            thumbnailLadderRenderer = { _, _, requested -> requested.map { Outcome.Encoded(byteArrayOf(1)) } },
            lane = lane,
        )

        minter.renderStills(proxy.executionContext, "/a.jpg", byteArrayOf(9), rungs, GetPreview.Params.FORMAT_JPEG)

        assertTrue(lane.dispatches > 0, "the ladder render was dispatched onto the lane")
    }

    private class CountingLane : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            Dispatchers.Default.dispatch(context, block)
        }
    }

    private fun minterWith(renderer: ThumbnailLadderRenderer) =
        ThumbnailClassMinter(fileDatabase = InMemoryFileDatabase(), thumbnailLadderRenderer = renderer)

    private class CountingPreview : SyncOperationImplementation {
        var calls = 0

        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            calls++
            @Suppress("UNCHECKED_CAST")
            return TByteArrayDto(byteArrayOf(1, 2, 3)) as T
        }
    }
}
