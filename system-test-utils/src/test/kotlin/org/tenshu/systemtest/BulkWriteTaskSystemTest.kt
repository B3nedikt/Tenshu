package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.health.client.healthService
import org.tenshu.sync.client.withBulkWriteTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * C4 (`sync-device-catch-up.md` §11.7): the shared `withBulkWriteTask` really collapses a bulk
 * writer's blocks — measured on the **relay**, against the same writes run bare.
 *
 * ## ⚠⚠ Why this counts instead of asserting convergence
 *
 * Its predecessors (`TaskBatchedImportSystemTest`, `TaskBatchedUploadSystemTest`) assert that
 * every task-batched row reaches the joiner. That is worth proving, but it would pass unchanged
 * against a `withTask` that did **nothing at all** — the rows converge either way; batching only
 * changes how many blocks carry them. So the batching claim needs the number, and the bare phase
 * below is the control that gives it meaning: without it, "12 blocks" could just as easily be a
 * quiet relay as a working lane.
 */
class BulkWriteTaskSystemTest {

    /** Blocks the relay has ACCEPTED for this owner — the only place the count is honest. */
    private fun blocksAccepted(system: TestSystem, ownerId: String): Int =
        system.syncMailbox.getChanges(ownerId, 0L).blocks.size

    private suspend fun writeWorkouts(host: TestClient, prefix: String, count: Int) {
        repeat(count) { i ->
            val result = host.tenshuClient.healthService.recordWorkout(
                Workout.create(
                    context = host.executionContext.context,
                    workoutId = "$prefix-$i",
                    activityType = "Running",
                    source = "system-test",
                    start = UnixInstantDto(1_700_000_000_000L + i * 3_600_000L),
                    end = UnixInstantDto(1_700_000_000_000L + i * 3_600_000L + 1_800_000L),
                    durationSec = 1_800,
                ),
                null,
            )
            assertTrue(result is TResult.Success, "recordWorkout $prefix-$i failed: $result")
        }
    }

    @Test fun bulkWriteTask_collapsesBlocks_againstTheSameWritesRunBare() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = host.syncManager.currentOwnerId!!
            val count = 24

            // ---- Control: the same N writes with no task. One block per row. ----
            val beforeBare = blocksAccepted(system, ownerId)
            writeWorkouts(host, "bare", count)
            system.advanceUntil(timeout = 30.seconds) {
                blocksAccepted(system, ownerId) - beforeBare >= count
            }
            val bare = blocksAccepted(system, ownerId) - beforeBare

            // ---- The helper: identical writes, wrapped. ----
            val beforeBatched = blocksAccepted(system, ownerId)
            host.tenshuClient.withBulkWriteTask("test/bulk-write") {
                writeWorkouts(host, "batched", count)
            }
            // The lane seals on close (inside the helper), so the batch is already queued; what
            // remains is the push.
            //
            // ⚠⚠ Waited for **quiescence**, not for "> 0". A count-reaches-N predicate would
            // decide the answer in advance, and "> 0" is worse than it looks: a straggler from the
            // bare phase satisfies it while the batch is still queued, which would score the lane
            // as brilliant for the wrong reason. Stable-for-a-second is the only predicate here
            // that cannot flatter the result.
            var stable = 0
            var last = -1
            system.advanceUntil(timeout = 30.seconds) {
                val now = blocksAccepted(system, ownerId) - beforeBatched
                if (now == last && now > 0) stable++ else stable = 0
                last = now
                stable >= 10
            }
            val batched = blocksAccepted(system, ownerId) - beforeBatched
            println("C4 withBulkWriteTask: $count writes → $batched block(s) batched vs $bare bare")

            assertTrue(
                bare >= count,
                "control: $count bare writes should cost at least $count blocks, got $bare",
            )
            assertTrue(
                batched * 2 <= bare,
                "the lane must collapse the batch: $count writes cost $batched block(s) batched " +
                    "vs $bare bare — no meaningful collapse",
            )

            // ⚠ And the rows must actually be there: a lane that dropped its deltas would score
            // beautifully on the count above.
            repeat(count) { i ->
                assertTrue(
                    host.executionContext.database.getData(
                        org.tenshu.core.entity.DataId.StringDataId.create(
                            type = Workout.ID,
                            keyValue = "batched-$i",
                        ),
                    ) != null,
                    "batched-$i must exist locally after the task closed",
                )
            }
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠ A bulk task must not swallow a failure: when the block throws, the caller sees the throw
     * (the task is failed proxy-side on the way out). Without this, an import that died halfway
     * would look like it had completed.
     */
    @Test fun aThrowingBlockPropagates() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val thrown = try {
                host.tenshuClient.withBulkWriteTask("test/failing") {
                    error("import blew up")
                }
                null
            } catch (t: Throwable) {
                t
            }
            assertEquals("import blew up", thrown?.message)
        } finally {
            system.shutdown()
        }
    }
}
