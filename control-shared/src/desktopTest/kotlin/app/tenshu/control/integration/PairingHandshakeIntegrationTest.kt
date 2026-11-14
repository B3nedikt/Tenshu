package app.tenshu.control.integration

import app.tenshu.control.data.SyncRepository
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import app.tenshu.control.sync.HostPairingMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.MockSyncRelay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The join-direction handshake end to end on the host: real proxy, real ops, [MockSyncRelay][org.tenshu.sync.proxy.MockSyncRelay]
 * for the wire, a simulated Device B.
 *
 * ⚠⚠ Deterministic because **nothing here is sampled against a clock**. Every step awaits the
 * monitor's own signal ([HostPairingMonitor.pairingComplete] for the end of the handshake), and
 * the one interleaving that decides the outcome — a poll asked while the session is live and
 * answered after the approval completed — is *arranged* rather than left to timing:
 * [PollHoldingSyncRepository] holds that poll's answer until completion has been asserted, and the
 * monitor runs on a serial dispatcher, so a no-op queued behind the released answer runs only once
 * the monitor has finished with it.
 *
 * ⚠ Real time, so nothing here may simply wait. That is why the relay runs without its simulated
 * latency and the monitor polls every [POLL_INTERVAL_MS] rather than every 2 s. Neither decides
 * what the test observes, only how long it takes. A test that can run under `runTest` gets the
 * same effect for free, on virtual time (`docs/testing-flakes.md` §6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingHandshakeIntegrationTest {

    // Serial, so the test can queue behind the monitor's work (see the class KDoc).
    private val monitorDispatcher = Dispatchers.Default.limitedParallelism(1)

    // Drives the HostPairingMonitor's poll loop (control-pairing-ux.md Phase 1); cancelled
    // in tearDown so the observer stops polling against a dead Koin.
    private val monitorScope = CoroutineScope(SupervisorJob() + monitorDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Default)
        startAppIntegrationKoin(
            clientId = "app.tenshu.control.test",
            additionalModules = listOf(
                module { single<SyncRelay> { MockSyncRelay(get(), simulateLatency = false) } },
            ),
        )
    }

    @After
    fun tearDown() {
        // Cancel the monitor's still-running poll coroutine before stopKoin/resetMain —
        // otherwise it keeps polling against a now-dead Koin and leaks uncaught exceptions.
        monitorScope.cancel()
        stopAppIntegrationKoin()
        Dispatchers.resetMain()
    }

    // NB: cannot use `runTest` here — calls into TenshuClient/SyncRelay traverse
    // ProxyGateway.call which uses runBlocking internally, deadlocking against a
    // TestDispatcher. Plain runBlocking over real time, awaiting signals (class KDoc).
    @Test
    fun testFullPairingHandshakeFlow() = runBlocking {
        GlobalContext.get().get<TenshuClient>()
        val syncRepository = PollHoldingSyncRepository(GlobalContext.get().get<SyncRepository>())
        val relay = GlobalContext.get().get<SyncRelay>()
        val syncManager = GlobalContext.get().get<org.tenshu.sync.common.SyncManager>()

        // SyncManager needs derived keys before approve can encrypt the master key. In
        // the real app this happens via PairingWizardViewModel during initial setup; in
        // an integration test we wire it manually with a freshly-generated 24-word
        // mnemonic — `DefaultSyncCryptoManager.deriveMasterKeyPair` now decodes the
        // mnemonic via `Bip39.decode` (Phase 4 slice A), so the previous "mock phrase"
        // string would throw `Bip39InvalidMnemonicException`.
        syncManager.initialize(org.tenshu.core.common.util.Bip39.generateMnemonic())

        // The host-side poll + pairing state now live in the process-lifetime
        // HostPairingMonitor (control-pairing-ux.md Phase 1), so the handshake is driven
        // through it rather than the (now UI-only) SyncDashboardViewModel.
        val monitor = HostPairingMonitor(syncRepository, scope = monitorScope, pollIntervalMs = POLL_INTERVAL_MS)

        // 2. Device A starts pairing
        monitor.startPairing()
        awaitStep("the host session to arm") { monitor.isPairing.first { it } }
        val pairingString = monitor.pairingString.value
        assertNotNull(pairingString)

        // Extract the secret from the canonical join URI (tenshu://join?o=…&d=…&k=…&s=…)
        // via the shared parser — the same code path a real joiner uses.
        val parsed = org.tenshu.core.common.util.PairingUri.parse(pairingString)
        check(parsed is org.tenshu.core.common.util.ParsedPairingUri.Join) {
            "getPairingString must emit a canonical join URI, got: $pairingString"
        }
        val secret = parsed.pairingSecret.decodeToString()

        // 3. Device B (simulated) posts a pairing request to the relay's pairing
        // mailbox. In the new flow Device B's public key is registered on the relay
        // by Device A's ApprovePairingRequest (master delegation in
        // POST /sync/pairing/response); the device's own Device entity is self-
        // pushed by GetPairingStatus once the master key arrives. The legacy
        // `IdentityService.pairDevice` step that used to live here is gone.
        val deviceBName = "New Tablet"
        // Phase 4 slice B: Device A's `GetPendingPairingRequests` derives a
        // shared secret with this key via real X25519 ECDH. Generate a real
        // 32-byte X25519 public key so the ECDH succeeds; under slice A's
        // XOR-padded stub any 3-byte fixture worked.
        val deviceBKeyPair = org.tenshu.core.common.security.BouncyCastleX25519Backend()
            .generateKeyPair()
        val deviceBPublicKey = deviceBKeyPair.publicKey
        val deviceBSigningSeed = ByteArray(32)
            .also { java.security.SecureRandom().nextBytes(it) }
        val deviceBSigningPublicKey = org.tenshu.core.common.security.BouncyCastleEd25519Backend()
            .derivePublicKey(deviceBSigningSeed)
        // Workstream B: Device B's id is its A4 key's — the only id the host admitter signs over.
        val deviceBId = org.tenshu.sync.common.deviceIdForPublicKey(deviceBSigningPublicKey)

        // The host's currentOwnerId is now derived from the master public key
        // (`ownerIdForMasterPublicKey`); Device B must address its pairing request to
        // the same value so Device A's `getPairingRequests` poll sees it.
        val hostOwnerId = syncManager.currentOwnerId!!
        assertTrue(
            relay.postPairingRequest(
                ownerId = hostOwnerId,
                // F7: what InitiatePairing posts — the secret's hash and the name sealed under it.
                pairingSecretHash = org.tenshu.sync.proxy.security.PairingRendezvous.joinSecretHash(secret),
                deviceId = deviceBId,
                sealedDeviceName = org.tenshu.sync.proxy.security.PairingRendezvous.sealJoinDeviceName(
                    crypto = org.tenshu.core.common.security.createDefaultSyncCryptoManager(),
                    pairingSecret = secret,
                    deviceId = deviceBId,
                    publicKey = deviceBPublicKey,
                    signingPublicKey = deviceBSigningPublicKey,
                    deviceName = deviceBName,
                ),
                publicKey = deviceBPublicKey,
                signingPublicKey = deviceBSigningPublicKey
            ) is TResult.Success, "Relay post should succeed"
        )

        // 4. A request arriving does not end the session — only approve / deny do.
        assertTrue(
            monitor.isPairing.value,
            "Should still be in pairing mode (showing QR or dialog)"
        )

        // 5. Device A detects the request on its poll.
        val detectedRequest = awaitStep("the host's poll to surface Device B's request") {
            monitor.activeRequest.filterNotNull().first()
        }
        assertEquals(deviceBName, detectedRequest.deviceName.get())

        // 6. Put a poll in flight across the approval: asked while the session is live (so the
        // proxy answers it with the request), not delivered to the monitor until after completion.
        syncRepository.holdNextPollCarryingARequest()
        awaitStep("a poll carrying the request to be in flight") { syncRepository.held.await() }

        // 7. Device A approves, and the handshake completes.
        monitor.approveActiveRequest()
        val outcome = awaitStep("the approval to complete or fail") {
            merge(
                monitor.pairingComplete.filter { it }.map { APPROVED },
                monitor.approvalError.filterNotNull().map { it.toString() },
            ).first()
        }
        assertEquals(APPROVED, outcome, "the host's approval must be accepted")

        // 8. At completion the session is gone on both layers. (The "Device B becomes visible
        // in Device A's device list" assertion that used to live here relied on the legacy
        // PairDevice op's local-DB mirror; in the new flow Device B's Device entity
        // arrives via SyncBlock propagation from the real Device B, which doesn't
        // exist in this single-process simulation. That post-approval visibility is
        // covered by `InvisibleSelfBugRegressionTest` and `RevokeDevicePropagationTest`.)
        assertNull(monitor.activeRequest.value, "no pending request may remain once approval completed")
        assertNull(monitor.pairingString.value)
        assertTrue(
            !monitor.isPairing.value,
            "Pairing mode should be cleared after approval"
        )
        assertNull(
            syncManager.activePairingContext,
            "the proxy's pairing context (and its ephemeral key) must be gone after approval",
        )
        assertTrue(
            syncRepository.approvePairingRequest(deviceBId) is TResult.Failure<*>,
            "a completed handshake must not be answerable a second time",
        )

        // 9. The poll that was in flight now answers — with the request, because it was asked
        // before the approval. It must not put the request back.
        syncRepository.release.complete(Unit)
        withContext(monitorDispatcher) { }
        assertNull(
            monitor.activeRequest.value,
            "an answer to a poll from the finished session must not resurface the approved request",
        )
        assertTrue(monitor.pairingComplete.value)
    }

    /**
     * Awaits [signal], failing with the name of the step if it never comes. The bound only turns
     * a hang into a red test; nothing passes or fails by how close to it a step lands.
     */
    private suspend fun <T : Any> awaitStep(step: String, signal: suspend () -> T): T =
        withTimeoutOrNull(STEP_HANG_GUARD_MS) { signal() }
            ?: fail("Timed out after ${STEP_HANG_GUARD_MS / 1000} s waiting for $step")

    private companion object {
        const val STEP_HANG_GUARD_MS = 60_000L

        /** Short, because the test runs on real time; the handshake is the same at any cadence. */
        const val POLL_INTERVAL_MS = 50L
        const val APPROVED = "approved"
    }
}

/**
 * The real [SyncRepository], able to hold one poll **at its answer**: the proxy has already
 * replied (with the pending request, since the session was live when it was asked), and the
 * monitor does not receive that reply until [release] completes.
 */
private class PollHoldingSyncRepository(
    private val delegate: SyncRepository,
) : SyncRepository by delegate {

    private val armed = AtomicBoolean(false)

    /** Completes once a poll carrying a request is being held. */
    val held = CompletableDeferred<Unit>()

    /** Complete to deliver the held answer to the monitor. */
    val release = CompletableDeferred<Unit>()

    fun holdNextPollCarryingARequest() = armed.set(true)

    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> {
        val answer = delegate.getPendingPairingRequests()
        val carriesARequest = answer.getOrNull()?.toList()?.isNotEmpty() == true
        if (carriesARequest && armed.compareAndSet(true, false)) {
            held.complete(Unit)
            release.await()
        }
        return answer
    }
}
