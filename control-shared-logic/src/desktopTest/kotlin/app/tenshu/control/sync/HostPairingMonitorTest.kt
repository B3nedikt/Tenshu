package app.tenshu.control.sync

import app.tenshu.control.ui.screens.sync.PairingError
import app.tenshu.error.ErrorCopyKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.SyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.GenericErrorDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.core.TContext
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.sync.GetPendingPairingRequests
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit test for [HostPairingMonitor] (`control-pairing-ux.md` Phase 1). Drives a fake
 * [SyncRepository] over a constructor-injected test scope (no platform runtime) so the
 * process-lifetime poll contract is locked independently of the UI. Mirrors
 * `PendingRequestAlertsTest`'s structure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostPairingMonitorTest {

    private val context: TContext = run {
        val registry = createCoreTestRegistry()
        registry.registerConfig(syncServiceCommonModule)
        createTestContext(schemaRegistry = registry)
    }

    private fun syncStatusWithPairingString(pairingString: String?): SyncStatus =
        SyncStatus.create(
            context = context,
            deviceId = "this-device",
            ownerId = "owner-1",
            isEnabled = true,
            pairingString = pairingString,
        )

    private fun pendingRequest(): PairingRequest = object : PairingRequest {
        override val deviceId = "joiner-id".toTString()
        override val deviceName = "Joiner Phone".toTString()
        override val publicKey = byteArrayOf().toTByteArray()
        override val pairingSecretHash = "secret".toTString()
        override val schema get() = PairingRequest.Schema
        override fun <T : Data> getField(reference: String): T {
            @Suppress("UNCHECKED_CAST")
            return when (reference) {
                PairingRequest.Fields.DEVICE_ID -> deviceId
                PairingRequest.Fields.DEVICE_NAME -> deviceName
                PairingRequest.Fields.PUBLIC_KEY -> publicKey
                PairingRequest.Fields.PAIRING_SECRET_HASH -> pairingSecretHash
                else -> error("Field not mocked: $reference")
            } as T
        }

        override fun <T : Data> getOptionalField(reference: String): T? = null
    }

    @Test
    fun a_pending_request_surfaces_on_activeRequest() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=RESUMEME&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)

        // init: resumeActivePairingSession adopts the live proxy session.
        runCurrent()
        assertTrue(monitor.isPairing.value, "a live proxy pairing session must be re-adopted on init")
        assertEquals("tenshu://join?o=o&d=RESUMEME&k=K&s=S", monitor.pairingString.value)
        assertEquals(0, repo.startPairingCalls, "resume must not call startPairing")

        // Drive a poll cycle so the request surfaces.
        advanceTimeBy(2_100L)
        runCurrent()

        val request = monitor.activeRequest.value
        assertNotNull(request, "the observer must surface the joiner's request")
        assertEquals("joiner-id", request.deviceId.get())
    }

    @Test
    fun a_resume_whose_session_mint_throws_is_contained_and_a_later_session_still_polls() = runTest {
        // ⚠⚠ The monitor's scope has no exception handler, and on K/N an uncaught throw there aborts
        // the process. `backgroundScope` fails this test on one instead.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=RESUMEME&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        ).apply { syncStatusThrows = 1 }
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)

        runCurrent()
        assertEquals(0, repo.syncStatusThrows, "the resume asked, and met the throw")
        assertFalse(monitor.isPairing.value, "a resume that threw adopts nothing")

        // The user's next "Add device" starts a session, and its poll surfaces the request.
        monitor.startPairing()
        runCurrent()
        assertTrue(monitor.isPairing.value)
        advanceTimeBy(2_100L)
        runCurrent()
        assertNotNull(monitor.activeRequest.value, "the poll still runs after the resume threw")
    }

    @Test
    fun a_clean_empty_poll_clears_connectionLost() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)

        runCurrent()
        // Simulate a prior failed poll having raised the banner, then a clean poll.
        repo.failNextPoll = true
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(monitor.connectionLost.value, "a failed poll raises the connection-lost banner")

        repo.failNextPoll = false
        advanceTimeBy(2_100L)
        runCurrent()
        assertFalse(monitor.connectionLost.value, "a clean empty poll clears connection-lost")
    }

    @Test
    fun a_refused_device_is_not_reported_as_an_unreachable_relay() = runTest {
        // ⚠⚠ THE bug this state exists for, seen on a desktop on 2026-08-17: its registration had
        // been wiped, so the relay answered every poll with a 401 — and the pairing screen said
        // "Can't reach the relay — retrying…". Wrong twice: the relay had replied (the user is sent
        // to check a working network), and this device is a pairing joiner, so no amount of retrying
        // can restore it. Only re-pairing can.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()

        repo.pollRefusesDevice = true
        advanceTimeBy(2_100L)
        runCurrent()

        assertTrue(
            monitor.deviceUnrecognized.value,
            "a 401 poll whose repair failed must report the device as unrecognised",
        )
        assertFalse(
            monitor.connectionLost.value,
            "…and must NOT claim the relay is unreachable: it answered",
        )
    }

    @Test
    fun a_refused_device_ends_the_host_session_it_cannot_complete() = runTest {
        // ⚠⚠ Naming the state was not enough. A host session on an unrecognised device cannot
        // complete by construction — the joiner's request lands in a relay mailbox this device's own
        // poll is 401'd from — so leaving it armed shows a QR that invites the other device into an
        // endless "Confirm on your other device". Observed on 2026-08-17, with the QR rendered
        // directly beneath the banner saying this device isn't recognised.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        monitor.startPairing()
        runCurrent()
        assertTrue(monitor.isPairing.value, "precondition: a host session is armed")

        repo.pollRefusesDevice = true
        advanceTimeBy(2_100L)
        runCurrent()

        assertTrue(monitor.deviceUnrecognized.value)
        assertFalse(monitor.isPairing.value, "the session must be torn down, not left running")
        assertEquals(null, monitor.pairingString.value, "and its code must be withdrawn")
    }

    @Test
    fun an_unreachable_relay_still_reports_connection_lost() = runTest {
        // The complement, so the fix above cannot degrade into "always blame the identity": a
        // genuine network failure says nothing about whether the relay would recognise this device.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()

        repo.failNextPoll = true
        advanceTimeBy(2_100L)
        runCurrent()

        assertTrue(monitor.connectionLost.value, "a network failure raises connection-lost")
        assertFalse(
            monitor.deviceUnrecognized.value,
            "a network failure must not accuse the relay of refusing this device",
        )
    }

    @Test
    fun a_successful_repair_shows_neither_banner() = runTest {
        // An A1-holding device repairs itself, which is why this state is in practice the joiner's.
        // It must not flash "you were removed" on the way through.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        repo.reregisterSucceeds = true
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()

        repo.pollRefusesDevice = true
        advanceTimeBy(2_100L)
        runCurrent()

        assertFalse(
            monitor.deviceUnrecognized.value,
            "a repair the relay accepted must not leave the device marked unrecognised",
        )
        assertFalse(monitor.connectionLost.value)
    }

    @Test
    fun approve_clears_activeRequest_and_isPairing() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        assertNotNull(monitor.activeRequest.value)

        monitor.approveActiveRequest()
        runCurrent()

        assertNull(monitor.activeRequest.value, "approve clears the active request")
        assertFalse(monitor.isPairing.value, "approve ends the pairing session")
        assertTrue(
            monitor.pairingComplete.value,
            "an accepted approval surfaces a terminal success so the Show screen leaves its spinner",
        )
        assertEquals(1, repo.approveCalls)
    }

    @Test
    fun a_failed_approve_does_not_mark_complete() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        repo.failApprove = true
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        assertNotNull(monitor.activeRequest.value)

        monitor.approveActiveRequest()
        runCurrent()

        assertFalse(
            monitor.pairingComplete.value,
            "a rejected approval must not claim success",
        )
    }

    @Test
    fun re_entering_show_clears_a_prior_success() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        monitor.approveActiveRequest()
        runCurrent()
        assertTrue(monitor.pairingComplete.value)

        // Starting a fresh "Show a code" session must drop the stale success.
        monitor.startPairing()
        runCurrent()
        assertFalse(
            monitor.pairingComplete.value,
            "a fresh session shows a QR, not the previous session's success",
        )
    }

    @Test
    fun deny_clears_activeRequest_and_isPairing() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        assertNotNull(monitor.activeRequest.value)

        monitor.denyActiveRequest()
        runCurrent()

        assertNull(monitor.activeRequest.value, "deny clears the active request")
        assertFalse(monitor.isPairing.value, "deny stops pairing")
        assertEquals(1, repo.stopPairingCalls)
    }

    // --- A poll outliving its session: asked while the session is live, answered after it ended ---

    /** Arms a session whose request has surfaced and whose NEXT poll is held at its answer. */
    private fun TestScope.monitorWithAPollInFlight(
        repo: FakeSyncRepository,
    ): HostPairingMonitor {
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        assertNotNull(monitor.activeRequest.value, "precondition: the request has surfaced")
        repo.holdPolls = CompletableDeferred()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, repo.pollsHeld, "precondition: a poll carrying the request is in flight")
        return monitor
    }

    @Test
    fun a_poll_answered_after_approval_does_not_bring_the_request_back() = runTest {
        // ⚠⚠ The proxy reads the pairing context when a poll STARTS, so one in flight across the
        // approval still answers with the request. Publishing it reopened the approval modal on a
        // device that had just been approved.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = monitorWithAPollInFlight(repo)

        monitor.approveActiveRequest()
        runCurrent()
        assertTrue(monitor.pairingComplete.value)
        assertNull(monitor.activeRequest.value, "precondition: approval cleared the request")

        repo.holdPolls!!.complete(Unit)
        runCurrent()

        assertNull(
            monitor.activeRequest.value,
            "an answer to a poll from the finished session must not resurface its request",
        )
        assertFalse(monitor.isPairing.value)
    }

    @Test
    fun a_poll_answered_after_deny_does_not_bring_the_request_back() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = monitorWithAPollInFlight(repo)

        monitor.denyActiveRequest()
        runCurrent()
        assertNull(monitor.activeRequest.value, "precondition: deny cleared the request")

        repo.holdPolls!!.complete(Unit)
        runCurrent()

        assertNull(monitor.activeRequest.value, "a denied request must stay dismissed")
    }

    @Test
    fun a_poll_from_the_previous_session_does_not_surface_in_the_next_one() = runTest {
        // The variant that does not heal itself: the stale answer lands while the NEW session is
        // live, and an empty poll never clears `activeRequest` — so the previous session's request,
        // carrying the previous session's SAS, would sit in the new session until approve or deny.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = monitorWithAPollInFlight(repo)

        monitor.stopPairing()
        runCurrent()
        repo.pendingRequests = emptyList()
        monitor.startPairing()
        runCurrent()
        assertTrue(monitor.isPairing.value, "precondition: a new session is live")

        repo.holdPolls!!.complete(Unit)
        repo.holdPolls = null
        runCurrent()
        assertNull(
            monitor.activeRequest.value,
            "the previous session's request must not appear in the new session",
        )

        advanceTimeBy(6_000L)
        runCurrent()
        assertNull(monitor.activeRequest.value)
        assertTrue(monitor.isPairing.value, "the new session is untouched")
    }

    @Test
    fun a_poll_of_the_live_session_still_surfaces_its_request() = runTest {
        // The complement: the gate must drop only answers from a session that ENDED, not any
        // answer that was slow.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        repo.holdPolls = CompletableDeferred()
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        assertEquals(1, repo.pollsHeld)
        assertNull(monitor.activeRequest.value)

        advanceTimeBy(30_000L)
        repo.holdPolls!!.complete(Unit)
        runCurrent()

        assertNotNull(monitor.activeRequest.value, "a slow answer in a live session is still an answer")
    }

    // --- Phase 1.5: bounded, self-destructing session (control-pairing-ux.md §3.5) ---

    @Test
    fun countdown_ticks_down_while_waiting() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        assertEquals(120, monitor.remainingSeconds.value, "the window arms at 2 minutes")

        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(117, monitor.remainingSeconds.value, "the window ticks down once per second")
    }

    @Test
    fun countdown_pauses_while_a_request_is_in_flight() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        // The request surfaces on the 2 s poll; let a few more seconds pass so it is set.
        advanceTimeBy(5_000L)
        runCurrent()
        assertNotNull(monitor.activeRequest.value, "the joiner request must have surfaced")

        val paused = monitor.remainingSeconds.value
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(
            paused,
            monitor.remainingSeconds.value,
            "the countdown must hold while a request awaits human approval",
        )
        assertTrue(monitor.isPairing.value, "a paused session stays alive")
    }

    @Test
    fun the_session_self_destructs_when_the_window_expires() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        assertTrue(monitor.isPairing.value)

        advanceTimeBy(121_000L)
        runCurrent()

        assertFalse(monitor.isPairing.value, "an expired waiting window tears the session down")
        assertNull(monitor.remainingSeconds.value, "the countdown clears on teardown")
        assertNull(monitor.pairingString.value)
        assertTrue(repo.stopPairingCalls >= 1, "expiry clears (and zeroes) the proxy context")
    }

    @Test
    fun approve_cancels_the_countdown() = runTest {
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=X&k=K&s=S"),
            pendingRequests = listOf(pendingRequest()),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        assertNotNull(monitor.activeRequest.value)

        monitor.approveActiveRequest()
        runCurrent()
        assertNull(monitor.remainingSeconds.value, "approve clears the countdown")

        // A cancelled countdown must not resurrect or fire a second teardown.
        advanceTimeBy(125_000L)
        runCurrent()
        assertNull(monitor.remainingSeconds.value)
        assertEquals(0, repo.stopPairingCalls, "approve must not also call stopPairing")
    }

    // --- A start that yields no code is not a session ---

    @Test
    fun a_failed_start_is_not_a_session_and_says_why() = runTest {
        val repo = FakeSyncRepository(syncStatus = syncStatusWithPairingString(null), pendingRequests = emptyList())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()

        monitor.startPairing()
        runCurrent()

        assertFalse(monitor.isPairing.value, "a start with no code must not declare a live session")
        assertNull(monitor.pairingString.value)
        assertNull(monitor.remainingSeconds.value, "no session, so no countdown")
        assertNotNull(monitor.startError.value, "the failure must be exposed, not swallowed")

        // Nothing to poll against, and nothing to expire.
        advanceTimeBy(125_000L)
        runCurrent()
        assertEquals(0, repo.pollCalls, "a codeless start must not poll")
        assertEquals(0, repo.stopPairingCalls, "there was no session for the window to tear down")
    }

    @Test
    fun a_successful_start_with_no_code_is_a_failure_too() = runTest {
        val repo = FakeSyncRepository(syncStatus = syncStatusWithPairingString(null), pendingRequests = emptyList())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()

        monitor.startPairing()
        runCurrent()

        assertFalse(monitor.isPairing.value)
        assertNull(monitor.remainingSeconds.value)
        assertEquals(
            PairingError.Unexpected(ErrorCopyKey.UNEXPECTED),
            monitor.startError.value,
            "a success carrying no pairing string is the generic failure",
        )
    }

    @Test
    fun a_retry_after_a_failed_start_clears_the_error_and_serves_the_code() = runTest {
        // The resume read finds nothing, so the session can only come from a start.
        val repo = FakeSyncRepository(syncStatus = syncStatusWithPairingString(null), pendingRequests = emptyList())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        monitor.startPairing()
        runCurrent()
        assertNotNull(monitor.startError.value)

        repo.startPairingResult = ResultSuccessDto(syncStatusWithPairingString("tenshu://join?o=o&d=RETRY&k=K&s=S"))
        monitor.startPairing()
        runCurrent()

        assertNull(monitor.startError.value, "a new start clears the previous failure")
        assertTrue(monitor.isPairing.value)
        assertEquals("tenshu://join?o=o&d=RETRY&k=K&s=S", monitor.pairingString.value)
        assertEquals(120, monitor.remainingSeconds.value, "a real session arms the window")
    }

    @Test
    fun a_stop_landing_after_a_failed_start_does_not_erase_the_failure() = runTest {
        // Stop and start are independent coroutines: a stop issued before the start (the user left
        // Show and came straight back) can land after it. It must not take the failure with it.
        val repo = FakeSyncRepository(syncStatus = syncStatusWithPairingString(null), pendingRequests = emptyList())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()

        repo.holdStop = CompletableDeferred()
        monitor.stopPairing()
        monitor.startPairing()
        runCurrent()
        assertNotNull(monitor.startError.value, "the start must have failed while the stop was still out")

        repo.holdStop?.complete(Unit)
        runCurrent()

        assertEquals(1, repo.stopPairingCalls)
        assertNotNull(monitor.startError.value, "a late stop must not put the Show tab back on its spinner")
        assertFalse(monitor.isPairing.value)
    }

    @Test
    fun a_start_that_succeeds_clears_an_overlapping_attempts_failure() = runTest {
        // A double tap: two starts in flight, the first fails, the second mints the code.
        // The resume read finds nothing, so the session can only come from a start.
        val repo = FakeSyncRepository(syncStatus = syncStatusWithPairingString(null), pendingRequests = emptyList())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        repo.startPairingResults += ResultFailureDto(GenericErrorDto("simulated start failure"))
        repo.startPairingResults += ResultSuccessDto(syncStatusWithPairingString("tenshu://join?o=o&d=TWICE&k=K&s=S"))

        monitor.startPairing()
        monitor.startPairing()
        runCurrent()

        assertTrue(monitor.isPairing.value)
        assertNull(monitor.startError.value, "a live code must not carry a failure beside it")
    }

    @Test
    fun a_failed_restart_keeps_the_session_already_being_served() = runTest {
        // Re-entering Show re-runs the start; its failure must not take down a code that is still
        // live, nor report a failure over it.
        val repo = FakeSyncRepository(
            syncStatus = syncStatusWithPairingString("tenshu://join?o=o&d=LIVE&k=K&s=S"),
            pendingRequests = emptyList(),
        )
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()
        assertTrue(monitor.isPairing.value, "precondition: the live session was adopted")

        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        monitor.startPairing()
        runCurrent()

        assertTrue(monitor.isPairing.value)
        assertEquals("tenshu://join?o=o&d=LIVE&k=K&s=S", monitor.pairingString.value)
        assertNull(monitor.startError.value)
    }

    @Test
    fun a_normal_start_reports_no_error() = runTest {
        val repo = FakeSyncRepository(syncStatus = syncStatusWithPairingString(null), pendingRequests = emptyList())
        repo.startPairingResult = ResultSuccessDto(syncStatusWithPairingString("tenshu://join?o=o&d=OK&k=K&s=S"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        runCurrent()

        monitor.startPairing()
        runCurrent()

        assertTrue(monitor.isPairing.value)
        assertEquals("tenshu://join?o=o&d=OK&k=K&s=S", monitor.pairingString.value)
        assertEquals(120, monitor.remainingSeconds.value)
        assertNull(monitor.startError.value)
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(repo.pollCalls >= 1, "a live session polls")
    }
}

/**
 * Minimal [SyncRepository] fake: a fixed sync status + pending-request set, call counters,
 * and a one-shot [failNextPoll] to exercise the connection-lost path.
 */
private class FakeSyncRepository(
    private val syncStatus: SyncStatus?,
    var pendingRequests: List<PairingRequest>,
) : SyncRepository {

    /**
     * When set, a poll takes its answer and then waits here before returning it — a poll in
     * flight, which is the only state the instant fake otherwise cannot be in.
     */
    var holdPolls: CompletableDeferred<Unit>? = null
    var pollsHeld: Int = 0
        private set

    var startPairingCalls: Int = 0
        private set
    var stopPairingCalls: Int = 0
        private set
    var approveCalls: Int = 0
        private set
    /** The relay is unreachable — a network failure, carrying no identity marker. */
    var failNextPoll: Boolean = false

    /**
     * The relay ANSWERED and refused this device's identity. Built from
     * [GetPendingPairingRequests.ERROR_DEVICE_UNRECOGNIZED] rather than a hand-typed message,
     * because that constant *is* the contract the monitor matches on.
     */
    var pollRefusesDevice: Boolean = false

    /** Whether the automatic repair succeeds — false models a joiner holding no master identity. */
    var reregisterSucceeds: Boolean = false
    var failApprove: Boolean = false

    override val devices: Flow<List<Device>> = MutableStateFlow(emptyList())

    /**
     * How many `getSyncStatus` calls throw before answering: the first call mints the in-process
     * session, and a mint over iOS's not-ready stand-in throws instead of answering a TResult.
     */
    var syncStatusThrows: Int = 0

    override suspend fun getSyncStatus(): TResult<SyncStatus> {
        if (syncStatusThrows > 0) {
            syncStatusThrows -= 1
            throw IllegalStateException("made up: the shared store is not ready")
        }
        return ResultSuccessDto(syncStatus)
    }

    var pollCalls: Int = 0
        private set

    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> {
        pollCalls += 1
        val answer: TResult<TList<PairingRequest>> = when {
            pollRefusesDevice -> ResultFailureDto(
                GenericErrorDto(
                    "${GetPendingPairingRequests.ERROR_DEVICE_UNRECOGNIZED}: the relay does not " +
                        "recognise this device — it must be paired again."
                )
            )
            failNextPoll -> ResultFailureDto(GenericErrorDto("simulated network failure: relay unreachable"))
            else -> ResultSuccessDto(pendingRequests.toTList())
        }
        holdPolls?.let { gate ->
            pollsHeld += 1
            gate.await()
        }
        return answer
    }

    /** What `startPairing` answers instead of [syncStatus] when set — e.g. a Failure. */
    var startPairingResult: TResult<SyncStatus>? = null

    /** Answers for successive `startPairing` calls, consumed first — two attempts that differ. */
    val startPairingResults: MutableList<TResult<SyncStatus>> = mutableListOf()

    override suspend fun startPairing(): TResult<SyncStatus> {
        startPairingCalls += 1
        val answer = startPairingResults.removeFirstOrNull() ?: startPairingResult ?: ResultSuccessDto(syncStatus)
        // A real start is an IPC round trip: suspend, so two attempts in flight genuinely overlap.
        kotlinx.coroutines.yield()
        return answer
    }

    /** When set, a stop waits here before returning — a stop still in flight when a start lands. */
    var holdStop: CompletableDeferred<Unit>? = null

    override suspend fun stopPairing(): TResult<SyncStatus> {
        stopPairingCalls += 1
        holdStop?.await()
        return ResultSuccessDto(null)
    }

    override suspend fun approvePairingRequest(deviceId: String): TResult<Data?> {
        approveCalls += 1
        return if (failApprove) ResultFailureDto(GenericErrorDto("simulated approve failure"))
        else ResultSuccessDto(null)
    }

    override suspend fun getDeviceId(): String = "this-device"

    // --- unused surface ---
    override suspend fun readmitDevice(deviceId: String): TResult<Data?> = ResultSuccessDto(null)
    override suspend fun revokeDevice(deviceId: String): TResult<Data?> = ResultSuccessDto(null)
    // ⚠ Widening SyncRepository broke every implementor — the interface change is
    // only 'additive' from the caller's side.
    override suspend fun forgetThisDevice(): TResult<TString> = error("not used")

    override suspend fun getRecoveryPhrase(): TResult<TString> = ResultSuccessDto(null)
    override suspend fun createRealm(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)
    // Defaults to failing like a joiner that can't self-reregister, so the banner the failed poll
    // raised stays up. [reregisterSucceeds] models an A1 holder, whose repair clears it.
    override suspend fun reregisterDevice(): TResult<Data?> =
        if (reregisterSucceeds) ResultSuccessDto(null)
        else ResultFailureDto(GenericErrorDto("no master identity"))
    override suspend fun recoveryClaim(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)
    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<PairingInitiation> = ResultSuccessDto(null)
    override suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<TBoolean> = ResultSuccessDto(null)
    override suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TaskHandle<TBoolean> = error("awaitPairing not expected")
    override suspend fun getUnlockStatus(): TResult<UnlockStatus> = ResultSuccessDto(null)
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(null)
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        ResultSuccessDto(null)
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(null)
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(null)
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(null)
    override suspend fun relaxToHeadless(): TResult<TBoolean> = ResultSuccessDto(null)
    override suspend fun clearBiometricSetup(): TResult<TBoolean> = ResultSuccessDto(null)
    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()
    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> =
        ResultSuccessDto(null)
}
