package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getDevices
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.revokeDevice
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The delegated device-admission authority track end-to-end
 * (`delegated-device-admission-authority.md` P1–P5), against the **real**
 * relay chain verification and **real Ed25519** — every unit test in the
 * track runs on stub crypto and a faked relay, so this is the first proof
 * that the client's canonical bytes, the blob-delivered A33 material, and the
 * relay's `AdmissionChainVerifier` walk actually agree:
 *
 *  1. Host pairs joiner **B** → B receives an A33 keypair + depth-1 chain via
 *     the encrypted blob (P3 minting) and never holds A1.
 *  2. **The resilience prize:** phrase-less B admits device **C** — the
 *     delegation is A33-signed, the chain rides the wire, and the relay
 *     verifies it to the realm master (P2) before registering C. C receives
 *     a depth-2 chain (Q1 chained).
 *  3. The P4 migration loop re-roots C live: C announces its deep chain, the
 *     A1-holding host grants a depth-1 replacement, C adopts it.
 *  4. Host removes B → master-signed revocations for the **whole lineage
 *     subtree** {B, C} land in the relay ledger (P5, Q3 conservative — C is
 *     revoked *despite* its re-rooted A1-direct chain, because the automatic
 *     re-root must not launder a removed admitter's lineage). C's subsequent
 *     attempt to admit **D** is rejected by the relay and D is never
 *     registered.
 *
 * `runBlocking` rather than `runTest` for the same reason as the other
 * SystemTests — `ProxyGateway.call` deadlocks against the test dispatcher.
 */
class DelegatedAdmissionSystemTest {

    @Test fun delegatedAdmission_fullLifecycle_admitThenSubtreeRevoke() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val deviceB = system.client("device-b", TestClientRole.JoiningDevice)
            val deviceC = system.client("device-c", TestClientRole.JoiningDevice)
            val deviceD = system.client("device-d", TestClientRole.JoiningDevice)

            // ---- 1. Host bootstraps + pairs B (the ordinary P3 owner path). ----
            host.bootstrapAsHost(deviceName = "Host")
            val hostOwnerId = assertNotNull(host.syncManager.currentOwnerId)
            pair(system, host = host, joiner = deviceB, joinerName = "Device B")

            assertNull(
                deviceB.syncManager.currentMasterKeyPair,
                "B is a paired joiner — it must never hold A1",
            )
            val bChain = deviceB.syncManager.getAdmissionCertChain()
            assertNotNull(deviceB.syncManager.getAdmissionKeyPair(), "B must receive its A33 via the blob")
            assertEquals(1, bChain.size, "owner-admitted joiners get a depth-1 chain")
            assertEquals(deviceB.syncManager.currentDeviceId, bChain[0].deviceId)

            // ---- 2. The resilience prize: phrase-less B admits C. ----
            // B's approve signs the delegation with its A33 and presents the chain;
            // the REAL relay walks it to the realm master with REAL Ed25519 before
            // registering C — the exact P2↔P3 seam no unit test exercises.
            pair(system, host = deviceB, joiner = deviceC, joinerName = "Device C")

            assertEquals(hostOwnerId, deviceC.syncManager.currentOwnerId)
            assertTrue(
                deviceC.syncManager.currentDeviceId in system.knownDevices.deviceIds(hostOwnerId),
                "the relay must have chain-verified and registered C",
            )
            assertEquals(
                2,
                deviceC.syncManager.getAdmissionCertChain().size,
                "delegate-admitted joiners get the admitter's chain plus one link (Q1 chained)",
            )

            // ---- 3. P4 re-root, live: C's deep chain announces; the A1-holding ----
            // host observes it on sync, grants a depth-1 replacement, C adopts it.
            system.advanceUntil(timeout = 20.seconds) {
                deviceC.syncManager.getAdmissionCertChain().size == 1
            }
            assertEquals(1, deviceC.syncManager.getAdmissionCertChain().size, "opportunistic re-root flattens C")

            // ---- 4. Host removes B → the whole lineage subtree loses minting power. ----
            // The host needs C's Device row (with its admittedByDeviceId lineage) to
            // walk the subtree — wait for it to sync in.
            val bId = deviceB.syncManager.currentDeviceId
            val cId = deviceC.syncManager.currentDeviceId
            system.advanceUntil(timeout = 20.seconds) {
                val view = host.tenshuClient.identityService.getDevices()
                view is TResult.Success &&
                    view.value!!.toList().map { it.deviceId.get() }.containsAll(setOf(bId, cId))
            }

            val revokeResult = host.tenshuClient.syncService.revokeDevice(bId)
            assertTrue(revokeResult is TResult.Success, "revokeDevice should succeed; got $revokeResult")

            assertEquals(
                setOf(bId, cId),
                system.admissionRevocations.revocations(hostOwnerId).map { it.deviceId }.toSet(),
                "removal must master-sign revocations for B AND its lineage subtree (Q3 conservative) — " +
                    "C is revoked despite its re-rooted A1-direct chain",
            )

            // C tries to admit D — the relay must now reject C's capability.
            val startResult = deviceC.tenshuClient.syncService.startPairing()
            assertTrue(startResult is TResult.Success, "startPairing on C should succeed; got $startResult")
            val cUri = PairingUri.parse(assertNotNull(deviceC.syncManager.getPairingString()))
            deviceD.tenshuClient.syncService.initiatePairing(
                ownerId = cUri.ownerId,
                pairingSecret = cUri.secret,
                deviceName = "Device D",
                otherDevicePublicKey = cUri.hostPairingPublicKey,
            )
            val dRequest = (deviceC.tenshuClient.syncService.getPendingPairingRequests()
                as TResult.Success).value!!.toList().single()
            val dDeviceId = dRequest.deviceId.get()

            val approveResult = deviceC.tenshuClient.syncService.approvePairingRequest(dDeviceId)
            assertTrue(
                approveResult is TResult.Failure,
                "a revoked admitter's approve must fail (relay rejects the chain); got $approveResult",
            )
            assertNull(
                system.knownDevices.getPublicKey(hostOwnerId, dDeviceId),
                "D must never be registered — the revoked subtree's minting power is gone",
            )
        } finally {
            system.shutdown()
        }
    }

    /** Full pairing handshake: [host] admits [joiner] (the PairingSystemTest steps). */
    private suspend fun pair(
        system: TestSystem,
        host: TestClient,
        joiner: TestClient,
        joinerName: String,
    ) {
        val startResult = host.tenshuClient.syncService.startPairing()
        assertTrue(startResult is TResult.Success, "startPairing should succeed; got $startResult")
        val uri = PairingUri.parse(assertNotNull(host.syncManager.getPairingString()))

        val initResult = joiner.tenshuClient.syncService.initiatePairing(
            ownerId = uri.ownerId,
            pairingSecret = uri.secret,
            deviceName = joinerName,
            otherDevicePublicKey = uri.hostPairingPublicKey,
        )
        assertTrue(initResult is TResult.Success, "initiatePairing should succeed; got $initResult")

        val pending = (host.tenshuClient.syncService.getPendingPairingRequests()
            as TResult.Success).value!!.toList()
        val request = pending.single { it.deviceName.get() == joinerName }

        val approveResult = host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        assertTrue(approveResult is TResult.Success, "approve of $joinerName should succeed; got $approveResult")

        system.advanceUntil(timeout = 15.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = uri.secret,
                otherDevicePublicKey = uri.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
    }
}
