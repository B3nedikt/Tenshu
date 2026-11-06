package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.SyncHealthStatus
import kotlinx.coroutines.flow.first
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.toStamp
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.revokeDevice
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The recovery path: what happens to a device the relay has stopped recognising, and to the
 * instructions that were queued while it could not push.
 *
 * ⚠⚠ **Why this class exists.** The failures here are compositions no unit test can see: a `401`
 * marker missing from the route that carries the traffic leaves the backoff unarmed, which makes the
 * self-repair unreachable; a re-admitted capability can be born revoked; a shared-realm pull can
 * swallow its failure. Each part passes its unit tests. What breaks is the *composition* of a client
 * that cannot authenticate, a relay that refuses it, and a queue that outlives both.
 *
 * This harness is the right place for that, because it runs the **real relay app** against clients
 * on the **real `RestSyncRelay`** — so a refusal here is a genuine HTTP `401` produced by the
 * relay's own auth, not a fake's `TResult.Failure`.
 *
 * ⚠ What it still cannot see, stated so nobody reads a green run as more than it is:
 *  - `TestApplication` speaks **HTTP/1.1**, so it sends a reason phrase. A detector that matches
 *    `"401 Unauthorized"` (which h2 never sends) **passes** here. Only the live relay
 *    behind Caddy reproduces that.
 *  - Nothing here exercises a device build, a scan, or an OS: a headless emulator or an iPhone
 *    several releases behind is invisible to every test here.
 */
class RelayAmnesiaRepairSystemTest {

