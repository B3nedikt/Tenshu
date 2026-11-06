package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getDevices
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the production bug "after pairing the Desktop does not
 * show up under the realm devices on the phone (joiner)."
 *
 * The existing `PairingSystemTest.pairedDevices_eventuallySeeEachOther…` uses
 * 100ms polling on both clients, which masks a real timing race. Production
 * uses 30s. The failure mode:
 *
 *   1. Host runs `bootstrapAsHost` → saves its `Device` entity → `SyncObserver`
 *      adds the block to the host's local pending queue, hlc.physicalTime = T_h
 *      (host's wall clock at CreateRealm — earlier than pairing).
 *   2. User pairs the phone before host's scheduler has fired its drain
 *      (host scheduler is `delay(30s)`). Pairing completes via direct relay
 *      calls (not scheduler-driven), so the joiner gets the master key.
 *   3. Joiner's fast scheduler fires:
 *        - drainPendingBlocks() pushes the joiner's own `Device`
 *          (hlc.physicalTime = T_j, where T_j > T_h).
 *        - pullChanges(sinceTimestamp=0) → relay returns only joiner's
 *          self-push; host's `Device` hasn't reached the mailbox yet.
 *        - Joiner advances `lastSyncTimestamp = T_j`.
 *   4. Host's scheduler fires (some time later) → finally pushes the host's
 *      `Device` (hlc.physicalTime = T_h, unchanged from when the block was
 *      generated).
 *   5. Joiner's next pull uses sinceTimestamp = T_j; relay's filter
 *      `block.hlc.physicalTime > T_j` excludes the host's block (T_h < T_j).
 *      Joiner never sees the host's `Device`.
 *
 * Modelled here by giving the host a long polling interval relative to the
 * joiner. The joiner's first cycle is guaranteed to pull before the host has
 * drained.
 *
 * On master this assertion times out — the joiner's `lastSyncTimestamp` has
 * already advanced past the host's `hlc.physicalTime`. After the fix, the
 * cursor-advancement no longer prevents older blocks from being delivered.
 */
class JoinerMissesHostDeviceSystemTest {

    @Test fun joinerSeesHostDevice_evenWhenHostPushesLate() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            // Host polls slow enough that the joiner's first pull will happen
            // first (the joiner polls at 100ms; the host first drains its
            // CreateRealm-time block after 3s).
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 3_000
            )
            val joiner = system.client(
                deviceId = "joiner",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 100
            )

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
            system.advanceUntil(timeout = 15.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            val hostDeviceId = host.syncManager.currentDeviceId
            val joinerDeviceId = joiner.syncManager.currentDeviceId

            // Within 30s the host's slow scheduler will have fired at least
            // once, pushing its Device block to the relay. After the fix,
            // the joiner's subsequent pulls will discover it; on master, the
            // joiner's already-advanced cursor filters it out.
            system.advanceUntil(timeout = 30.seconds) {
                val joinerView = joiner.tenshuClient.identityService.getDevices()
                joinerView is TResult.Success &&
                    joinerView.value!!.toList()
                        .map { it.deviceId.get() }
                        .containsAll(setOf(hostDeviceId, joinerDeviceId))
            }

            val joinerIds = (joiner.tenshuClient.identityService.getDevices()
                as TResult.Success).value!!.toList().map { it.deviceId.get() }
            assertEquals(
                listOf(hostDeviceId, joinerDeviceId).sorted(),
                joinerIds.sorted(),
                "Joiner should see both devices once the host's slow scheduler " +
                    "has drained, regardless of the order pulls happened in; " +
                    "got $joinerIds"
            )
        } finally {
            system.shutdown()
        }
    }
}
