package org.tenshu.systemtest

import app.tenshu.control.data.TenshuSyncRepository
import app.tenshu.control.sync.HostPairingMonitor
import app.tenshu.control.ui.screens.sync.PairingStep
import app.tenshu.control.ui.screens.sync.PairingWizardViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.awaitState
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.TResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Two-party **VM-level flow tests**: the [TestSystem] harness (two real proxy stacks + the
 * in-process relay, as in `PairingSystemTest`) with the *real* [PairingWizardViewModel] on
 * both sides, asserting the **user-visible state sequence** — not just the ops-layer outcome.
 *
 * This is the layer where this week's field bugs actually lived and where component tests are
 * structurally blind: the unpaired Show tab spinning forever (the proxy pinned its null-string
 * contract, no test consumed it UI-side), and the offer flow's SAS timing (round-trip tests
 * prove both sides *derive* the same SAS, but never asserted what each user *sees, and when*).
 *
 * Threading mirrors `PairingSystemTest`: `runBlocking` + a real single-thread Main (the VMs'
 * `viewModelScope`) because `ProxyGateway.call` deadlocks under the test dispatcher.
 *
 * ## Stepped: every poll is one the test takes
 *
 * *What each user sees, and when* is the subject, and "when" is decided by three polls: the
 * offerer's status poll (`OfferPairingMonitor`), the host's request poll (`HostPairingMonitor`) and
 * the joiner's in-proxy wait (`AwaitPairing`). Each takes its pause from a [StepPacer], and
 * `step()` lets exactly one more poll run, after the previous one has finished. So each case is a
 * sequence: the other device acts, its own state shows the act reached the relay, then one poll on
 * this device must show the outcome. A state that needed a second poll would go red rather than wait
 * 2 s longer. The system is stepped too (`docs/testing.md` §12), so no sync loop ticks underneath.
 * The view models' states are still awaited with `awaitState`, because a poll publishes on the
 * view model's own coroutines.
 *
 * ⚠⚠ Every [Party] is built through [Parties.own] and torn down by [tearDown] BEFORE the system —
 * a real Main means a real 6 s timer on `viewModelScope`, and one that outlives its test fails a
 * different class. [Parties]' KDoc has the mechanism and the measurement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingVmFlowSystemTest {

    private lateinit var mainThread: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainThread)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThread.close()
    }

    private class Party(client: TestClient, scope: CoroutineScope) {
        val repository = TenshuSyncRepository(client.tenshuClient)
        /** The host's request poll (`HostPairingMonitor`): it polls, then pauses here. */
        val hostPoll = StepPacer("${client.deviceId}'s host request poll")
        /** The offerer's status poll (`OfferPairingMonitor`): it pauses here, then polls. */
        val offerPoll = StepPacer("${client.deviceId}'s offer status poll")
        val monitor = HostPairingMonitor(repository, scope, pacePoll = hostPoll.pace)
        val viewModel = PairingWizardViewModel(repository, monitor, scope, offerPollPacer = offerPoll.pace)
    }

    /**
     * Owns the lifetime of every [Party] a test builds — the `runBlocking` twin of
     * `maps-shared-logic`'s `OwnedViewModels`, for the same reason.
     *
     * ⚠⚠ A `PairingWizardViewModel` that reaches a pairing terminal arms
     * `watchForUnfundedRealm`: a 6 s `delay` on `viewModelScope` (`Main.immediate`) that then reads
     * `syncHealth()` off the party's client. Nothing in a test ever cancelled that scope — the
     * monitors' `scope` is not it — so the timer outlived the test, resumed on whichever `Main`
     * delegate was live six seconds later (the *next* test's `mainThread`), and read a client whose
     * Koin graph `system.shutdown()` had already closed:
     *
     *     NullPointerException: null cannot be cast to non-null type org.tenshu.core.request.RoProvider
     *       at …roProvider(RoProvider.kt:42) ← …PairingWizardViewModel$watchForUnfundedRealm$1
     *
     * A `StandaloneCoroutine` on `viewModelScope` has no handler, so that went to kotlinx-test's
     * global `ExceptionCollector` and was replayed into the **next `runTest` in the JVM** as
     * `UncaughtExceptionsBeforeTest` — `TestClientGraphResolutionTest`, alphabetically the first one
     * after this class, blamed for months as a "rotating victim". Measured 2026-09-02: 2/3 red with
     * this class followed by that reporter; a forced-interleaving pair (this teardown held open past
     * the settle) is 3/3 red without [closeAll] and 3/3 green with it. `docs/tech-debt.md` § "The
     * full gate is flaky under parallel load" carries the evidence.
     *
     * ⚠ [cancelAndJoin], never a bare `cancel`: the fix is the JOIN — a cancel returns while the
     * watcher is still unwinding and loses the same race with `system.shutdown()`.
     */
    private class Parties {
        private val owned = mutableListOf<Party>()

        /** Register a party as this test's to close. Wrap **every** construction. */
        fun own(party: Party): Party = party.also { owned += it }

        /** Newest first: a later party may observe an earlier one's repository. */
        suspend fun closeAll() {
            owned.asReversed().forEach { it.viewModel.viewModelScope.coroutineContext.job.cancelAndJoin() }
        }

        val anyActive: Boolean get() = owned.any { it.viewModel.viewModelScope.isActive }
    }

    /**
     * The one teardown order that leaves nothing running: the view models first (their scopes
     * carry the unfunded-realm watcher and the offer poll), then the monitors' [scope] — joined,
     * not merely cancelled — and only then the system those coroutines read from.
     */
    private suspend fun tearDown(parties: Parties, scope: CoroutineScope, system: TestSystem) {
        parties.closeAll()
        scope.coroutineContext.job.cancelAndJoin()
        system.shutdown()
    }

    private fun assertNothingOutlived(parties: Parties) = assertFalse(
        parties.anyActive,
        "a view model outlived its test — build every Party with `parties.own(...)` so its " +
            "viewModelScope is cancelled and JOINED before system.shutdown(); see Parties' KDoc",
    )

    // ------------------------------------------------------------------ offer flow (§4)

    @Test
    fun offerFlow_unpairedDesktopShowsOffer_phoneApproves_desktopLandsPaired() = runBlocking {
        val system = TestSystem.builder().stepped().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val parties = Parties()
        try {
            val phoneClient = system.client("phone", TestClientRole.RealmHost)
            val desktopClient = system.client("desktop", TestClientRole.JoiningDevice)
            val bootstrap = phoneClient.bootstrapAsHost(deviceName = "Phone")
            assertTrue(bootstrap is TResult.Success, "phone realm bootstrap failed: $bootstrap")

            val phone = parties.own(Party(phoneClient, scope))
            val desktop = parties.own(Party(desktopClient, scope))

            // 1. Unpaired desktop picks "Show a code" → the offerer role with a real
            //    tenshu://offer QR — never the host role's ownerless eternal spinner.
            desktop.viewModel.onSelectShow()
            val shown = desktop.viewModel.uiState.awaitState("the offer QR on the desktop") {
                it.offerRole && it.offerUri != null
            }
            val offerUri = assertNotNull(shown.offerUri)
            assertTrue(offerUri.startsWith("tenshu://offer"), "expected an offer URI, got $offerUri")

            // 2. Phone scans → the SAS approval prompt appears on the phone…
            phone.viewModel.onQrScanned(offerUri)
            val phonePrompt = phone.viewModel.uiState.awaitState("the responder approval on the phone") {
                it.step == PairingStep.ResponderApproval && it.responderSas != null
            }
            val phoneSas = assertNotNull(phonePrompt.responderSas)
            // The prompt's SAS is set only once `RespondToPairingOffer` has returned, and that op
            // posts the scanned announce before it returns. So ONE offerer poll from here must
            // see it.
            desktop.offerPoll.step()

            // ⭐⭐ …and NOW the desktop shows the same code, with the decision still open. This
            // assertion is the inverse of the one it replaces: until A1–A3 the offerer could not
            // derive the SAS before approval, because it needs the phone's ephemeral key and that
            // key only rode the approve POST. The scanned announce carries it earlier, so the
            // ceremony is compare-then-approve on both screens — the join flow's shape, which the
            // offer flow structurally lacked.
            //
            // ⚠ The old test pinned the OPPOSITE ("no SAS on the offerer pre-approval") to stop
            // copy implying a simultaneous comparison that could not happen. That pin was correct
            // for its time and is deliberately reversed here, not deleted: what must never regress
            // is that the two screens agree.
            val offererPreApproval = desktop.viewModel.uiState.awaitState(
                "the offerer's pre-approval SAS (scanned announce)",
            ) { it.offerScanned }
            assertEquals(
                phoneSas,
                offererPreApproval.offerSas,
                "both screens must show the SAME code BEFORE the decision — that is the ceremony",
            )
            assertFalse(
                desktop.viewModel.uiState.value.offerPaired,
                "showing the code must not imply the join happened — nothing is installed yet",
            )

            // 3. Phone approves → both terminals; the desktop's post-hoc SAS matches the
            //    one the phone's prompt showed.
            phone.viewModel.approveOffer()
            val phoneTerminal = phone.viewModel.uiState.awaitState("the device-added terminal on the phone") {
                it.step == PairingStep.ResponderSuccess
            }
            // ResponderSuccess is set once the approval op has returned, so ONE more offerer poll
            // must see the approval.
            desktop.offerPoll.step()

            // ⚠⚠ The SAS must SURVIVE the transition to the terminal, because that is the only
            // screen on which the comparison can actually be made. The offer flow's SAS is
            // post-approval by construction (see the assertion above), so the responder used to
            // approve, watch the code disappear, and then be asked to compare it against the new
            // device from MEMORY — a comparison that mostly does not happen, which makes T-P-07's
            // tripwire weaker in practice than "detected, not prevented" suggests.
            // `sync-pairing-scanned-announce.md` §5.3 keeps it on screen; this pins the state the
            // UI needs to do that, so clearing `responderSas` on success goes red here rather than
            // silently rendering an empty box.
            assertEquals(
                phoneSas,
                phoneTerminal.responderSas,
                "the responder's SAS must still be readable at the terminal — it is the only " +
                    "screen where the user can compare it side by side",
            )
            val paired = desktop.viewModel.uiState.awaitState("the paired terminal on the desktop") {
                it.offerPaired
            }
            assertEquals(phoneSas, paired.offerSas, "post-hoc SAS must match the phone's prompt")

            // 4. Below the UI, the desktop's proxy is now realm-bound to the phone's owner.
            assertEquals(
                phoneClient.syncManager.currentOwnerId,
                desktopClient.syncManager.currentOwnerId,
                "the desktop must have joined the phone's realm",
            )
        } finally {
            tearDown(parties, scope, system)
        }
        assertNothingOutlived(parties)
    }

    @Test
    fun offerFlow_phoneDenies_desktopShowsDeclinedNotSpinner() = runBlocking {
        val system = TestSystem.builder().stepped().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val parties = Parties()
        try {
            val phoneClient = system.client("phone", TestClientRole.RealmHost)
            val desktopClient = system.client("desktop", TestClientRole.JoiningDevice)
            assertTrue(phoneClient.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            val phone = parties.own(Party(phoneClient, scope))
            val desktop = parties.own(Party(desktopClient, scope))

            desktop.viewModel.onSelectShow()
            val offerUri = assertNotNull(
                desktop.viewModel.uiState.awaitState("the offer QR on the desktop") {
                    it.offerRole && it.offerUri != null
                }.offerUri,
            )

            phone.viewModel.onQrScanned(offerUri)
            phone.viewModel.uiState.awaitState("the responder approval on the phone") {
                it.step == PairingStep.ResponderApproval && it.responderSas != null
            }

            phone.viewModel.denyOffer()
            // The phone returns to its scanner only once the deny op has returned, so ONE offerer
            // poll from there must see the denial.
            phone.viewModel.uiState.awaitState("the phone back at its scanner after denying") {
                it.step == PairingStep.ScanQr && it.responderSecretHash == null
            }
            desktop.offerPoll.step()
            val deniedState = desktop.viewModel.uiState.awaitState("the declined terminal on the desktop") {
                it.offerDenied
            }
            assertNull(deniedState.offerUri, "a denied offer's QR must leave the screen")
            assertFalse(deniedState.offerPaired)
            assertNull(desktopClient.syncManager.currentOwnerId, "a denied desktop must stay unpaired")
        } finally {
            tearDown(parties, scope, system)
        }
        assertNothingOutlived(parties)
    }

    // ------------------------------------------------------------------ join flow (§3)

    @Test
    fun joinFlow_pairedPhoneShowsJoinQr_desktopRequests_phoneApproves_desktopLandsPaired() = runBlocking {
        val system = TestSystem.builder().stepped().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val parties = Parties()
        try {
            // The joiner's in-proxy pairing wait (`AwaitPairing`): it tries, then pauses here.
            val desktopWait = StepPacer("the desktop's pairing wait")
            val phoneClient = system.client("phone", TestClientRole.RealmHost)
            val desktopClient = system.client("desktop", TestClientRole.JoiningDevice, pairingWaitPacer = desktopWait.pace)
            assertTrue(phoneClient.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            val phone = parties.own(Party(phoneClient, scope))
            val desktop = parties.own(Party(desktopClient, scope))

            // 1. The PAIRED phone's Show tab hosts the origin join QR (never the offer role).
            phone.viewModel.onSelectShow()
            val hostState = phone.viewModel.uiState.awaitState("the join QR on the phone") {
                it.hostPairingString != null
            }
            assertFalse(hostState.offerRole, "a realm-bound device hosts; it never offers")
            val joinUri = assertNotNull(hostState.hostPairingString)
            assertTrue(joinUri.startsWith("tenshu://join"), "expected a join URI, got $joinUri")

            // 2. Desktop scans → names itself → requests; its SAS surfaces while waiting.
            desktop.viewModel.onQrScanned(joinUri)
            assertEquals(PairingStep.NameDevice, desktop.viewModel.uiState.value.step)
            desktop.viewModel.confirmPairing("Desktop")
            val requesting = desktop.viewModel.uiState.awaitState("the requester SAS on the desktop") {
                it.sas != null
            }
            val desktopSas = assertNotNull(requesting.sas)

            // 3. The phone's process-lifetime monitor surfaces the approval request — the
            //    app-global modal's trigger — carrying the SAME SAS the desktop shows
            //    (join flow = true simultaneous comparison, unlike the offer flow).
            //    The desktop's SAS is set once `InitiatePairing` has returned, which posts the
            //    request; so ONE host poll from here must surface it.
            phone.hostPoll.step()
            val request = assertNotNull(
                phone.monitor.activeRequest.awaitState("the approval request on the phone") { it != null },
            )
            assertEquals("Desktop", request.deviceName.get())
            assertEquals(desktopSas, request.sas?.get(), "host and requester must show the same SAS")

            // 4. Phone approves → the desktop reaches Success and is realm-bound.
            //    ⚠ The desktop's wait tries once BEFORE its first pause. Stand on that pause first,
            //    so its pending attempt is known to have finished before the approval; then the
            //    approval, confirmed by the monitor; then exactly one more attempt.
            desktopWait.awaitPause()
            phone.monitor.approveActiveRequest()
            phone.monitor.pairingComplete.awaitState("the phone's approval landed") { it }
            desktopWait.release()
            desktop.viewModel.uiState.awaitState("the success terminal on the desktop") {
                it.step == PairingStep.Success
            }
            assertEquals(
                phoneClient.syncManager.currentOwnerId,
                desktopClient.syncManager.currentOwnerId,
                "the desktop must have joined the phone's realm",
            )
        } finally {
            tearDown(parties, scope, system)
        }
        assertNothingOutlived(parties)
    }
}
