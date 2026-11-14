package app.tenshu.control.ui.screens.sync

import app.tenshu.error.ErrorCopyKey
import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.sync.HostPairingMonitor
import org.tenshu.core.file.sync.SyncPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
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
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.core.core.TContext
import org.tenshu.identity.Device
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.GetPendingPairingRequests
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.PairingDeviceRemovedError
import org.tenshu.sync.PairingApproverRevokedError
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingOfferApprovalPrompt
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Failure-path coverage for the §4a pairing-as-requester wizard. The
 * end-to-end [org.tenshu.systemtest.PairingSystemTest] only exercises the
 * happy path (relay accepts the request, SAS surfaces, host approves), so
 * none of these scenarios were caught before:
 *
 *  - `InitiatePairing` returning `TResult.Failure` (the proxy catch-all,
 *    `LocalTaskExecutor.executeAsync`, converts an op's `TenshuException` to
 *    a `Failure`, so this — not a throw — is what a relay rejection looks
 *    like at this boundary; the in-process pairing call has no wire/parser,
 *    so it never throws across the repository boundary).
 *  - handshake timeout vs. completion.
 *
 * NB: none of these cover an op that *hangs* (suspends without ever
 * returning) — no catch can convert that. The relay HttpClient lacking a
 * request timeout is the live candidate for the reported "Computing"
 * hang; that's a transport-config fix, not a VM one.
 *
 * The VM owns no IO and no relay; the SAS it shows is computed proxy-side
 * and returned by `initiatePairing`, so faking [SyncRepository] is the whole
 * seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingWizardViewModelTest {

    private val context: TContext = run {
        val registry = createCoreTestRegistry()
        registry.registerConfig(syncServiceCommonModule)
        createTestContext(schemaRegistry = registry)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Drive the wizard up to the point where `confirmPairing` runs. */
    private fun PairingWizardViewModel.toNameDevice() {
        onQrScanned(TEST_JOIN_URI)
    }

    @Test
    fun initiationSucceeds_surfacesSas_thenHandshakeCompletes_reachesSuccess() = vmTest { vms ->
        val initiation = PairingInitiation.create(
            context = context,
            publicKey = ByteArray(32),
            sas = "482913",
        )
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(initiation),
            awaitHandle = FakeTaskHandle(result = ResultSuccessDto(TBooleanDto(true))),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Laptop")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("482913", state.sas, "the proxy-computed SAS must surface to the UI")
        assertEquals(PairingStep.Success, state.step, "true handshake → Success step")
        assertTrue(state.isPairingComplete)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun initiationReturnsFailure_surfacesError_andDoesNotHangOnComputing() = vmTest { vms ->
        val repo = FakeSyncRepository(
            initiateResult = ResultFailureDto(GenericErrorDto("relay unreachable")),
            // onSuccess never fires → awaitPairing must never be reached.
            awaitHandle = null,
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Laptop")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<PairingError.Unexpected>(state.error, "the failure must reach the user as a typed value")
        assertFalse(state.isLoading, "spinner must stop on failure")
        assertNull(state.sas, "no code was produced")
        assertEquals(0, repo.awaitPairingCalls, "handshake wait must not start on a failed initiation")
    }

    @Test
    fun handshakeTimesOut_surfacesError() = vmTest { vms ->
        val initiation = PairingInitiation.create(
            context = context,
            publicKey = ByteArray(32),
            sas = "100200",
        )
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(initiation),
            // Success(false) = proxy-side ~5-min budget elapsed.
            awaitHandle = FakeTaskHandle(result = ResultSuccessDto(TBooleanDto(false))),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Laptop")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("100200", state.sas, "SAS still shown while waiting")
        assertFalse(state.isLoading)
        assertTrue(state.error != null, "a timed-out handshake surfaces an error")
        assertFalse(state.isPairingComplete)
    }

    @Test
    fun backOut_whileWaiting_cancelsHandshakeHandle_andResets() = vmTest { vms ->
        val initiation = PairingInitiation.create(
            context = context,
            publicKey = ByteArray(32),
            sas = "777888",
        )
        // A handle whose await() suspends until cancelled — models the
        // "waiting for the host to approve" state.
        val handle = FakeTaskHandle(result = null)
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(initiation),
            awaitHandle = handle,
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Laptop")
        advanceUntilIdle()
        assertEquals("777888", state(vm).sas)

        vm.onBackToScanStart()
        advanceUntilIdle()

        assertTrue(handle.cancelled, "backing out must cancel the in-flight handshake wait")
        val state = state(vm)
        assertEquals(PairingStep.ScanQr, state.step, "back-out resets to the scan entry (PickMethod subsumed)")
        assertNull(state.sas)
        assertNull(state.error, "a user back-out is not an error")
    }

    // --- Role-aware scanner dispatch (sync-pairing-inverted-direction.md §5.2) ---

    @Test
    fun onQrScanned_retiredPairHost_surfacesUnknownRoleError() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = null)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        // The exact pre-flag-day emitter shape — pinned so the removal stays deliberate.
        vm.onQrScanned("tenshu://pair?v=1&d=tp1qqqqq&ownerId=host-owner&deviceId=host-device")

        val state = vm.uiState.value
        assertEquals(PairingScanError.UnknownRole, state.scanError, "retired host is just an unknown role now")
        assertEquals(PairingStep.ScanQr, state.step, "a rejected scan must not advance the wizard")
    }

    @Test
    fun onQrScanned_offerCode_drivesResponderApproval() = vmTest { vms ->
        // §5.2: scanning a new device's offer QR flips THIS device into the responder role —
        // the proxy parses it + derives the SAS, and the UI shows the admit prompt.
        val prompt = PairingOfferApprovalPrompt.create(
            context = context,
            pairingSecretHash = "hash-abc",
            deviceName = "New Laptop",
            sas = "482913",
        )
        val repo = FakeSyncRepository(offerPromptResult = ResultSuccessDto(prompt))
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(PairingStep.ResponderApproval, state.step, "an offer QR drives the responder flow")
        assertEquals("New Laptop", state.responderDeviceName)
        assertEquals("482913", state.responderSas, "the proxy-derived SAS surfaces to the admit prompt")
        assertEquals("hash-abc", state.responderSecretHash)
        assertNull(state.scanError)
        assertFalse(state.isLoading)
    }

    @Test
    fun approveOffer_admitsScannedDevice_reachesResponderSuccess() = vmTest { vms ->
        val prompt = PairingOfferApprovalPrompt.create(
            context = context,
            pairingSecretHash = "hash-xyz",
            deviceName = "New Laptop",
            sas = "100200",
        )
        val repo = FakeSyncRepository(
            offerPromptResult = ResultSuccessDto(prompt),
            approveOfferResult = ResultSuccessDto(TBooleanDto(true)),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()
        vm.approveOffer()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(PairingStep.ResponderSuccess, state.step, "a true admit → the device-added terminal")
        assertEquals("hash-xyz", repo.lastApproveHash, "approve must thread the scanned pairingSecretHash")
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun approveOffer_relayRejects_surfacesError_staysOnApproval() = vmTest { vms ->
        val prompt = PairingOfferApprovalPrompt.create(context, "hash-1", "New Laptop", "111222")
        val repo = FakeSyncRepository(
            offerPromptResult = ResultSuccessDto(prompt),
            approveOfferResult = ResultFailureDto(GenericErrorDto("relay rejected the admission")),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()
        vm.approveOffer()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<PairingError.Unexpected>(state.error)
        assertEquals(PairingStep.ResponderApproval, state.step, "a failed admit keeps the prompt up to retry")
        assertFalse(state.isLoading)
    }

    /**
     * The relay refuses THIS device's approval — its admission capability was revoked (§7j.5).
     * ⚠ Never the generic error: both approvers showed "Something went wrong" on 2026-11-11.
     */
    @Test
    fun approveOffer_approversCapabilityRevoked_saysSo() = vmTest { vms ->
        val prompt = PairingOfferApprovalPrompt.create(context, "hash-1", "Desktop", "011617")
        val repo = FakeSyncRepository(
            offerPromptResult = ResultSuccessDto(prompt),
            approveOfferResult = ResultFailureDto(ApproverRevokedErrorDto()),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()
        vm.approveOffer()
        advanceUntilIdle()

        assertEquals(PairingError.ApproverRevoked, vm.uiState.value.error)
    }

    /**
     * The relay refuses the device being added because it was removed from this realm before.
     * ⚠ Must not render as the generic error: that said nothing, and every retry was refused the
     * same way (`admission-revocation-names-the-capability.md` §7h).
     */
    @Test
    fun approveOffer_deviceBeingAddedWasRemoved_saysSo() = vmTest { vms ->
        val prompt = PairingOfferApprovalPrompt.create(context, "hash-1", "Desktop", "011617")
        val repo = FakeSyncRepository(
            offerPromptResult = ResultSuccessDto(prompt),
            approveOfferResult = ResultFailureDto(DeviceRemovedErrorDto()),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()
        vm.approveOffer()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(PairingError.DeviceRemoved, state.error)
        assertEquals(PairingStep.ResponderApproval, state.step)
        assertFalse(state.isLoading)
    }

    @Test
    fun denyOffer_notifiesOfferer_andResetsToScanner() = vmTest { vms ->
        val prompt = PairingOfferApprovalPrompt.create(context, "hash-2", "New Laptop", "333444")
        val repo = FakeSyncRepository(offerPromptResult = ResultSuccessDto(prompt))
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()
        vm.denyOffer()
        advanceUntilIdle()

        assertEquals(1, repo.denyOfferCalls, "deny must POST a signed denied so the offerer's poll ends")
        val state = vm.uiState.value
        assertEquals(PairingStep.ScanQr, state.step, "deny returns to the scanner")
        assertNull(state.responderSecretHash)
    }

    @Test
    fun onQrScanned_offerRespondFails_returnsToScannerWithMalformed() = vmTest { vms ->
        val repo = FakeSyncRepository(
            offerPromptResult = ResultFailureDto(GenericErrorDto("bad offer payload")),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(PairingStep.ScanQr, state.step, "a failed respond drops back to the scanner")
        assertEquals(PairingScanError.Malformed, state.scanError)
    }

    @Test
    fun onQrScanned_unknownRoleHost_surfacesUpdateAppError() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = null)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned("tenshu://handover?k=AAAA&s=BBBB")

        assertEquals(PairingScanError.UnknownRole, vm.uiState.value.scanError)
    }

    @Test
    fun onQrScanned_offerScanBurst_mintsExactlyOneResponderSession() = vmTest { vms ->
        // Camera scanners report once per analyzed frame: one physical scan = a burst of
        // identical results. Each respondToPairingOffer mints a fresh ephemeral key (and
        // thus a fresh SAS), so the burst used to flash a different SAS per frame.
        val prompt = PairingOfferApprovalPrompt.create(context, "hash-burst", "New Laptop", "482913")
        val repo = FakeSyncRepository(offerPromptResult = ResultSuccessDto(prompt))
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_OFFER_URI)
        vm.onQrScanned(TEST_OFFER_URI)
        vm.onQrScanned(TEST_OFFER_URI)
        advanceUntilIdle()

        assertEquals(1, repo.respondOfferCalls, "a scanner burst must mint exactly one responder session")
        val state = vm.uiState.value
        assertEquals(PairingStep.ResponderApproval, state.step)
        assertEquals("482913", state.responderSas, "the single session's SAS is the one on screen")
    }

    @Test
    fun onQrScanned_joinScanBurst_staysOnNameDeviceUnchanged() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = null)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned(TEST_JOIN_URI)
        val afterFirst = vm.uiState.value
        assertEquals(PairingStep.NameDevice, afterFirst.step)

        vm.onQrScanned(TEST_JOIN_URI)
        assertEquals(afterFirst, vm.uiState.value, "burst frames past the first must be no-ops")
    }

    @Test
    fun onQrScanned_success_clearsAPriorScanError() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = null)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))

        vm.onQrScanned("garbage")
        assertEquals(PairingScanError.Malformed, vm.uiState.value.scanError)

        vm.onQrScanned(TEST_JOIN_URI)
        val state = vm.uiState.value
        assertNull(state.scanError, "a good scan clears the previous error")
        assertEquals(PairingStep.NameDevice, state.step)
    }

    // --- Phase 2: unified Scan / Show segmented control + host-session delegation ---

    @Test
    fun onSelectShow_startsHostSession_andSurfacesPairingString() = vmTest { vms ->
        val status = SyncStatus.create(
            context = context,
            deviceId = "this-device",
            ownerId = "owner-1",
            isEnabled = true,
            pairingString = "tenshu://join?o=o&d=SHOWME&k=K&s=S",
        )
        val repo = FakeSyncRepository(startPairingStatus = status, syncStatus = pairedStatus())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        vm.onSelectShow()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Show, state.mode, "selecting Show flips the active tab")
        assertTrue(repo.startPairingCalls >= 1, "Show must adopt the host role via startPairing")
        assertFalse(state.offerRole, "a realm-bound device hosts; it never offers")
        assertEquals(
            "tenshu://join?o=o&d=SHOWME&k=K&s=S",
            state.hostPairingString,
            "the monitor's pairing string must be mirrored into uiState",
        )
    }

    @Test
    fun onSelectScan_stopsHostSession_andResetsToScanEntry() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        vm.onSelectShow()
        runCurrent()

        vm.onSelectScan()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Scan, state.mode, "selecting Scan flips back to the Scan tab")
        assertEquals(PairingStep.ScanQr, state.step, "Scan resets to the scanner entry step")
        assertTrue(repo.stopPairingCalls >= 1, "leaving the host role must stop the session")
    }

    @Test
    fun hostConnectionLost_mirrorsTheMonitor() = vmTest { vms ->
        // The monitor only polls (and can raise connectionLost) while isPairing — start a
        // session, then fail the poll, and confirm uiState mirrors the banner state.
        val status = SyncStatus.create(
            context = context,
            deviceId = "this-device",
            ownerId = "owner-1",
            isEnabled = true,
            pairingString = "tenshu://join?o=o&d=X&k=K&s=S",
        )
        val repo = FakeSyncRepository(startPairingStatus = status, syncStatus = pairedStatus())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        vm.onSelectShow()
        runCurrent()
        assertFalse(vm.uiState.value.hostConnectionLost, "no failure yet")

        repo.failNextPoll = true
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(vm.uiState.value.hostConnectionLost, "a failed poll must surface in uiState")
    }

    // --- Host-side approval mirrored into uiState (for surfaces with no app-global modal) ---
    //
    // Compose renders the approval as an App-root modal off the monitor, so the wizard state never
    // carried it — and Control-on-iOS, which mounts no Compose tree, therefore showed a QR, polled
    // the request it produced, and had nowhere to put it. These pin the mirror + both resolutions.

    @Test
    fun hostRequest_surfacesDeviceNameAndSas() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()

        vm.onSelectShow()
        runCurrent()
        assertNull(vm.uiState.value.hostRequestDeviceName, "nobody has scanned the code yet")

        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()

        val state = vm.uiState.value
        assertEquals("New Phone", state.hostRequestDeviceName, "the joiner's request must reach the UI")
        assertEquals("482913", state.hostRequestSas, "the SAS the user compares against the joiner")
        assertNull(state.hostApprovalError)
    }

    @Test
    fun approveHostRequest_admitsTheJoiner_andReachesTheHostSuccessTerminal() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()
        assertEquals("New Phone", vm.uiState.value.hostRequestDeviceName, "the prompt must be up first")

        vm.approveHostRequest()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(listOf("joiner-1"), repo.approvedDeviceIds, "approve must admit the requesting device")
        assertNull(state.hostRequestDeviceName, "a resolved request clears the prompt")
        // Load-bearing: approval also clears hostPairingString, so a surface without this flag
        // falls back to its "no code yet" spinner and never says the pairing worked.
        assertTrue(state.hostPairingComplete, "approval is a terminal success for the session")
    }

    @Test
    fun approveHostRequest_failure_keepsThePromptUp_andSaysWhy() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        repo.approveRequestResult = ResultFailureDto(GenericErrorDto("This device can't add devices"))
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()

        vm.approveHostRequest()
        runCurrent()

        val state = vm.uiState.value
        assertIs<PairingError.Unexpected>(state.hostApprovalError, "the reason must be shown, typed")
        assertEquals("New Phone", state.hostRequestDeviceName, "a failed approve must not silently dismiss")
        assertFalse(state.hostPairingComplete)
    }

    @Test
    fun denyHostRequest_endsTheSession() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()
        assertEquals("New Phone", vm.uiState.value.hostRequestDeviceName, "the prompt must be up first")

        vm.denyHostRequest()
        runCurrent()

        val state = vm.uiState.value
        assertNull(state.hostRequestDeviceName, "deny clears the prompt")
        assertNull(state.hostPairingString, "deny burns the whole host session, not just this request")
        assertFalse(state.hostPairingComplete, "a denial is not a success")
        assertTrue(repo.stopPairingCalls >= 1)
    }

    // --- A host session that ends with no device added must not strand the Show tab ---
    //
    // The Show surface renders "no pairing string" as "the session is still starting" — a spinner.
    // Every ending but approval leaves exactly that state behind, so each one has to leave the tab.

    @Test
    fun onStopShowing_inHostRole_returnsToScan() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        assertEquals(AddDeviceMode.Show, vm.uiState.value.mode)
        assertTrue(vm.uiState.value.hostPairingString != null, "the code must be up first")

        vm.onStopShowing()
        assertEquals(AddDeviceMode.Scan, vm.uiState.value.mode, "stop leaves at once, not after the IPC")
        runCurrent()

        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Scan, state.mode, "a stopped host session must not sit on the Show spinner")
        assertNull(state.hostPairingString)
        assertTrue(repo.stopPairingCalls >= 1)
    }

    @Test
    fun hostSessionExpiring_returnsToScan() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()

        // The monitor's bounded waiting window (2 min) runs out with nobody having scanned.
        advanceTimeBy(121_000L)
        runCurrent()

        val state = vm.uiState.value
        assertNull(state.hostPairingString, "the expired session's code is gone")
        assertEquals(AddDeviceMode.Scan, state.mode, "…so the tab that showed it must not spin on its absence")
    }

    @Test
    fun denyHostRequest_returnsToScan() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()

        vm.denyHostRequest()
        runCurrent()

        assertEquals(AddDeviceMode.Scan, vm.uiState.value.mode)
    }

    @Test
    fun approveHostRequest_staysOnShow_forTheSuccessTerminal() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()

        vm.approveHostRequest()
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.hostPairingComplete)
        assertEquals(AddDeviceMode.Show, state.mode, "approval also ends the session, but its terminal lives on Show")
    }

    @Test
    fun openedWhileAHostSessionIsLive_startsOnShow_withItsCode() = vmTest { vms ->
        // The dashboard's "in Pairing" row routes here while a session is being served.
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        monitor.startPairing()
        runCurrent()

        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Show, state.mode, "re-entry must land on the live session, not the scanner")
        assertTrue(state.hostPairingString != null, "…showing the code it is serving")
        assertEquals(1, repo.startPairingCalls, "re-entry adopts the session; it must not mint a second one")
    }

    // --- A host start that yields no code: say so, and offer a retry ---

    @Test
    fun aFailedHostStart_showsTheError_notTheStartingSpinner() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = pairedStatus())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        vm.onSelectShow()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Show, state.mode, "the user stays on the tab they chose")
        assertFalse(state.offerRole)
        assertNull(state.hostPairingString)
        assertIs<PairingError.Unexpected>(state.error, "the failure must reach the Show tab")
        assertFalse(monitor.isPairing.value, "a failed start is not a session")
        assertNull(monitor.remainingSeconds.value, "…so no window counts down")

        // The live → ended edge never fires for a session that never started, so the tab stays put
        // showing the failure rather than bouncing to Scan after the window.
        advanceTimeBy(125_000L)
        runCurrent()
        assertEquals(AddDeviceMode.Show, vm.uiState.value.mode)
        assertIs<PairingError.Unexpected>(vm.uiState.value.error)
        assertEquals(0, repo.stopPairingCalls, "there is no session to time out")
    }

    @Test
    fun aHostStartWithNoCode_isAFailureToo() = vmTest { vms ->
        // Success carrying no pairing string — the shape a realm-less proxy answers with.
        val repo = FakeSyncRepository(startPairingStatus = pairedStatus(), syncStatus = pairedStatus())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        vm.onSelectShow()
        runCurrent()

        assertEquals(PairingError.Unexpected(ErrorCopyKey.UNEXPECTED), vm.uiState.value.error)
        assertNull(vm.uiState.value.hostPairingString)
        assertFalse(monitor.isPairing.value)
    }

    @Test
    fun retryingAFailedHostStart_showsTheCode_andClearsTheError() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = pairedStatus())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        assertIs<PairingError.Unexpected>(vm.uiState.value.error)

        repo.startPairingResult = ResultSuccessDto(hostStatus())
        vm.onSelectShow()
        runCurrent()

        val state = vm.uiState.value
        assertNull(state.error, "a successful retry clears the failure")
        assertEquals("tenshu://join?o=o&d=X&k=K&s=S", state.hostPairingString)
        assertTrue(monitor.isPairing.value)
        assertEquals(2, repo.startPairingCalls, "retry goes through the same start")
    }

    @Test
    fun retryingAHostStartThatFailsAgain_keepsShowingTheError() = vmTest { vms ->
        // ⚠ The same failure twice leaves the monitor's error equal to what it was, so a collector
        // may never see a change — and the retry has just cleared `error`. Without the post-start
        // re-read the tab would fall back to its spinner.
        val repo = FakeSyncRepository(syncStatus = pairedStatus())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        val first = vm.uiState.value.error
        assertIs<PairingError.Unexpected>(first)

        vm.onSelectShow()
        runCurrent()

        assertEquals(first, vm.uiState.value.error, "a second failure must still be shown")
        assertEquals(2, repo.startPairingCalls)
    }

    @Test
    fun leavingAFailedHostStartForScan_clearsTheError() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = pairedStatus())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()
        vm.onSelectShow()
        runCurrent()

        vm.onStopShowing()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Scan, state.mode, "Stop still leaves for Scan with no session to stop")
        assertEquals(PairingStep.ScanQr, state.step)
        assertNull(state.error, "the start failure must not follow the user onto the scanner")
    }

    @Test
    fun aHostStartFailingAfterTheUserLeftForScan_doesNotReachTheScanner() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = pairedStatus())
        repo.startPairingResult = ResultFailureDto(GenericErrorDto("simulated start failure"))
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        // The start is still in flight when the user changes their mind.
        vm.onSelectShow()
        vm.onSelectScan()
        runCurrent()

        assertTrue(monitor.startError.value != null, "the start must have failed after the tab switch")
        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Scan, state.mode)
        assertNull(state.error, "`error` on Scan belongs to the scanner, not to a start the user walked away from")
    }

    @Test
    fun aNormalHostStart_reportsNoError() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        val monitor = HostPairingMonitor(repo, scope = backgroundScope)
        val vm = vms.own(PairingWizardViewModel(repo, monitor))
        runCurrent()

        vm.onSelectShow()
        runCurrent()

        val state = vm.uiState.value
        assertNull(state.error)
        assertEquals("tenshu://join?o=o&d=X&k=K&s=S", state.hostPairingString)
        assertTrue(monitor.isPairing.value)
    }

    @Test
    fun aDeviceTheRelayRefuses_isNotReportedAsAFailedStart() = vmTest { vms ->
        // The refusal is discovered by a successful start's poll and switches the tab to the offer
        // role in place — a different ending, which must not also show a start failure.
        val repo = FakeSyncRepository(
            startPairingStatus = hostStatus(),
            syncStatus = pairedStatus(),
            offerSession = offerSession(),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))
        runCurrent()
        repo.pollRefusesDevice = true
        vm.onSelectShow()
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerRole)
        assertNull(state.error, "an unrecognised device is not a failed start")

        vm.onStopShowing()
        runCurrent()
    }

    @Test
    fun openedWithNoHostSession_startsOnScan() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = pairedStatus())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()

        assertEquals(AddDeviceMode.Scan, vm.uiState.value.mode)
    }

    // --- Track D: the realm a device just joined cannot sync, and the terminal says so ---

    @Test
    fun joinerOnAnUnfundedRealm_isToldOnTheSuccessStep() = vmTest { vms ->
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(
                PairingInitiation.create(context, ByteArray(32), "482913")
            ),
            awaitHandle = FakeTaskHandle(result = ResultSuccessDto(TBooleanDto(true))),
        )
        // The relay ANSWERED and refused the write — this is not an unreachable relay.
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Phone")
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(PairingStep.Success, vm.uiState.value.step)
        assertFalse(
            vm.uiState.value.realmUnfunded,
            "before the settle a refusal is indistinguishable from the join-race",
        )

        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(vm.uiState.value.realmUnfunded, "a refusal that outlives the settle is the real thing")
        assertEquals(PairingStep.Success, vm.uiState.value.step, "the device DID join; this is not a failure")
    }

    @Test
    fun joinerWhoseRelayIsUnreachable_isNeverToldTheRealmIsUnfunded() = vmTest { vms ->
        // ⚠⚠ The blip guard. `offline` is what an unreachable relay produces; only a relay that
        // ANSWERED and refused produces `needs_attention`. A network drop must never accuse a
        // paying user's realm.
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(
                PairingInitiation.create(context, ByteArray(32), "482913")
            ),
            awaitHandle = FakeTaskHandle(result = ResultSuccessDto(TBooleanDto(true))),
        )
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_OFFLINE)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Phone")
        advanceTimeBy(20_000L)
        runCurrent()

        assertTrue(vm.uiState.value.isPairingComplete)
        assertFalse(vm.uiState.value.realmUnfunded, "offline is not unfunded")
    }

    @Test
    fun joinerWithNoHealthRowYet_staysQuiet() = vmTest { vms ->
        // Every uncertainty leaves the wizard quiet — `false` means "not established".
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(
                PairingInitiation.create(context, ByteArray(32), "482913")
            ),
            awaitHandle = FakeTaskHandle(result = ResultSuccessDto(TBooleanDto(true))),
        )
        repo.health = null
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Phone")
        advanceTimeBy(20_000L)
        runCurrent()

        assertFalse(vm.uiState.value.realmUnfunded, "no row yet is not a verdict")
    }

    @Test
    fun hostThatJustAdmittedADevice_isToldItsRealmCannotSync() = vmTest { vms ->
        // D2: the host knows first — its own pushes have been refused all along.
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()

        vm.approveHostRequest()
        runCurrent()
        assertTrue(vm.uiState.value.hostPairingComplete)

        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(vm.uiState.value.realmUnfunded, "the host says it on its own 'Device added' terminal")
    }

    // --- Auto-continue arming (control-pairing-ux.md §9): settled state, never a guess ---

    @Test
    fun hostTerminal_settlesItsFundingCheckWithoutTheJoinRaceDelay() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_ONLINE)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()

        vm.approveHostRequest()
        runCurrent()
        assertTrue(vm.uiState.value.hostPairingComplete)
        advanceTimeBy(100L)
        runCurrent()

        assertTrue(vm.uiState.value.fundingCheckSettled, "D2 has no join race — no 6 s settle on the host")
        assertFalse(vm.uiState.value.realmUnfunded)
        assertTrue(vm.uiState.value.autoContinueArmed, "a settled, funded host terminal may auto-continue")
    }

    @Test
    fun joinerTerminal_settlesOnlyAfterTheJoinRaceSettle() = vmTest { vms ->
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(PairingInitiation.create(context, ByteArray(32), "482913")),
            awaitHandle = FakeTaskHandle(result = ResultSuccessDto(TBooleanDto(true))),
        )
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_ONLINE)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Phone")
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(PairingStep.Success, vm.uiState.value.step)
        assertFalse(vm.uiState.value.fundingCheckSettled, "inside the settle the body may still change")
        assertFalse(vm.uiState.value.autoContinueArmed, "…so nothing may count down yet")

        advanceTimeBy(6_000L)
        runCurrent()
        assertTrue(vm.uiState.value.fundingCheckSettled)
        assertTrue(vm.uiState.value.autoContinueArmed)
    }

    @Test
    fun unfundedTerminal_settlesButNeverArms() = vmTest { vms ->
        val repo = FakeSyncRepository(
            initiateResult = ResultSuccessDto(PairingInitiation.create(context, ByteArray(32), "482913")),
            awaitHandle = FakeTaskHandle(result = ResultSuccessDto(TBooleanDto(true))),
        )
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.toNameDevice()

        vm.confirmPairing("My Phone")
        advanceTimeBy(20_000L)
        runCurrent()
        assertTrue(vm.uiState.value.fundingCheckSettled)
        assertTrue(vm.uiState.value.realmUnfunded)
        assertFalse(vm.uiState.value.autoContinueArmed, "the 'Set up storage' screen never counts down")
    }

    @Test
    fun healthReadThatNeverAnswers_stillSettlesAtTheTimeout() = vmTest { vms ->
        val repo = FakeSyncRepository(startPairingStatus = hostStatus(), syncStatus = pairedStatus())
        repo.healthOverride = kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        runCurrent()
        vm.onSelectShow()
        runCurrent()
        repo.pendingRequests = listOf(joinerRequest())
        advanceTimeBy(2_100L)
        runCurrent()
        vm.approveHostRequest()
        runCurrent()

        advanceTimeBy(3_000L)
        runCurrent()
        assertFalse(vm.uiState.value.fundingCheckSettled, "still inside the read's bound")
        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(vm.uiState.value.fundingCheckSettled, "a silent read settles at the timeout")
        assertFalse(vm.uiState.value.realmUnfunded, "silence is not a verdict")
    }

    @Test
    fun offerer_scannedBeforeApproved_marksTheSasShown_andArmsOnceSettled() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_ONLINE)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))
        vm.onSelectShow()
        runCurrent()

        repo.offerStatusResult = ResultSuccessDto(OfferStatus.create(context, OfferStatus.SCANNED, sas = "482913"))
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(vm.uiState.value.offerScanned)
        assertTrue(vm.uiState.value.offerSasShownBeforeApproval)

        repo.offerStatusResult = ResultSuccessDto(OfferStatus.create(context, OfferStatus.APPROVED, sas = "482913"))
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(vm.uiState.value.offerPaired)
        assertTrue(vm.uiState.value.offerSasShownBeforeApproval, "sticky across the approval")
        assertFalse(vm.uiState.value.autoContinueArmed, "the offerer keeps the join-race settle")

        advanceTimeBy(7_000L)
        runCurrent()
        assertTrue(vm.uiState.value.autoContinueArmed)
    }

    @Test
    fun offerer_approvedWithNoScannedAnnounce_staysManual() = vmTest { vms ->
        // T-P-07: the announce is best-effort. Without it the paired screen's SAS is the only
        // comparison there was — that screen must not leave by itself.
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_ONLINE)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))
        vm.onSelectShow()
        runCurrent()

        repo.offerStatusResult = ResultSuccessDto(OfferStatus.create(context, OfferStatus.APPROVED, sas = "482913"))
        advanceTimeBy(2_100L)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(vm.uiState.value.offerPaired)
        assertEquals("482913", vm.uiState.value.offerSas)
        assertTrue(vm.uiState.value.fundingCheckSettled)
        assertFalse(vm.uiState.value.offerSasShownBeforeApproval, "approved's SAS is not a pre-approval sighting")
        assertFalse(vm.uiState.value.autoContinueArmed)
    }

    @Test
    fun offerer_sasShownFlag_resetsWithTheSession() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))
        vm.onSelectShow()
        runCurrent()
        repo.offerStatusResult = ResultSuccessDto(OfferStatus.create(context, OfferStatus.SCANNED, sas = "482913"))
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(vm.uiState.value.offerSasShownBeforeApproval)

        vm.onStopShowing()
        runCurrent()
        assertFalse(vm.uiState.value.offerSasShownBeforeApproval, "stopping ends the session")

        repo.offerStatusResult = null
        vm.onSelectShow()
        runCurrent()
        assertTrue(vm.uiState.value.offerRole)
        assertFalse(vm.uiState.value.offerSasShownBeforeApproval, "a new session starts unsighted")
        vm.onStopShowing()
        runCurrent()
    }

    @Test
    fun sasResponderSuccess_neverArms_butTheHubResponderDoes() = vmTest { vms ->
        val repo = FakeSyncRepository(
            offerPromptResult = ResultSuccessDto(
                PairingOfferApprovalPrompt.create(
                    context = context,
                    pairingSecretHash = "hash-1",
                    deviceName = "New Laptop",
                    sas = "482913",
                ),
            ),
            approveOfferResult = ResultSuccessDto(TBooleanDto(true)),
        )
        repo.health = healthRow(SyncHealthStatus.CONNECTIVITY_ONLINE)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope)))
        vm.onQrScanned(TEST_OFFER_URI)
        runCurrent()
        vm.approveOffer()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(PairingStep.ResponderSuccess, vm.uiState.value.step)
        assertTrue(vm.uiState.value.fundingCheckSettled)
        assertFalse(vm.uiState.value.autoContinueArmed, "the SAS responder screen is the side-by-side check")
        assertTrue(vm.uiState.value.copy(responderIsHub = true).autoContinueArmed, "no SAS in the hub flow")
    }

    private fun healthRow(connectivity: String): SyncHealthStatus = SyncHealthStatus.create(
        context = context,
        connectivity = connectivity,
        isSyncing = false,
        pendingBlockCount = 5,
        lastSyncedAtMillis = 0,
    )

    private fun hostStatus(): SyncStatus = SyncStatus.create(
        context = context,
        deviceId = "this-device",
        ownerId = "owner-1",
        isEnabled = true,
        pairingString = "tenshu://join?o=o&d=X&k=K&s=S",
    )

    private fun joinerRequest(): PairingRequest = PairingRequest.create(
        context = context,
        deviceId = "joiner-1",
        deviceName = "New Phone",
        publicKey = ByteArray(32),
        signingPublicKey = ByteArray(32),
        pairingSecretHash = "secret-hash",
        // Enriched host-side by the proxy; the relay never sees it.
        sas = "482913",
    )

    // --- Offerer role: Show tab on an UNPAIRED device (sync-pairing-inverted-direction.md §5.1) ---

    private fun pairedStatus(): SyncStatus = SyncStatus.create(
        context = context,
        deviceId = "this-device",
        ownerId = "owner-1",
        isEnabled = true,
        pairingString = null,
    )

    private fun unpairedStatus(): SyncStatus = SyncStatus.create(
        context = context,
        deviceId = "this-device",
        ownerId = null,
        isEnabled = false,
        pairingString = null,
    )

    private fun offerSession(): OfferPairingSession = OfferPairingSession.create(
        context = context,
        offerUri = "tenshu://offer?k=AAAA&s=BBBB",
        pairingSecretHash = "offer-hash-1",
    )

    @Test
    fun aDeviceTheRelayRefuses_switchesShowToTheOfferRole() = vmTest { vms ->
        // ⚠⚠ The role must turn on whether the RELAY accepts this device, not on whether it holds a
        // realm locally. A device whose registration the relay lost still has an ownerId, so it used
        // to take the host path — where its poll 401s, the joiner's request is never surfaced, and
        // both sides hang (observed 2026-08-17 on a desktop after a relay wipe). Such a device is,
        // for pairing purposes, a new device: it must be re-admitted, which is what the offer flow
        // does. This replaces the "go and scan instead" dialog with the ordinary add-a-device
        // gesture — the broken device shows a code and gets approved.
        val repo = FakeSyncRepository(
            startPairingStatus = hostStatus(),
            syncStatus = pairedStatus(),
            offerSession = offerSession(),
        )
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        // The relay refuses us, and the repair cannot help (no master identity — a joiner).
        repo.pollRefusesDevice = true
        vm.onSelectShow()
        runCurrent()
        // The host attempt is how this is discovered, so it happens once…
        assertEquals(1, repo.startPairingCalls, "the first attempt is how the refusal is discovered")
        advanceTimeBy(2_100L)
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerRole, "a refused device must switch to the offerer role")
        assertEquals(1, repo.offerPairingCalls, "…and mint exactly one offer session")
        assertEquals("tenshu://offer?k=AAAA&s=BBBB", state.offerUri, "the offer QR must surface")

        vm.onStopShowing()
        runCurrent()
    }

    @Test
    fun onSelectShow_unpaired_startsOfferSession_notHostSession() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        vm.onSelectShow()
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerRole, "no owner → the Show tab takes the offerer role")
        assertEquals("tenshu://offer?k=AAAA&s=BBBB", state.offerUri, "the offer QR payload must surface")
        assertEquals(1, repo.offerPairingCalls)
        assertEquals(0, repo.startPairingCalls, "an unpaired device must never arm the host session")
        assertFalse(state.offerPaired)

        // Tear the still-polling session down so runTest doesn't trip over the live loop.
        vm.onStopShowing()
        runCurrent()
    }

    @Test
    fun offerApproved_surfacesPairedTerminal_withSas() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        vm.onSelectShow()
        runCurrent()

        repo.offerStatusResult = ResultSuccessDto(
            org.tenshu.sync.OfferStatus.create(context, org.tenshu.sync.OfferStatus.APPROVED, sas = "482913"),
        )
        advanceTimeBy(2_100L)
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerPaired, "approved → the proxy installed the keys; terminal success")
        assertEquals("482913", state.offerSas, "the SAS surfaces for post-hoc comparison")
        assertEquals("offer-hash-1", repo.lastOfferPollHash, "the poll must thread the minted hash")
    }

    @Test
    fun offerDenied_surfacesDeniedTerminal() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        vm.onSelectShow()
        runCurrent()

        repo.offerStatusResult = ResultSuccessDto(
            org.tenshu.sync.OfferStatus.create(context, org.tenshu.sync.OfferStatus.DENIED),
        )
        advanceTimeBy(2_100L)
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerDenied, "denied → the declined terminal")
        assertFalse(state.offerPaired)
        assertNull(state.offerUri, "a denied offer's QR must leave the screen")
    }

    /**
     * F2′ on the offerer: the relay refused THIS device as a removed identity. Its own terminal —
     * not "connection lost" polled until the window closes, and not "failed" with a retry that every
     * attempt repeats (`admission-revocation-names-the-capability.md` §7i.5).
     */
    @Test
    fun offerRefusedAsThisDeviceRemoved_surfacesItsOwnTerminal() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        vm.onSelectShow()
        runCurrent()

        repo.offerStatusResult = ResultFailureDto(org.tenshu.sync.PairingThisDeviceRemovedError.create(context))
        advanceTimeBy(2_100L)
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerRemoved, "a removed identity is told as such")
        assertFalse(state.offerFailed, "not the retry terminal — a retry is refused identically")
        assertNull(state.offerUri, "the dead code leaves the screen")
    }

    /**
     * The refusal can land after SCANNED. The SAS must go with it, or the scanned step — shown
     * whenever a SAS is present — keeps the user on a code nothing will ever answer.
     */
    @Test
    fun offerRefusedAsThisDeviceRemoved_afterScanned_leavesTheScannedStep() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        vm.onSelectShow()
        runCurrent()
        repo.offerStatusResult = ResultSuccessDto(OfferStatus.create(context, OfferStatus.SCANNED, sas = "482913"))
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(vm.uiState.value.offerScanned, "precondition: on the scanned step")

        repo.offerStatusResult = ResultFailureDto(org.tenshu.sync.PairingThisDeviceRemovedError.create(context))
        advanceTimeBy(2_100L)
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerRemoved)
        assertFalse(state.offerScanned, "the scanned step must not outrank the refusal")
        assertNull(state.offerSas)
    }

    @Test
    fun offerMintFails_surfacesFailedTerminal() = vmTest { vms ->
        // offerSession = null → offerPairing resolves Success(null) = mint failure.
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = null)
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        vm.onSelectShow()
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.offerFailed, "a failed mint must not strand the tab on a spinner")
        assertNull(state.offerUri)
    }

    @Test
    fun onStopShowing_inOfferRole_cancelsSession_andReturnsToScan() = vmTest { vms ->
        val repo = FakeSyncRepository(syncStatus = unpairedStatus(), offerSession = offerSession())
        val vm = vms.own(PairingWizardViewModel(repo, HostPairingMonitor(repo, scope = backgroundScope), backgroundScope))

        vm.onSelectShow()
        runCurrent()
        assertTrue(vm.uiState.value.offerRole)

        vm.onStopShowing()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(AddDeviceMode.Scan, state.mode, "stopping an offer returns to the Scan tab")
        assertFalse(state.offerRole)
        assertTrue(repo.cancelOfferCalls >= 1, "stop must fire the proxy-side offer teardown")
    }

    private fun state(vm: PairingWizardViewModel) = vm.uiState.value
}

