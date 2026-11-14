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
 *  4. Host removes B and **keeps** C (`admission-revocation-names-the-capability.md`
 *     §7j.10): only B's own key is revoked, and C — a member — admits **D**.
 *  5. The user unticks C after all → C is fully removed (its own key revoked, its
 *     registration gone, in the removal ledger).
 *
 * `runBlocking` rather than `runTest` for the same reason as the other
 * SystemTests — `ProxyGateway.call` deadlocks against the test dispatcher.
 *
 * ## Stepped
 *
 * The system is stepped (`docs/testing.md` §12): a device syncs only when the test ticks it. That
 * matters most to the two cases that need the host NOT to see C's re-root request. They used to
 * give the host a one-hour poll and assume it never ticked. But a polled scheduler ticks once on
 * `start()` and again on every local-save wake, so one tick after C's announcement reached the
 * relay flattened C's chain. That was *expected:<2> but was:<1>*, forced 3/3 by a single host
 * `drainNow()` (`docs/tech-debt.md`, closed members). Here a pairing ticks only the two devices
 * pairing ([pair]), so "the host never ingests C's announcement" holds by construction.
 * The full-lifecycle case, which needs the re-root, ticks every device until it lands.
 */
class DelegatedAdmissionSystemTest {

    @Test fun delegatedAdmission_fullLifecycle_admitThenRemoveTheAdmitter_keptAdmitteeStillAdmits(): Unit = runBlocking {
        val system = TestSystem.builder().stepped().build()
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
            system.syncUntil("C adopted the host's depth-1 re-root grant") {
                deviceC.syncManager.getAdmissionCertChain().size == 1
            }
            assertEquals(1, deviceC.syncManager.getAdmissionCertChain().size, "opportunistic re-root flattens C")

            // ---- 4. Host removes B, keeping C. ----
            // The host holds C's Device row (with its admittedByDeviceId lineage) — the input the
            // retired subtree strip walked. Wait for it, so the test proves the strip is gone rather
            // than merely unable to find C.
            val bId = deviceB.syncManager.currentDeviceId
            val cId = deviceC.syncManager.currentDeviceId
            system.syncUntil("the host holds B's and C's Device rows") {
                val view = host.tenshuClient.identityService.getDevices()
                view is TResult.Success &&
                    view.value!!.toList().map { it.deviceId.get() }.containsAll(setOf(bId, cId))
            }

            val revokeResult = host.tenshuClient.syncService.revokeDevice(bId)
            assertTrue(revokeResult is TResult.Success, "revokeDevice should succeed; got $revokeResult")

            assertEquals(
                setOf(bId),
                system.admissionRevocations.revocations(hostOwnerId).map { it.deviceId }.toSet(),
                "removal revokes B's own key only — C was kept, so C stays a member that can admit (§7j.10)",
            )

            // C — kept — admits D, end to end.
            pair(system, host = deviceC, joiner = deviceD, joinerName = "Device D")
            assertTrue(
                deviceD.syncManager.currentDeviceId in system.knownDevices.deviceIds(hostOwnerId),
                "a kept admittee of a removed device must still admit",
            )

            // ---- 5. The user unticks C after all: C is fully removed. ----
            val revokeC = host.tenshuClient.syncService.revokeDevice(cId)
            assertTrue(revokeC is TResult.Success, "revokeDevice(C) should succeed; got $revokeC")
            assertEquals(
                setOf(bId, cId),
                system.admissionRevocations.revocations(hostOwnerId).map { it.deviceId }.toSet(),
                "C's own key is revoked when C itself is removed",
            )
            system.syncUntil("C's removal reached the relay") {
                system.knownDevices.wasRemoved(hostOwnerId, cId) &&
                    cId !in system.knownDevices.deviceIds(hostOwnerId)
            }
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ §7j.10 at the relay, end to end: C still holds its **depth-2** chain `[C ← B ← A1]` when B
     * is removed and B's key revoked — the host is never ticked after pairing B, so it never sees
     * C's re-root request and nothing flattens C. The relay checks revocation on the approver alone, so C — a kept,
     * current member — still admits D. Before §7j.10 the revoked ancestor refused every approve C
     * signed.
     */
    @Test fun aKeptAdmitteeOnADeepChain_stillAdmits_afterItsAdmitterIsRemovedAndRevoked() = runBlocking {
        val system = TestSystem.builder().stepped().build()
        try {
            // The host is never ticked after its own pairing with B: it never ingests C's
            // announcement, so nothing re-roots C.
            val host = system.client("host", TestClientRole.RealmHost)
            val deviceB = system.client("device-b", TestClientRole.JoiningDevice)
            val deviceC = system.client("device-c", TestClientRole.JoiningDevice)
            val deviceD = system.client("device-d", TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host")
            val hostOwnerId = assertNotNull(host.syncManager.currentOwnerId)
            pair(system, host = host, joiner = deviceB, joinerName = "Device B")
            pair(system, host = deviceB, joiner = deviceC, joinerName = "Device C")
            val bId = deviceB.syncManager.currentDeviceId

            val revokeResult = host.tenshuClient.syncService.revokeDevice(bId)
            assertTrue(revokeResult is TResult.Success, "revokeDevice should succeed; got $revokeResult")
            assertEquals(
                setOf(bId),
                system.admissionRevocations.revocations(hostOwnerId).map { it.deviceId }.toSet(),
                "precondition: B's key is revoked at the relay",
            )
            val cChain = deviceC.syncManager.getAdmissionCertChain()
            assertEquals(2, cChain.size, "precondition: C's chain still runs through B")
            assertEquals(bId, cChain[1].deviceId)

            pair(system, host = deviceC, joiner = deviceD, joinerName = "Device D")
            assertEquals(2, deviceC.syncManager.getAdmissionCertChain().size, "C approved on the deep chain")
            assertTrue(
                deviceD.syncManager.currentDeviceId in system.knownDevices.deviceIds(hostOwnerId),
                "the relay must admit through a revoked ANCESTOR when the approver's own key is valid",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ §7j.10 fix round, the window: removing B strips B's own A33 key at the relay at once, but
     * B's tombstone (which unregisters it) lands on a later push. The window is held open
     * deterministically by landing the remover's master-signed key strip in the relay ledger
     * directly — exactly what `RevokeDevice`'s first relay call does — and never pushing the
     * tombstone, so B is still registered. A stolen B re-keys itself — mints `B(K')` under its revoked key and presents
     * `[B(K'), B(K_B)]` — and approves C. The relay refuses; C never registers. Own-key revocation
     * stops an approver immediately; removal completes when the tombstone unregisters it.
     */
    @Test fun aRevokedApproverInTheRemovalWindow_cannotReKeyItselfToAdmit() = runBlocking {
        val system = TestSystem.builder().stepped().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val deviceB = system.client("device-b", TestClientRole.JoiningDevice)
            val deviceC = system.client("device-c", TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host")
            val hostOwnerId = assertNotNull(host.syncManager.currentOwnerId)
            pair(system, host = host, joiner = deviceB, joinerName = "Device B")
            val bId = deviceB.syncManager.currentDeviceId

            // The remover's key strip, master-signed with REAL Ed25519 over the canonical bytes.
            val crypto = deviceB.koinApp.koin.get<org.tenshu.core.security.SyncCryptoManager>()
            val oldKey = assertNotNull(deviceB.syncManager.getAdmissionKeyPair())
            val master = assertNotNull(host.syncManager.currentMasterKeyPair)
            system.admissionRevocations.appendRevocation(
                org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry.RevocationRecord(
                    ownerId = hostOwnerId,
                    deviceId = bId,
                    epoch = 0L,
                    revokedAt = 1_000L,
                    signature = crypto.signEd25519(
                        master.privateKey,
                        org.tenshu.sync.common.AdmissionRevocation.message(hostOwnerId, bId, 0L, 1_000L, oldKey.publicKey),
                    ),
                    deviceA33PublicKey = oldKey.publicKey,
                ),
            )
            assertTrue(
                bId in system.knownDevices.deviceIds(hostOwnerId) && !system.knownDevices.wasRemoved(hostOwnerId, bId),
                "precondition: inside the window B is still registered and not yet in the removal ledger",
            )

            // The forged re-key, installed as B's own admission material.
            val oldChain = deviceB.syncManager.getAdmissionCertChain()
            val fresh = crypto.generateAdmissionKeyPair()
            val reKeyMessage = org.tenshu.sync.common.AdmissionCapabilityCert.message(
                ownerId = hostOwnerId, deviceA33PublicKey = fresh.publicKey, deviceId = bId, mintedAtEpoch = 0L,
            )
            val reKeyCert = org.tenshu.sync.common.AdmissionCapabilityCert(
                ownerId = hostOwnerId, deviceA33PublicKey = fresh.publicKey, deviceId = bId, mintedAtEpoch = 0L,
                signature = crypto.signEd25519(oldKey.privateKey, reKeyMessage),
            )
            deviceB.syncManager.installAdmissionMaterial(fresh, listOf(reKeyCert) + oldChain)

            val startResult = deviceB.tenshuClient.syncService.startPairing()
            assertTrue(startResult is TResult.Success, "startPairing on B should succeed; got $startResult")
            val bUri = PairingUri.parse(assertNotNull(deviceB.syncManager.getPairingString()))
            deviceC.tenshuClient.syncService.initiatePairing(
                ownerId = bUri.ownerId,
                pairingSecret = bUri.secret,
                deviceName = "Device C",
                otherDevicePublicKey = bUri.hostPairingPublicKey,
            )
            val pending = deviceB.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(pending is TResult.Success, "B (still registered) must read its pairing requests; got $pending")
            val cRequest = pending.value!!.toList().single()
            val approveResult = deviceB.tenshuClient.syncService.approvePairingRequest(cRequest.deviceId.get())
            assertTrue(approveResult is TResult.Failure, "the self-re-keyed approve must fail; got $approveResult")
            assertNull(
                system.knownDevices.getPublicKey(hostOwnerId, cRequest.deviceId.get()),
                "C must never register through a self-re-key",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * Full pairing handshake: [host] admits [joiner] (the PairingSystemTest steps).
     *
     * ⚠ Ticks [host] and [joiner] only, never the realm's other devices. The deep-chain cases rely
     * on that: pairing C through B must not tick the A1 host.
     */
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

        system.syncUntil("$joinerName is paired", devices = listOf(host, joiner)) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = uri.secret,
                otherDevicePublicKey = uri.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
    }
}
