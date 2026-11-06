package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.get
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.health.client.healthService
import org.tenshu.sync.GenericTaskSpec
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.register
import org.tenshu.sync.client.syncService
import org.tenshu.sync.client.taskService
import org.tenshu.sync.client.withTask
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * TS-4 (`core-background-processing.md` §10.6): the Fit-shaped bulk import as
 * ONE registered task over the REAL stack — register → `withTask`-tagged
 * `recordWorkout` constituent calls (the L2 envelope / in-process taskId
 * threading) → proxy saves join the task's sync lane → `complete()` seals the
 * batch → relay push/pull → the joiner ingests the `DeltaBatchPayload` and
 * converges on every workout. The wire twin of `TaskServiceOpsTest`.
 */
class TaskBatchedImportSystemTest {

    @Test fun taskWrappedBulkImport_batchesOnTheWire_andConvergesOnTheJoiner() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost, pollingIntervalMs = 1_000)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice, pollingIntervalMs = 1_000)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
            system.advanceUntil(timeout = 30.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            // ---- The Fit-shaped bulk import: one task, N constituent writes. ----
            val count = 30
            val task = host.tenshuClient.taskService.register(
                GenericTaskSpec.create(host.executionContext.context, "fit/health-import")
            )
            assertNotNull(task, "TaskService must be available on the host")

            withTask(task) {
                repeat(count) { i ->
                    val workout = Workout.create(
                        context = host.executionContext.context,
                        workoutId = "import-$i",
                        activityType = "Running",
                        source = "system-test",
                        start = UnixInstantDto(1_700_000_000_000L + i * 3_600_000L),
                        end = UnixInstantDto(1_700_000_000_000L + i * 3_600_000L + 1_800_000L),
                        durationSec = 1_800,
                    )
                    val result = host.tenshuClient.healthService.recordWorkout(workout, null)
                    assertTrue(result is TResult.Success, "recordWorkout $i should succeed; got $result")
                }
            }
            val done = task.complete()
            assertTrue(done is TResult.Success, "complete should succeed; got $done")

            // ---- Joiner converges on every workout via the batch block(s). ----
            fun joinerHas(i: Int): Boolean = joiner.executionContext.database.getData(
                org.tenshu.core.entity.DataId.StringDataId.create(
                    type = Workout.ID,
                    keyValue = "import-$i"
                )
            ) != null

            system.advanceUntil(timeout = 30.seconds) { (0 until count).all { joinerHas(it) } }
            assertEquals(
                count,
                (0 until count).count { joinerHas(it) },
                "all task-batched workouts must converge on the joiner"
            )
        } finally {
            system.shutdown()
        }
    }
}
