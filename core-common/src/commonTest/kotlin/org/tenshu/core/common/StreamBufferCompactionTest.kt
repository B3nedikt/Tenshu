package org.tenshu.core.common

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.common.impl.StreamElementRemovedDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay buffer must be bounded by **live** rows, not by rows *ever seen*
 * (`core-client-resubscription.md` R3, the residual its own note left open).
 *
 * R3 collapsed repeated edits of one row to a per-row latest, but it could not drop a *removal*:
 * a tombstone may be the only thing cancelling a row inside the initial snapshot, which is
 * replayed whole and cannot be rewritten. So every removal was kept, and a long-lived stream over
 * a high-churn row set accumulated one entry per row it had ever carried — including rows added
 * and deleted *after* the snapshot, whose tombstones cancel nothing any replay would mention.
 *
 * Driven directly rather than through a proxy: the interesting behaviour starts above a
 * compaction floor of 64 buffered entries, which a store-backed test would need ~13 s of spaced
 * writes to reach (they must be spaced — the write mailbox dedupes by hash).
 */
class StreamBufferCompactionTest {

    private class RecordingCallback : StreamCallback<Data> {
        val elements = mutableListOf<StreamElement<Data>>()
        override fun start(error: TError?) {}
        override fun element(streamElement: StreamElement<Data>) { elements += streamElement }
        override fun reset() {}
        override fun end(error: TError?) {}
    }

    private fun context(): TContext = createTestContext(createSyntheticTestRegistry())

    private fun interactor(ctx: TContext) =
        StreamInteractorImpl<Data>(onEndStream = {}, onCleanup = {}, context = ctx)

    private fun row(ctx: TContext, name: String): Data =
        SyntheticTestData.Factory.create(ctx, name, 1)

    private fun dataId(ctx: TContext, name: String): DataId =
        DataId.StringDataId.create(ctx, SyntheticTestData.ID, name)

    private fun value(ctx: TContext, payload: Data) = StreamElementValueDto(1L, payload)

    /** The initial snapshot: one element whose payload is the whole list, as `StreamRegistry` sends it. */
    private fun snapshot(ctx: TContext, rows: List<Data>) =
        StreamElementValueDto(1L, ctx.dataFactory().createList<Data>(rows))

    private fun removed(ctx: TContext, name: String) = StreamElementRemovedDto(1L, dataId(ctx, name))

    private suspend fun StreamInteractorImpl<Data>.seed(ctx: TContext) {
        @Suppress("UNCHECKED_CAST")
        setInitialResult(TResult.Success.create(ctx, "ok".toTString(ctx)) as TResult<Data?>)
    }

    @Test
    fun rowsThatCameAndWentDoNotAccumulate() = runTest {
        val ctx = context()
        val subject = interactor(ctx)
        subject.seed(ctx)

        // 200 rows, each added and then deleted — none of them ever in a snapshot, so none of
        // their tombstones cancels anything a late subscriber would be shown.
        repeat(CHURN) { i ->
            subject.dispatchElement(value(ctx, row(ctx, "churn-$i")))
            subject.dispatchElement(removed(ctx, "churn-$i"))
        }

        val late = RecordingCallback()
        subject.registerCallback(late)

        assertTrue(
            late.elements.size < CHURN / 10,
            "$CHURN rows came and went, and the buffer replayed ${late.elements.size} elements " +
                "— it is bounded by rows ever seen rather than by live rows",
        )
    }

    @Test
    fun aTombstoneForARowInTheSnapshotSurvivesCompaction() = runTest {
        // ⚠⚠ THE correctness guard. Compaction drops tombstones, and dropping one that was still
        // cancelling a snapshot row resurrects a deleted row for every late subscriber — strictly
        // worse than the memory it saves. This is R3's original bug, re-reachable through the new
        // path, so it is asserted at a buffer size that actually triggers compaction.
        val ctx = context()
        val subject = interactor(ctx)
        subject.seed(ctx)

        subject.dispatchElement(snapshot(ctx, listOf(row(ctx, "alpha"), row(ctx, "beta"))))
        subject.dispatchElement(removed(ctx, "alpha"))

        // Push well past the compaction floor with churn that IS collectable.
        repeat(CHURN) { i ->
            subject.dispatchElement(value(ctx, row(ctx, "churn-$i")))
            subject.dispatchElement(removed(ctx, "churn-$i"))
        }

        val late = RecordingCallback()
        subject.registerCallback(late)

        val tombstones = late.elements.filterIsInstance<StreamElement.Removed<Data>>()
        val alphaId = dataId(ctx, "alpha").getDataIdHash()
        assertTrue(
            tombstones.any { it.removedId.getDataIdHash() == alphaId },
            "the tombstone for a row inside the replayed snapshot was compacted away, so a late " +
                "subscriber sees 'alpha' resurrected",
        )
    }

    @Test
    fun liveRowsAreStillReplayed() = runTest {
        // The control: compaction must drop only what cancels nothing. A test that merely asserts
        // "the buffer got smaller" passes against an implementation that throws the log away.
        val ctx = context()
        val subject = interactor(ctx)
        subject.seed(ctx)

        repeat(CHURN) { i ->
            subject.dispatchElement(value(ctx, row(ctx, "churn-$i")))
            subject.dispatchElement(removed(ctx, "churn-$i"))
        }
        subject.dispatchElement(value(ctx, row(ctx, "survivor")))

        val late = RecordingCallback()
        subject.registerCallback(late)

        assertEquals(
            1,
            late.elements.filterIsInstance<StreamElement.Value<Data>>().size,
            "the one row still alive must be replayed",
        )
    }

    private companion object {
        const val CHURN = 200
    }
}
