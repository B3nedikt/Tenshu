package org.tenshu.systemtest

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.DataId
import org.tenshu.routine.Routine
import org.tenshu.routine.client.getRoutines
import org.tenshu.routine.client.routineService
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.Trigger
import org.tenshu.routine.deleteRoutine
import org.tenshu.routine.saveRoutine
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * **A routine delete reaches every device** (`tech-debt.md` *"Deletes that emit no tombstone"*).
 *
 * ⚠⚠ `SyncObserver.onDataDeleted` is a deliberate no-op: a delete leaves the device it was made on
 * only if its op emits a tombstone. `DeleteRoutine` emitted none, so the tablet kept the routine —
 * and, as an executor on the roster, could still fire it.
 *
 * Same shape as [CalendarDeleteSyncSystemTest]: the tablet must hold the row first (so its absence
 * later is the delete's doing), and the row must **stay** gone through a settle period. The row is
 * read straight off the tablet's store by key, not through any read path that might filter it.
 */
class RoutineDeleteSyncSystemTest {

    @Test
    fun aDeletedRoutineLeavesThePeer() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            val tablet = system.client("tablet", TestClientRole.JoiningDevice, pollingIntervalMs = 200)
            pair(system, host = phone, joiner = tablet)

            val program = JsonRoutineProgramCodec().encode(
                RoutineProgram(
                    trigger = Trigger.TimeOfDay(minuteOfDay = 6 * 60),
                    actions = listOf(Action.SetPower("lamp", on = true)),
                )
            )
            val service = phone.tenshuClient.routineService
            service.saveRoutine(
                routineId = ROUTINE,
                name = "Morning lights",
                timeZoneId = "Europe/Berlin",
                programJson = program.programJson,
                programVersion = program.programVersion,
                homeLatE5 = 5_252_000,
                homeLonE5 = 1_340_500,
            ).orFail("saveRoutine")
            awaitOrFail(system, "the tablet holds and lists the routine before the delete") {
                rowOn(tablet) != null && listedOn(tablet)
            }

            service.deleteRoutine(ROUTINE).orFail("deleteRoutine")

            awaitOrFail(system, "the delete reached the tablet") { rowOn(tablet) == null }
            // ⚠ And stays gone: a resurrection is a later tick's event.
            delay(SETTLE)
            assertNull(rowOn(tablet), "[tablet] the routine is still gone")
            // And the read path agrees — a listing holding a dead id would still show it in Home.
            assertFalse(listedOn(tablet), "[tablet] RoutineService.routines no longer lists the routine")
        } finally {
            system.shutdown()
        }
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private fun rowOn(client: TestClient) =
        client.executionContext.database.getData(
            DataId.StringDataId.create(context = client.executionContext.context, type = Routine.ID, keyValue = ROUTINE)
        )

    private suspend fun listedOn(client: TestClient) =
        client.tenshuClient.routineService.getRoutines().any { it.routineId.get() == ROUTINE }

    private suspend fun awaitOrFail(system: TestSystem, what: String, condition: suspend () -> Boolean) {
        try {
            system.advanceUntil(timeout = 30.seconds) { condition() }
        } catch (_: TimeoutCancellationException) {
            fail("Timed out waiting until $what")
        }
    }

    private fun TResult<*>.orFail(what: String) {
        assertTrue(this is TResult.Success<*>, "$what failed: $this")
    }

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.bootstrapAsHost(deviceName = "Phone")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Tablet",
            otherDevicePublicKey = parsed.hostPairingPublicKey,
        )
        val request = host.tenshuClient.syncService.getPendingPairingRequests()
            .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
        host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        system.advanceUntil(timeout = 60.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
    }

    private companion object {
        const val ROUTINE = "morning-lights"

        /** How long a state must hold before it is believed. ~15 ticks at 200 ms. */
        val SETTLE = 3.seconds
    }
}