/** publicKey(32 bytes of 0) || "secret" → Bech32, matching `onQrScanned`'s decode. */
private val TEST_JOIN_URI: String =
    org.tenshu.core.common.util.PairingUri.joinUri(
        ownerId = "host-owner",
        hostDeviceId = "host-device",
        hostPublicKey = ByteArray(32),
        pairingSecret = "secret".encodeToByteArray(),
    )

/** A well-formed `tenshu://offer?…` a new device would show — the responder branch parses it. */
private val TEST_OFFER_URI: String =
    org.tenshu.core.common.util.PairingUri.offerUri(
        offererPublicKey = ByteArray(32) { 1 },
        offererSigningKey = ByteArray(32) { 2 },
        offererDeviceId = "new-device",
        pairingSecret = "offer-secret".encodeToByteArray(),
        deviceName = "New Laptop",
    )

/** Internal only because [FakeSyncRepository] takes one — it stays a pairing-test detail. */
internal class FakeTaskHandle(
    private val result: TResult<TBoolean>?,
) : TaskHandle<TBoolean> {
    override val taskId: String = "test-task"
    var cancelled: Boolean = false
        private set

    private val gate = CompletableDeferred<TResult<TBoolean>>()

    override suspend fun await(): TResult<TBoolean> = result ?: gate.await()

    override fun cancel() {
        cancelled = true
        gate.cancel()
    }
}