    /**
     * ⭐ A device that has been revoked and re-admitted must be able to admit others — its new
     * capability must not be **born revoked**.
     *
     * ⚠⚠ The trap is that the epoch does not always move. `AdmissionChainVerifier.isRevoked`
     * supersedes a cert when `revocation.epoch >= cert.mintedAtEpoch`, and both sides read
     * `currentRealmEpoch` — the count of `Device` tombstones. Revoking advances it the FIRST time
     * by adding one; re-revoking a device that already has a tombstone adds none, so the counter
     * stands still and the next mint lands AT the revoked epoch. `N >= N`: superseded at the instant
     * it is signed.
     *
     * The symptom: a device revoked and re-admitted at the same epoch syncs perfectly and answers
     * `403 Admission capability has been revoked` to every attempt to add a device, permanently.
     * Revoking therefore seeds the epoch past the revocation it just wrote.
     */
    @Test
    fun aReAdmittedDevice_canAdmitOthers_itsCapabilityIsNotBornRevoked(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val second = system.client("second", TestClientRole.JoiningDevice)
            val third = system.client("third", TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)

            // ⚠⚠ Under option F (`admission-revocation-names-the-capability.md` §7c) a removed
            // identity never returns, so the relay refuses a re-pair of the SAME deviceId; that
            // refusal is `aRemovedDeviceCannotRejoinUnderTheSameIdentity`.
            //
            // ⭐ After revocations have advanced the epoch, a device that legitimately holds a
            // capability must be able to admit others — its cert must not be born revoked. The
            // device that does so is a FRESH identity, which is how a user recovers.
            pairJoiner(system, host, second)
            val secondDeviceId = assertNotNull(second.syncManager.currentDeviceId)
            host.tenshuClient.syncService.revokeDevice(secondDeviceId)

            // A fresh identity, admitted after the epoch moved. Its cert is minted at the current
            // epoch, which is what the revocation's epoch seed exists to get right.
            val fourth = system.client("fourth", TestClientRole.JoiningDevice)
            pairJoiner(system, host, fourth)

            val thirdDeviceId = admitVia(system, admitter = fourth, joiner = third, name = "Third Phone")

            assertTrue(
                thirdDeviceId in system.knownDevices.deviceIds(ownerId),
                "a device admitted after a revocation must be able to admit others. If this fails " +
                    "the relay refused its capability as revoked — it can sync perfectly and still " +
                    "never add a device, with no in-product way out",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⭐ The other half: the relay forgets a **joiner**, which holds no master identity and
     * therefore *cannot* repair itself.
     *
     * ⚠⚠ The requirement is not that it recovers — it can't, by design, and that is the capability
     * boundary working (T-K-04: a device that could re-admit itself could launder away its own
     * revocation). The requirement is that it **says so**: `CONNECTIVITY_DEVICE_UNRECOGNIZED`, not
     * "offline". That distinction is the whole difference between a user who re-pairs and a user
     * who checks their wifi.
     *
     * ⚠ Deliberately asserts the joiner does NOT re-register. A joiner that repaired itself here
     * would mean the A1 requirement had been lost somewhere, which is a security regression, not a
     * convenience.
     *
     * ⚠⚠ **Known limit, measured not assumed.** This pins that the state is *reached*, not *which
     * path* reached it. `registrationBackoff.onRejected()` has **three** call sites — the pull
     * classification and two push-drain paths — and a joiner pushes its own `Device` row, so the
     * backoff still arms with the pull classification deleted: verified, this test stays green with
     * `noteRelayFailure`'s `isRelayUnauthorized` branch neutered. A 401 storm is a **pull**
     * problem, so do not read this as covering it; that is
     * `RestSyncRelayUnauthorizedContractTest` (producer) and
     * `unauthorizedPull_stopsPullingInsideTheWindow…` (scheduler). What this test does discriminate
     * on is the health mapping — neutering `deviceUnrecognized` turns it red.
     */
    @Test
    fun aJoinerTheRelayHasForgotten_cannotRepairItself_andSaysSoRatherThanBlamingTheNetwork(): Unit =
        runBlocking {
            val system = TestSystem.builder().build()
            try {
                val host = system.client("host", TestClientRole.RealmHost)
                val joiner = system.client("joiner", TestClientRole.JoiningDevice)
                host.bootstrapAsHost(deviceName = "Host Tablet")
                val ownerId = assertNotNull(host.syncManager.currentOwnerId)
                val joinerDeviceId = pairJoiner(system, host, joiner)

                system.knownDevices.revoke(ownerId, joinerDeviceId)

                // The joiner must reach the "not recognised" state on its own, from its own traffic.
                system.advanceUntil(timeout = 30.seconds) {
                    joiner.tenshuClient.syncService.syncHealth().first()?.connectivity?.get() ==
                        SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED
                }

                val connectivity =
                    joiner.tenshuClient.syncService.syncHealth().first()?.connectivity?.get()
                assertEquals(
                    SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED,
                    connectivity,
                    "a refused joiner must report that the relay does not recognise it. Reporting " +
                        "offline instead sends the user to check a working network and hides an " +
                        "identity problem while the client storms the relay",
                )

                assertTrue(
                    joinerDeviceId !in system.knownDevices.deviceIds(ownerId),
                    "a joiner holds no master identity, so it must NOT be able to re-register " +
                        "itself — if it could, a revoked device could launder away its own " +
                        "revocation (threat model T-K-04)",
                )
            } finally {
                system.shutdown()
            }
        }

    /**
     * ⭐ The relay forgets an **A1 holder**: it must notice, repair itself, and the repaired
     * admission must carry a stamp.
     *
     * Three mechanisms compose here and none of them can be observed alone:
     *  - the `401` must be **classified** as an identity rejection rather than an outage, because
     *    that classification is the only thing that arms the backoff;
     *  - the probing boundary must then run the **repair** — which is reachable *only* through that
     *    classification, so a broken detector silently disables a working repair;
     *  - the repair goes through `reregisterThisDevice`, one of the four routes that carry an
     *    **admission stamp**, so a successful repair must leave one behind.
     *
     * ⚠ If step one is inert, the device storms the relay with `401`s and never repairs.
     */
    @Test
    fun anA1Holder_theRelayHasForgotten_reRegistersItself_withAStamp(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)
            val hostDeviceId = assertNotNull(host.syncManager.currentDeviceId)

            assertTrue(
                hostDeviceId in system.knownDevices.deviceIds(ownerId),
                "precondition: the host must be registered before the relay forgets it",
            )

            // The relay forgets this device — a wipe, a restore from an older backup, an operator
            // reset. Everything the client sends now comes back `401`.
            system.knownDevices.revoke(ownerId, hostDeviceId)
            assertTrue(hostDeviceId !in system.knownDevices.deviceIds(ownerId))

            // No user action, no restart: the scheduler's own probing boundary should do this.
            system.advanceUntil(timeout = 30.seconds) {
                hostDeviceId in system.knownDevices.deviceIds(ownerId)
            }

            assertTrue(
                hostDeviceId in system.knownDevices.deviceIds(ownerId),
                "an A1 holder must re-register itself after the relay forgets it — without this " +
                    "the device 401s forever with no user-visible cause",
            )
            assertNotNull(
                system.knownDevices.admittedAt(ownerId, hostDeviceId),
                "the repaired admission must carry a stamp: `reregisterThisDevice` is one of the " +
                    "four stamped routes, and without it the relay has nothing to compare a stale " +
                    "retirement against — the repair would hand back a device the next queued " +
                    "tombstone can drop again",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⭐ Pins the stale-retirement guard end to end: a retirement authored while the author could
     * not push must NOT revoke a device that has been legitimately re-admitted since.
     *
     * Delivery order is not authorship order. The tombstone below is written at HLC `T2`, sits in
     * the client's durable queue because the relay refuses its author, and is delivered only after
     * the target has been re-admitted at `T3 > T2`. A relay that applied `revokedDeviceId` on
     * arrival, comparing nothing, would drop the freshly re-paired device.
     */
    @Test
    fun aQueuedRetirement_doesNotRevokeADeviceReAdmittedAfterItWasAuthored() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)
            val joinerDeviceId = pairJoiner(system, host, joiner)

            val admittedFirst = assertNotNull(
                system.knownDevices.admittedAt(ownerId, joinerDeviceId),
                "the approving client must stamp the admission (the guard's client half) — without a " +
                    "stamp the relay has nothing to compare and this whole test is vacuous",
            )

            // Captured before the revoke: the registry drops the row, key and all.
            val joinerKey = assertNotNull(system.knownDevices.getPublicKey(ownerId, joinerDeviceId))
            // ⚠ `host.deviceId` is the harness's LABEL; the relay knows the device by the id the
            // SyncManager minted. Using the label silently looks up nothing.
            val hostDeviceId = assertNotNull(host.syncManager.currentDeviceId)
            val hostKey = assertNotNull(system.knownDevices.getPublicKey(ownerId, hostDeviceId))

            // ── the device the relay forgets is the AUTHOR, so its retirement cannot be pushed ──
            //
            // The queue is client-side, so wiping the relay does not empty it. The instruction
            // outlives the server that would have
            // executed it.
            system.knownDevices.revoke(ownerId, hostDeviceId)

            host.tenshuClient.syncService.revokeDevice(joinerDeviceId)

            // ⚠⚠ The tombstone mints its OWN stamp: `SyncObserver.emit` calls
            // `hlcManager.tick()` when it builds the `DeleteBlock`. So a stamp captured *before*
            // this call is NOT the quantity the guard reads; it is at least one tick early. The
            // re-admission could then land BETWEEN the captured stamp and the real tombstone: the
            // precondition passes, the guard correctly revokes, and the test reports a guard
            // failure under load.
            //
            // Ticking the host's clock again after the emit gives a floor that is provably later
            // than whatever the tombstone got, monotonically and without reaching into the pending
            // queue to read it.
            val afterRetirement = host.syncManager.hlcManager.tick()
            val retirementFloor = afterRetirement.toStamp()

            // The retirement is authored and durable, but its push is refused, so the relay has
            // not acted on it. ⚠ This assertion is load-bearing for the whole test: if the
            // tombstone reached the relay HERE, the re-admission below would come after the
            // revocation and the ordering the guard exists for would never be exercised.
            assertTrue(
                joinerDeviceId in system.knownDevices.deviceIds(ownerId),
                "the retirement must not reach the relay while its author is refused",
            )

            // ── the target is re-admitted, later ──
            //
            // A fresh admission at a strictly later HLC. On the real system this is a re-pair
            // hours after the retirement was authored; here it is the same relay API the approve
            // path calls, stamped from the joiner's own clock so the ordering is real rather than
            // hand-written.
            // ⚠⚠ DERIVED from the retirement floor, not raced against it. `update` returns a
            // stamp strictly greater than its argument in every branch, so
            // `readmit > retirementFloor > tombstone` holds BY CONSTRUCTION and the ordering this
            // test exists to exercise always exists. Ticking the joiner's own clock instead leaves
            // the ordering to whichever millisecond each `tick()` lands in, which fails under load.
            val readmitStamp = joiner.syncManager.hlcManager.update(afterRetirement).toStamp()
            assertTrue(
                readmitStamp > admittedFirst,
                "the re-admission must be later than the first, or the test proves nothing",
            )
            // An invariant rather than a coin flip: if this fails, `HybridLogicalClock.update`
            // stopped returning a stamp strictly greater than its argument, which is a real
            // regression in HLC ordering and not a flaky precondition. Do not "fix" it by
            // re-running.
            assertTrue(
                readmitStamp > retirementFloor,
                "INVARIANT: update() must return a stamp strictly later than its argument — " +
                    "re-admission $readmitStamp vs retirement floor $retirementFloor",
            )
            system.knownDevices.register(
                ownerId = ownerId,
                deviceId = joinerDeviceId,
                publicKey = joinerKey,
                admittedAt = readmitStamp,
            )

            // ── the host recovers, and its queue drains ──
            system.knownDevices.register(
                ownerId = ownerId,
                deviceId = hostDeviceId,
                publicKey = hostKey,
                admittedAt = null,
            )
            // ⚠⚠ Wait for the tombstone to ACTUALLY ARRIVE, and assert it did: a wait on a
            // condition that is true immediately never drains, and passes with the relay guard
            // deleted. The mailbox is the honest anchor: the relay stores and serves a stale tombstone either
            // way (only the registry revocation is skipped), so its presence here means delivery
            // without presuming the outcome.
            system.advanceUntil(timeout = 30.seconds) {
                system.syncMailbox.getTombstones(ownerId, 0L, Int.MAX_VALUE).tombstones.isNotEmpty()
            }
            assertTrue(
                system.syncMailbox.getTombstones(ownerId, 0L, Int.MAX_VALUE).tombstones.isNotEmpty(),
                "the queued retirement must reach the relay — if it never arrives this test is " +
                    "vacuous and would pass with the guard removed",
            )

            // ⭐ THE assertion. The retirement was authored BEFORE the re-admission, so the relay
            // must refuse it and the re-paired device must survive.
            assertTrue(
                joinerDeviceId in system.knownDevices.deviceIds(ownerId),
                "a retirement authored at or before $retirementFloor must NOT revoke a device re-admitted at " +
                    "$readmitStamp — without the stale-retirement guard the device is dropped and " +
                    "401s forever with no way back",
            )
            assertTrue(
                system.knownDevices.admittedAt(ownerId, joinerDeviceId) == readmitStamp,
                "the re-admission's stamp must still stand — a refused tombstone changes nothing",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * Option F (`admission-revocation-names-the-capability.md` §7c): a device the owner removed cannot
     * rejoin under the **same identity**. End-to-end over the real relay routes, because the rule
     * is only worth anything where it is enforced.
     *
     * ⚠⚠ The counterpart matters as much as the rule: rejoining with a **new** identity must still
     * work, or "removed" would mean "that hardware is finished" rather than "that identity is". On
     * Android and desktop a new identity comes from clearing app data; on iOS the Keychain outlives
     * the app, so the new identity comes from "Start fresh".
     */
    @Test
    fun aRemovedDeviceCannotRejoinUnderTheSameIdentity(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val second = system.client("second", TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)

            pairJoiner(system, host, second)
            val removedDeviceId = assertNotNull(second.syncManager.currentDeviceId)
            assertTrue(removedDeviceId in system.knownDevices.deviceIds(ownerId), "precondition")

            host.tenshuClient.syncService.revokeDevice(removedDeviceId)

            // ⚠ `revokeDevice` only QUEUES the tombstone — the ledger is written when the relay
            // APPLIES it (`revokeIfNotStale`), which needs a push. Without the wait the refusal
            // below could pass for the wrong reason, "refused" being indistinguishable from
            // "never arrived".
            system.advanceUntil(timeout = 30.seconds) {
                system.knownDevices.wasRemoved(ownerId, removedDeviceId)
            }
            assertTrue(
                system.knownDevices.wasRemoved(ownerId, removedDeviceId),
                "the removal must reach the ledger, or the refusal below proves nothing",
            )
            assertFalse(
                removedDeviceId in system.knownDevices.deviceIds(ownerId),
                "and the registration is gone",
            )

            // ⭐ A fresh identity still joins — "removed" is about the identity, not the hardware.
            // ⚠ Ordered BEFORE the refused attempt: a refused re-pair leaves its pending request
            // on the host (the relay rejects at registration, after the request was posted), and
            // `pairJoiner` asserts a single pending. That leftover is worth knowing about in its
            // own right — see §7c.6 — but it is not what this test is about.
            val third = system.client("third", TestClientRole.JoiningDevice)
            pairJoiner(system, host, third)
            assertTrue(
                assertNotNull(third.syncManager.currentDeviceId) in
                    system.knownDevices.deviceIds(ownerId),
                "a NEW identity must still be admissible, or removal would end the hardware",
            )

            // ⭐⭐ THE assertion: actually ATTEMPT the rejoin. That the ledger recorded and that a
            // fresh device joins are both true with the relay guard deleted. A test named for a
            // refusal must trigger one.
            host.tenshuClient.syncService.startPairing()
            val reparse = PairingUri.parse(assertNotNull(host.syncManager.getPairingString()))
            second.tenshuClient.syncService.initiatePairing(
                ownerId = reparse.ownerId,
                pairingSecret = reparse.secret,
                deviceName = "Removed Phone",
                otherDevicePublicKey = reparse.hostPairingPublicKey,
            )
            val pendingAgain = assertNotNull(
                (host.tenshuClient.syncService.getPendingPairingRequests() as TResult.Success).value,
            ).toList()
            // The host may approve — the RELAY is what refuses, which is the layer under test.
            pendingAgain.forEach { host.tenshuClient.syncService.approvePairingRequest(it.deviceId.get()) }

            assertFalse(
                removedDeviceId in system.knownDevices.deviceIds(ownerId),
                "a removed identity must not be re-registered by the relay, however the pairing " +
                    "flow is driven — this is option F's whole promise",
            )
        } finally {
            system.shutdown()
        }
    }

    /** The real handshake, lifted from [PairingSystemTest] so this class tests recovery, not pairing. */
    private suspend fun pairJoiner(system: TestSystem, host: TestClient, joiner: TestClient): String {
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(assertNotNull(host.syncManager.getPairingString()))
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Joiner Phone",
            otherDevicePublicKey = parsed.hostPairingPublicKey,
        )
        val pending = assertNotNull(
            (host.tenshuClient.syncService.getPendingPairingRequests() as TResult.Success).value,
        ).toList()
        val joinerDeviceId = pending.single().deviceId.get()
        host.tenshuClient.syncService.approvePairingRequest(joinerDeviceId)
        system.advanceUntil(timeout = 15.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
        return joinerDeviceId
    }

    /** [admitter] (not the A1 holder) admits [joiner] — the delegated-admission path. */
    private suspend fun admitVia(
        system: TestSystem,
        admitter: TestClient,
        joiner: TestClient,
        name: String,
    ): String {
        admitter.tenshuClient.syncService.startPairing()
        val uri = PairingUri.parse(assertNotNull(admitter.syncManager.getPairingString()))
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = uri.ownerId,
            pairingSecret = uri.secret,
            deviceName = name,
            otherDevicePublicKey = uri.hostPairingPublicKey,
        )
        val pending = assertNotNull(
            (admitter.tenshuClient.syncService.getPendingPairingRequests() as TResult.Success).value,
        ).toList()
        val deviceId = pending.single { it.deviceName.get() == name }.deviceId.get()
        admitter.tenshuClient.syncService.approvePairingRequest(deviceId)
        // Best-effort: the assertion is on the relay's registry, not on this poll completing.
        // ⚠ The joiner must FETCH its response: the relay registers it on delivery (peek), not on
        // the admitter's post, so an admission nobody fetched leaves no registered device.
        runCatching {
            system.advanceUntil(timeout = 15.seconds) {
                joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = uri.secret,
                    otherDevicePublicKey = uri.hostPairingPublicKey,
                )
                deviceId in system.knownDevices.deviceIds(assertNotNull(admitter.syncManager.currentOwnerId))
            }
        }
        return deviceId
    }

}