/**
 * Shared with [SyncDashboardViewModelTest] (same package + source set) — the two sync VMs sit
 * behind the same repository seam, and a second hand-written 30-member fake would drift.
 */
internal class FakeSyncRepository(
    private val initiateResult: TResult<PairingInitiation>? = null,
    private val awaitHandle: FakeTaskHandle? = null,
    // The SyncStatus startPairing reports (carries the host pairing string the monitor
    // mirrors into uiState.hostPairingString). Null = no pairing string surfaced.
    private val startPairingStatus: SyncStatus? = null,
    // The SyncStatus getSyncStatus reports — onSelectShow branches host-vs-offerer on its
    // ownerId (§5.1). Null models a fake that never answers (Success(null) → offer branch).
    private val syncStatus: SyncStatus? = null,
    // Offer-flow responder (§5.2): the prompt respondToPairingOffer resolves, and the result
    // approvePairingOffer returns. Null defaults model a successful parse / admit.
    private val offerPromptResult: TResult<PairingOfferApprovalPrompt>? = null,
    private val approveOfferResult: TResult<TBoolean>? = null,
    // Offer-flow offerer (§5.1): the session offerPairing mints. Null = mint failure.
    private val offerSession: OfferPairingSession? = null,
    // What `revokeDevice` reports. Null = the op accepted the revocation.
    private val revokeResult: TResult<Data?>? = null,
    // Thrown by `revokeDevice` instead of returning — models transport loss, which is a
    // throw rather than a Failure at this boundary.
    private val revokeThrows: Throwable? = null,
    // What `readmitDevice` reports. Null = the relay accepted the re-admission.
    private val readmitResult: TResult<Data?>? = null,
    // Thrown by `readmitDevice` instead of returning. ⚠ The shape that matters: all three of
    // `ReadmitDevice`'s guards report as THROWS carrying the sentence that says what to do
    // instead, so a fake that only ever returns a Failure cannot reach the unwrap under test.
    private val readmitThrows: Throwable? = null,
) : SyncRepository {

    var revokedDeviceIds: MutableList<String> = mutableListOf()
        private set

    var readmittedDeviceIds: MutableList<String> = mutableListOf()
        private set

    var awaitPairingCalls: Int = 0
        private set
    var cancelPairingCalls: Int = 0
        private set
    var startPairingCalls: Int = 0
        private set
    var stopPairingCalls: Int = 0
        private set
    var denyOfferCalls: Int = 0
        private set
    var respondOfferCalls: Int = 0
        private set
    var lastApproveHash: String? = null
        private set
    var offerPairingCalls: Int = 0
        private set
    var lastOfferDeviceName: String? = null
        private set
    var lastOfferPollHash: String? = null
        private set
    var cancelOfferCalls: Int = 0
        private set
    // One-shot: when true, the next pending-request poll fails (relay-401-shaped), exercising
    // the monitor's connection-lost path.
    var failNextPoll: Boolean = false
    // Settable mid-test: what the offerer's next getOfferStatus poll reports. Null models
    // "pending" (the VM treats an empty poll as transient and keeps polling).
    var offerStatusResult: TResult<OfferStatus>? = null

    /**
     * The live roster relation. ⚠ Settable so a test can emit a device joining from elsewhere —
     * the case [SyncDashboardRosterReactivityTest] pins. A fixed `MutableStateFlow(emptyList())`
     * cannot model it, which is part of why the gap went unnoticed.
     */
    val devicesFlow: MutableStateFlow<List<Device>> = MutableStateFlow(emptyList())

    /**
     * Replaces [devicesFlow] entirely when set.
     *
     * ⚠⚠ Needed to model a roster that **never emits**. A `MutableStateFlow` always replays its
     * current value on collect, so it cannot express "this relation produced nothing" — and a test
     * built on one silently passes whether or not the initial load survives.
     */
    var devicesOverride: Flow<List<Device>>? = null

    /**
     * Models PRODUCTION's replay semantics, which a `MutableStateFlow` does not.
     *
     * ⚠⚠ `StreamInteractorImpl` catches a late subscriber up by replaying its buffer **from the
     * initial snapshot**, so a second collection of the same relation yields the ORIGINAL roster
     * first — not the latest. A `MutableStateFlow` replays only the newest value, which hides
     * exactly the bug this models: a screen that receives a fresh roster from its collector and
     * then re-reads via `first()` gets the stale one back.
     */
    var replayFromInitial: List<Device>? = null

    override val devices: Flow<List<Device>>
        get() = devicesOverride
            ?: replayFromInitial?.let { initial -> flow { emit(initial); emitAll(devicesFlow) } }
            ?: devicesFlow

    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<PairingInitiation> = initiateResult ?: ResultSuccessDto(null)

    override suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TaskHandle<TBoolean> {
        awaitPairingCalls += 1
        return awaitHandle ?: error("awaitPairing not expected in this scenario")
    }

    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    /** Counted because ONLY a refresh pass reads it — sync status is also polled by the pairing monitor. */
    var lastSeenReads: Int = 0
        private set

    val hardwareFlow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> get() = hardwareFlow
    override suspend fun deviceLastSeen(): Map<String, Long> {
        lastSeenReads++
        return emptyMap()
    }

    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> {
        cancelPairingCalls += 1
        return ResultSuccessDto(TBooleanDto(true))
    }

    override suspend fun respondToPairingOffer(scannedUri: String): TResult<PairingOfferApprovalPrompt> {
        respondOfferCalls += 1
        return offerPromptResult ?: ResultSuccessDto(null)
    }

    override suspend fun approvePairingOffer(pairingSecretHash: String): TResult<TBoolean> {
        lastApproveHash = pairingSecretHash
        return approveOfferResult ?: ResultSuccessDto(TBooleanDto(true))
    }

    override suspend fun denyPairingOffer(pairingSecretHash: String): TResult<TBoolean> {
        denyOfferCalls += 1
        return ResultSuccessDto(TBooleanDto(true))
    }

    override suspend fun offerPairing(deviceName: String): TResult<OfferPairingSession> {
        offerPairingCalls += 1
        lastOfferDeviceName = deviceName
        return ResultSuccessDto(offerSession)
    }

    override suspend fun getOfferStatus(pairingSecretHash: String): TResult<OfferStatus> {
        lastOfferPollHash = pairingSecretHash
        return offerStatusResult ?: ResultSuccessDto(null)
    }

    override suspend fun cancelPairingOffer(pairingSecretHash: String): TResult<TBoolean> {
        cancelOfferCalls += 1
        return ResultSuccessDto(TBooleanDto(true))
    }

    override suspend fun readmitDevice(deviceId: String): TResult<Data?> {
        readmittedDeviceIds += deviceId
        readmitThrows?.let { throw it }
        return readmitResult ?: ResultSuccessDto(null)
    }

    override suspend fun revokeDevice(deviceId: String): TResult<Data?> {
        revokedDeviceIds += deviceId
        revokeThrows?.let { throw it }
        return revokeResult ?: ResultSuccessDto(null)
    }

    // --- unused surface ---
    // ⚠ Widening SyncRepository broke every implementor — the interface change is
    // only 'additive' from the caller's side.
    override suspend fun forgetThisDevice(): TResult<TString> = error("not used")

    override suspend fun getRecoveryPhrase(): TResult<TString> = ResultSuccessDto(null)
    override suspend fun getSyncStatus(): TResult<SyncStatus> = ResultSuccessDto(syncStatus)
    /** The relay ANSWERS and refuses this device's identity — built from the op's own constant. */
    var pollRefusesDevice: Boolean = false

    /** What the host-side poll finds. Settable mid-test: a request arrives when the joiner posts
     *  it, not when the session starts. */
    var pendingRequests: List<PairingRequest> = emptyList()

    /** The proxy's health row, or null for "not derived yet". Read once, after the settle. */
    var health: SyncHealthStatus? = null

    /** Replaces [health] entirely when set — e.g. a flow that never answers. */
    var healthOverride: Flow<SyncHealthStatus?>? = null

    override fun syncHealth(): Flow<SyncHealthStatus?> = healthOverride ?: MutableStateFlow(health)

    /** What `approvePairingRequest` reports; null = accepted. */
    var approveRequestResult: TResult<Data?>? = null
    var approvedDeviceIds: MutableList<String> = mutableListOf()
        private set

    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> = when {
        pollRefusesDevice -> ResultFailureDto(
            GenericErrorDto(
                "${GetPendingPairingRequests.ERROR_DEVICE_UNRECOGNIZED}: the relay does not " +
                    "recognise this device — it must be paired again."
            )
        )
        failNextPoll -> ResultFailureDto(GenericErrorDto("simulated network failure: relay unreachable"))
        else -> ResultSuccessDto(pendingRequests.toTList())
    }
    /** Settable mid-test: when set, `startPairing` answers this instead — e.g. a Failure. */
    var startPairingResult: TResult<SyncStatus>? = null

    override suspend fun startPairing(): TResult<SyncStatus> {
        startPairingCalls += 1
        return startPairingResult ?: ResultSuccessDto(startPairingStatus)
    }
    override suspend fun stopPairing(): TResult<SyncStatus> {
        stopPairingCalls += 1
        return ResultSuccessDto(null)
    }
    override suspend fun approvePairingRequest(deviceId: String): TResult<Data?> {
        approvedDeviceIds += deviceId
        return approveRequestResult ?: ResultSuccessDto(null)
    }
    override suspend fun getDeviceId(): String = ""
    override suspend fun createRealm(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)
    // Fails like a joiner that can't self-reregister, so a connection-lost banner raised by a
    // failed poll stays up for assertion (a Success would clear it — the recovery path).
    override suspend fun reregisterDevice(): TResult<Data?> =
        ResultFailureDto(GenericErrorDto("no master identity"))
    override suspend fun recoveryClaim(recoveryPhrase: String, deviceName: String): TResult<Data?> =
        ResultSuccessDto(null)
    override suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<TBoolean> = ResultSuccessDto(TBooleanDto(false))
    override suspend fun getUnlockStatus(): TResult<UnlockStatus> = ResultSuccessDto(null)
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(false))
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun clearBiometricSetup(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
    override suspend fun relaxToHeadless(): TResult<TBoolean> =
        ResultSuccessDto(TBooleanDto(true))
}

/**
 * Mirrors `:sync-service-common`'s `PairingDeviceRemovedErrorDto`, which is `internal`. Fieldless,
 * like the real one — the ViewModel matches on the type and reads nothing else.
 */
private class DeviceRemovedErrorDto :
    PairingDeviceRemovedError, CompositeDto(emptyList())

/** Mirrors `:sync-service-common`'s internal `PairingApproverRevokedErrorDto`. Fieldless. */
private class ApproverRevokedErrorDto :
    PairingApproverRevokedError, CompositeDto(emptyList())
