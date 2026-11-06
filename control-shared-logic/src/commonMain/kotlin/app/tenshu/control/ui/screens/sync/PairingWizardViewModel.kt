package app.tenshu.control.ui.screens.sync

import org.tenshu.core.common.util.bestEffort
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.PairingFailedError
import org.tenshu.sync.PairingFailure
import app.tenshu.error.errorCopyKey
import org.tenshu.core.TError
import app.tenshu.error.ErrorCopyKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.sync.HostPairingMonitor
import app.tenshu.control.sync.OfferPairingMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.util.ParsedPairingUri
import org.tenshu.core.common.util.getPlatformDeviceName
import org.tenshu.core.getOrNull
import org.tenshu.core.onFailure
import org.tenshu.core.onSuccess
import org.tenshu.sync.SyncHealthStatus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/**
 * VM for the unified "Add a device" screen (`docs/proposals/control-pairing-ux.md` Phase 2).
 *
 * Owns *both* pairing roles behind a single [AddDeviceMode] segmented control:
 *  - **Scan** ("this is the new device"): the joiner wizard — the `ScanQr` → `ManualCode` →
 *    `NameDevice` → `Approval` → `Success` steps + the joiner handshake
 *    (`confirmPairing`/`awaitHandshake`). Defaults to the camera scanner (the old standalone
 *    `PickMethod` intro is subsumed — §3.2).
 *  - **Show** ("this device invites a new one"): delegates to the process-lifetime
 *    [HostPairingMonitor] (origin QR + copyable code), whose `pairingString`/`connectionLost`
 *    are mirrored into [uiState]. The Approve/Deny approval is the app-global modal, not here.
 *
 * Host-session lifecycle (load-bearing): the host session persists process-lifetime so the
 * global approval modal can surface on any screen. Leaving this screen does NOT stop it.
 * The only explicit stops are [onStopShowing] and switching to the Scan tab ([onSelectScan]).
 * A session that ends with no device added returns this screen to Scan ([leaveEndedHostSession]).
 * Re-entering Show ([onSelectShow]) re-adopts the live session via the monitor's idempotent
 * [HostPairingMonitor.startPairing].
 */
class PairingWizardViewModel(
    private val syncRepository: SyncRepository,
    private val pairingMonitor: HostPairingMonitor,
    offerCleanupScope: CoroutineScope? = null,
) : ViewModel() {

    /**
     * Outlives the VM so [onCleared] can still fire the suspend proxy-side offer cancel.
     * Constructor-injectable (like [HostPairingMonitor]'s scope) so tests can drive it.
     */
    private val offerCleanupScope =
        offerCleanupScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The offer poll, extracted (`OfferPairingMonitor`). ⚠ VM-owned on purpose — the offer session
     * is deliberately ephemeral, unlike the host's; see that class's KDoc. Built over
     * [offerCleanupScope] so its proxy-side cancel can still complete after [onCleared].
     */
    private val offerMonitor = OfferPairingMonitor(
        syncRepository = syncRepository,
        // ⚠ The poll belongs to the SCREEN: `viewModelScope` ends it structurally when the wizard
        // goes away, rather than relying on [onCleared] remembering to call `cancel()`. It is also
        // the dispatcher the poll ran on before it was extracted — passing the cleanup scope here
        // would silently move it off Main.
        pollScope = viewModelScope,
        // …and the proxy-side cancel must outlive that, or `onCleared` fires it into a dead scope
        // and the offer is orphaned on the relay until its TTL.
        teardownScope = this.offerCleanupScope,
    )

    /**
     * ⚠ Opens on **Show** when a host session is already live. The session outlives this screen
     * (see the class KDoc), so the wizard can be entered — from the dashboard's "in Pairing" row,
     * which exists to route back to it — while a code is still being served. Defaulting to Scan
     * there showed a camera, with the session the user tapped on counting down out of sight behind
     * the other tab. The code itself arrives through the `pairingString` collector below.
     */
    private val _uiState = MutableStateFlow(
        PairingWizardUiState(
            mode = if (pairingMonitor.isPairing.value) AddDeviceMode.Show else AddDeviceMode.Scan,
        )
    )
    val uiState: StateFlow<PairingWizardUiState> = _uiState.asStateFlow()

    // §7.5 Phase L2: the in-flight pairing-wait task. Non-null while the
    // requester is waiting for the host to authorize; `cancel()` tears down
    // the in-proxy handshake (no separate CancelPairing call needed once the
    // wait is running).
    private var pairingHandle: TaskHandle<TBoolean>? = null

    init {
        // Mirror the host monitor's "Show a code" state into the UI (Phase 2). The monitor
        // is process-lifetime, so its pairing string / connection-lost state reflect a live
        // session that may have been started on a previous visit.
        viewModelScope.launch {
            pairingMonitor.pairingString.collect { str ->
                _uiState.update { it.copy(hostPairingString = str) }
            }
        }
        viewModelScope.launch {
            pairingMonitor.connectionLost.collect { lost ->
                _uiState.update { it.copy(hostConnectionLost = lost) }
            }
        }
        viewModelScope.launch {
            // A host session that ends WITHOUT a device being added — Stop, a denied request, or
            // the monitor's waiting window running out — takes its code with it, and the Show
            // surface reads "no code" as "still starting": it would sit on that spinner forever.
            //
            // ⚠ On the live → ended EDGE, not on `false`: "no session" is also what this reads
            // before [onSelectShow]'s start has landed, and leaving then would bounce the user
            // straight back off the tab they just chose.
            var wasLive = false
            pairingMonitor.isPairing.collect { live ->
                if (wasLive && !live) leaveEndedHostSession()
                wasLive = live
            }
        }
        viewModelScope.launch {
            pairingMonitor.deviceUnrecognized.collect { unrecognized ->
                _uiState.update { it.copy(hostDeviceUnrecognized = unrecognized) }
                // The host attempt is how this is discovered (the relay only answers when asked), so
                // the first attempt on an unrecognised device always starts down the wrong path.
                // Switch it to the offer flow in place rather than leaving the user on a dead
                // surface: the monitor has already torn the impossible host session down.
                if (unrecognized && _uiState.value.mode == AddDeviceMode.Show &&
                    !_uiState.value.offerRole
                ) {
                    startOfferSession()
                }
            }
        }
        viewModelScope.launch {
            pairingMonitor.pairingComplete.collect { complete ->
                _uiState.update { it.copy(hostPairingComplete = complete) }
                // D2: the host learns first — its own pushes have been refused all along — so the
                // device that ran the pairing says it, on the device the user is holding. No join
                // race on this side, so no settle delay (see watchForUnfundedRealm).
                if (complete) watchForUnfundedRealm(settle = false)
            }
        }
        // Offer-side, mirrored from the monitor — same shape as the host collectors above.
        viewModelScope.launch {
            offerMonitor.offerUri.collect { uri -> _uiState.update { it.copy(offerUri = uri) } }
        }
        viewModelScope.launch {
            offerMonitor.sas.collect { sas -> _uiState.update { it.copy(offerSas = sas) } }
        }
        viewModelScope.launch {
            offerMonitor.denied.collect { denied -> _uiState.update { it.copy(offerDenied = denied) } }
        }
        viewModelScope.launch {
            offerMonitor.failed.collect { failed -> _uiState.update { it.copy(offerFailed = failed) } }
        }
        viewModelScope.launch {
            offerMonitor.sasShownBeforeApproval.collect { shown ->
                _uiState.update { it.copy(offerSasShownBeforeApproval = shown) }
            }
        }
        viewModelScope.launch {
            offerMonitor.paired.collect { paired ->
                _uiState.update { it.copy(offerPaired = paired) }
                // The offerer just joined too — same promise, same check (D1), and the same join
                // race as the joiner, so it keeps the settle.
                if (paired) watchForUnfundedRealm(settle = true)
            }
        }

        // Host-side approval, mirrored for the surfaces that cannot mount the app-global Compose
        // modal — today that is Control-on-iOS, whose Show tab rendered the QR and then had
        // nowhere to put the request that answered it (the joiner sat on "Confirm on your other
        // device" until its ~5-min budget elapsed, with no error on either side).
        //
        // ⚠ Compose does NOT read these: Android/desktop keep the app-global
        // `HostPairingApprovalDialog` (it must overlay any destination, which a screen-scoped
        // state cannot), and rendering both would show the approval twice.
        viewModelScope.launch {
            pairingMonitor.activeRequest.collect { request ->
                _uiState.update {
                    it.copy(
                        hostRequestDeviceName = request?.deviceName?.get(),
                        hostRequestSas = request?.sas?.get()?.takeIf(String::isNotEmpty),
                    )
                }
            }
        }
        viewModelScope.launch {
            // A host start that produced no session, rendered on the Show tab through the shared
            // [PairingWizardUiState.error] channel. Host role only: on Scan, or on the offer role,
            // `error` belongs to another flow and a stale start failure must not overwrite it.
            pairingMonitor.startError.collect { showHostStartError(it) }
        }
        viewModelScope.launch {
            pairingMonitor.approvalError.collect { message ->
                _uiState.update { it.copy(hostApprovalError = message) }
            }
        }
    }

    /**
     * Switch to the **Scan** ("new device") tab: leave the host/offerer role (the user isn't
     * showing a code here) and reset the joiner flow to its entry — the camera scanner.
     */
    fun onSelectScan() {
        pairingMonitor.stopPairing()
        cancelOfferSession()
        _uiState.update { it.copy(mode = AddDeviceMode.Scan, step = PairingStep.ScanQr, error = null, scanError = null) }
    }

    /**
     * Switch to the **Show** tab. The role branches on whether this device is realm-bound
     * (`sync-pairing-inverted-direction.md` §5.1):
     *  - **paired** (owner known) → host role: show the origin `tenshu://join` QR via the
     *    process-lifetime [HostPairingMonitor.startPairing] (idempotent — re-adopts a live
     *    session rather than minting a fresh secret).
     *  - **unpaired** (no owner) → **offerer** role: this new device shows a `tenshu://offer`
     *    QR for an existing device to scan + approve ([startOfferSession]). Hosting here is
     *    impossible by contract — `StartPairing` returns no pairing string without an owner
     *    (see `StartPairingImplementation`), which used to strand this tab on a spinner.
     *
     * The offer branch is taken only on a *successful* status read reporting no owner: a
     * transient status failure must never mint an offer session on an already-paired device
     * (an approved offer would install keys over the live realm).
     */
    fun onSelectShow() {
        _uiState.update { it.copy(mode = AddDeviceMode.Show, error = null, scanError = null) }
        viewModelScope.launch {
            val status = syncRepository.getSyncStatus()
            val unpaired = status is TResult.Success<*> && status.getOrNull()?.ownerId?.get() == null
            // ⚠⚠ The role turns on whether the RELAY accepts this device, not merely on whether it
            // holds a realm locally. A device whose registration the relay has lost still has an
            // ownerId, so the old condition sent it down the host path — where its
            // `GET /sync/pairing/requests` 401s, the joiner's request is never seen, and BOTH sides
            // hang (observed 2026-08-17). For pairing purposes such a device *is* a new device: it
            // must be re-admitted, which is exactly what the offer flow does — and safely, because
            // `PairingKeyInstaller` only purges a prior realm when the owner DIFFERS, and the offer
            // QR carries this device's existing id so the approve re-registers that id rather than
            // minting a second one.
            if (unpaired || pairingMonitor.deviceUnrecognized.value) {
                startOfferSession()
            } else {
                // ⚠ Re-read once the attempt lands rather than trusting the collector alone: a
                // retry that fails the same way leaves [HostPairingMonitor.startError] equal to the
                // value it held before, and a StateFlow collector may never see the null between —
                // so the error this call just cleared would not come back, and the tab would spin.
                pairingMonitor.startPairing().join()
                showHostStartError(pairingMonitor.startError.value)
            }
        }
    }

    /** Mirror a host start failure onto the Show tab's host role; see the `startError` collector. */
    private fun showHostStartError(startError: PairingError?) {
        _uiState.update {
            if (it.mode == AddDeviceMode.Show && !it.offerRole) it.copy(error = startError) else it
        }
    }

    /**
     * Explicit "Stop" on the Show surface. Host role → end the host pairing session (the
     * monitor owns it). Offerer role → tear down the offer session and return to the Scan
     * tab (there is nothing else to show on an unpaired device).
     */
    fun onStopShowing() {
        if (_uiState.value.offerRole) {
            cancelOfferSession()
            _uiState.update { it.copy(mode = AddDeviceMode.Scan, step = PairingStep.ScanQr) }
        } else {
            pairingMonitor.stopPairing()
            // Leave now rather than when the proxy confirms: the teardown is an IPC round trip.
            // ⚠ And take a failed start's error along: once the mode is Scan the monitor's own
            // reset no longer reaches `error`, and the scanner would render it as a scan failure.
            _uiState.update {
                it.copy(mode = AddDeviceMode.Scan, step = PairingStep.ScanQr, error = null)
            }
        }
    }

    /**
     * Return the Show tab to Scan once its host session is gone — the same landing
     * [onStopShowing] gives the offerer role.
     *
     * ⚠ Not on the two endings that have a surface of their own: an approved request (the "device
     * added" terminal — the monitor raises `pairingComplete` before it retires the session, so it
     * is already readable here) and a refused device (the tab switches to the offer role in place).
     */
    private fun leaveEndedHostSession() {
        if (pairingMonitor.pairingComplete.value || pairingMonitor.deviceUnrecognized.value) return
        _uiState.update {
            if (it.mode == AddDeviceMode.Show && !it.offerRole) {
                it.copy(mode = AddDeviceMode.Scan, step = PairingStep.ScanQr, error = null)
            } else it
        }
    }

    /** "Show a new code" after a denied/failed offer — restart the offer session. */
    fun onRetryOffer() {
        startOfferSession()
    }

    /**
     * Approve the pending host-side request ([PairingWizardUiState.hostRequestDeviceName]) — the
     * §3 Step 3 "active approval", after the user has compared
     * [PairingWizardUiState.hostRequestSas] against the joiner's screen.
     *
     * Delegates to the process-lifetime [HostPairingMonitor], which owns the session: on success it
     * clears the request and raises `pairingComplete`; on failure it keeps the request up and
     * surfaces the reason on [PairingWizardUiState.hostApprovalError] (most often: this device is a
     * phrase-less joiner and may not admit devices).
     */
    fun approveHostRequest() {
        pairingMonitor.approveActiveRequest()
    }

    /**
     * Decide whether the realm this device just joined (or just admitted a device into) can
     * actually sync — `enforcement-followup-tracks.md` **Track D**.
     *
     * ## Why the health row, and why it cannot mis-accuse
     *
     * ⚠⚠ The distinction that matters is already made for us. [SyncHealthStatus] carries
     * `CONNECTIVITY_NEEDS_ATTENTION` **only** when the relay *answered and refused the write*
     * (`402`); an unreachable relay is `CONNECTIVITY_OFFLINE`, a sealed realm is
     * `CONNECTIVITY_LOCKED`, a refused identity is `CONNECTIVITY_DEVICE_UNRECOGNIZED`. So the
     * "never tell a paying user their realm is unfunded because their network blipped" rule is
     * **structural here, not a guard this function has to remember** — a blip cannot produce the
     * value this reads.
     *
     * ## Why one read after a settle is enough
     *
     * A joiner on a *funded* realm can legitimately see one `402`: the documented join-race, where
     * it pushes a beat before the owner's entitlement record has synced and derived locally. That
     * heals within seconds — the observed shape is a `402` followed by a `200` in the same second.
     * A refusal still standing after [UNFUNDED_SETTLE_MS] is therefore not that race, so the
     * **joiner and offerer** terminals ([settle] = true) wait it out. The **host** case (D2, and the
     * offer-flow responder, which is the same role: an existing device admitting a new one) runs
     * with [settle] = false — its pushes have been refused since long before this pairing, so there
     * is no race to exclude.
     *
     * ## Why the delay is now visible
     *
     * The terminal screens auto-continue (`control-pairing-ux.md` §9), and they may only arm once
     * this check has *finished* — otherwise a screen could leave a beat before it swapped its body
     * to "Nothing will sync yet". [PairingWizardUiState.fundingCheckSettled] is that signal, so a
     * settle the host does not need would be three-plus seconds of a stalled countdown on the most
     * common terminal. The [HEALTH_READ_TIMEOUT_MS] bound applies to every role.
     *
     * ⚠ Silence on every uncertainty: no row yet, a flow that does not answer within
     * [HEALTH_READ_TIMEOUT_MS], a read that throws, or any value other than "refused" leaves the
     * wizard quiet — and still marks the check settled. The fallback for the cases this misses is
     * the standing surface (Track D3), never a guess here.
     */
    private fun watchForUnfundedRealm(settle: Boolean) {
        unfundedWatchJob?.cancel()
        // A new terminal: whatever an earlier check settled no longer speaks for this screen.
        _uiState.update { it.copy(fundingCheckSettled = false) }
        unfundedWatchJob = viewModelScope.launch {
            if (settle) delay(UNFUNDED_SETTLE_MS)
            val refused = try {
                withTimeoutOrNull(HEALTH_READ_TIMEOUT_MS) {
                    syncRepository.syncHealth().first()
                }?.connectivity?.get() == SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("PairingWizard -> health read failed; treating as not established: $e")
                false
            }
            _uiState.update {
                it.copy(realmUnfunded = it.realmUnfunded || refused, fundingCheckSettled = true)
            }
        }
    }

    /** The live unfunded-realm check; replaced on every new terminal. */
    private var unfundedWatchJob: Job? = null

    /**
     * Deny the pending host-side request. Like the Compose modal's Deny, this ends the whole host
     * session (the monitor calls `stopPairing`) rather than merely dropping this one request — the
     * ephemeral key the SAS was derived from is burned with it, so there is nothing left to admit
     * a second attempt with.
     */
    fun denyHostRequest() {
        pairingMonitor.denyActiveRequest()
    }

    /**
     * Offerer session (`sync-pairing-inverted-direction.md` §5.1): mint the offer via
     * [SyncRepository.offerPairing] (named with the platform device name — renameable later,
     * same as the join flow's prefill), surface the `tenshu://offer` URI, then poll
     * [SyncRepository.getOfferStatus] until `approved` (the proxy has already installed the
     * keys — this device is paired) or `denied`. VM-scoped, unlike the host session: an
     * abandoned offer has no approval to surface globally, so leaving the screen tears it
     * down ([onCleared]).
     */
    /**
     * Offerer session (`sync-pairing-inverted-direction.md` §5.1): mint the offer, surface the
     * `tenshu://offer` URI, and watch for the responder's decision. The poll itself lives in
     * [OfferPairingMonitor] — one place, bounded by a deadline, and the seam Phase 3's long-poll
     * would replace. Idempotent: re-entering Show re-adopts a live session rather than minting a
     * second code and orphaning the first.
     */
    private fun startOfferSession() {
        _uiState.update { it.copy(offerRole = true) }
        offerMonitor.start(getPlatformDeviceName())
    }

    /** Tear down the offer session: stop polling, fire the idempotent proxy-side cancel. */
    private fun cancelOfferSession() {
        offerMonitor.cancel()
        _uiState.update {
            it.copy(
                offerRole = false,
                offerUri = null,
                offerSas = null,
                offerSasShownBeforeApproval = false,
                offerPaired = false,
                offerDenied = false,
                offerFailed = false,
            )
        }
    }

    override fun onCleared() {
        // ⚠ Teardown-on-leave is UNCHANGED: the offer session is deliberately ephemeral (an
        // abandoned offer has no global surface to report on), so leaving the wizard still kills
        // it. The monitor's scope outlives the VM only so this suspend proxy-side cancel can
        // complete, not so the session can.
        offerMonitor.cancel()
    }

    private companion object {

        /**
         * How long a relay refusal must stand before it is reported as "this realm has no plan".
         * Sized against the join-race it exists to exclude, which resolves in ~a second; the extra
         * headroom costs a terminal screen nothing.
         */
        const val UNFUNDED_SETTLE_MS = 6_000L

        /** Bound on the health read itself — a flow that never answers must not hang the check. */
        const val HEALTH_READ_TIMEOUT_MS = 4_000L
    }

    fun onPickManual() {
        _uiState.update { it.copy(step = PairingStep.ManualCode, error = null, scanError = null) }
    }

    /**
     * Reset the Scan flow to its entry step (the camera scanner) and tear down any in-flight
     * joiner handshake. (Formerly `onBackToPickMethod`; PickMethod is subsumed — §3.2.)
     */
    fun onBackToScanStart() {
        // Per pairing-flow.md §5 — closing the QR dialog burns the handshake.
        // §7.5: once the wait task is running, cancelling its TaskHandle is
        // the teardown lever — the op's CancellationException path clears the
        // in-proxy handshake (asset A8). Before the wait task exists (backed
        // out pre-confirm), InitiatePairing may still have stashed a
        // handshake, so fall back to the idempotent CancelPairing op.
        val handle = pairingHandle
        val secret = _uiState.value.pairingSecret
        if (handle != null) {
            handle.cancel()
            pairingHandle = null
        } else if (secret != null) {
            viewModelScope.launch {
                bestEffort("PairingWizard -> cancelling the pairing we are leaving") {
                    syncRepository.cancelPairing(secret)
                }
            }
        }
        _uiState.update {
            it.copy(
                step = PairingStep.ScanQr,
                scannedDeviceId = null,
                pairingSecret = null,
                otherDevicePublicKey = null,
                ownerId = null,
                sas = null,
                responderDeviceName = null,
                responderSas = null,
                responderSecretHash = null,
                error = null,
                scanError = null,
            )
        }
    }

    fun onSuccessContinue() {
        _uiState.update { it.copy(step = PairingStep.Done) }
    }

    /**
     * Role-aware scanner dispatch (`sync-pairing-inverted-direction.md` §5.2): one camera, the
     * QR's role host picks the branch. `tenshu://join` (this device is the new one) drives the
     * joiner wizard; `tenshu://offer` (this device is the existing one admitting a new device)
     * drives the responder flow ([respondToOffer]); `tenshu://hub` admits a headless hub from a
     * printed label, through the same approval machinery. The retired legacy `tenshu://pair?…` host
     * and unknown role hosts get their §3.2 prompts — never a partial parse.
     */
    fun onQrScanned(data: String) {
        // Camera scanners report the code once per analyzed frame (no once-latch), so a
        // single physical scan can arrive as a burst. Only the scan-entry steps may
        // consume a result: the first one advances the step synchronously, so the rest
        // of the burst is dropped here — load-bearing for the offer branch, where every
        // respondToPairingOffer mints a fresh ephemeral key proxy-side and each extra
        // frame would flash a different SAS on the approval prompt.
        val step = _uiState.value.step
        if (step != PairingStep.ScanQr && step != PairingStep.ManualCode && step != PairingStep.PickMethod) {
            return
        }
        when (val parsed = PairingUri.parse(data)) {
            is ParsedPairingUri.Join -> {
                println("PairingWizard -> Parsed join code: owner=${parsed.ownerId}, device=${parsed.hostDeviceId}")
                _uiState.update {
                    it.copy(
                        step = PairingStep.NameDevice,
                        scannedDeviceId = parsed.hostDeviceId,
                        pairingSecret = parsed.pairingSecret.decodeToString(),
                        otherDevicePublicKey = parsed.hostPublicKey,
                        ownerId = parsed.ownerId,
                        error = null,
                        scanError = null,
                    )
                }
            }

            is ParsedPairingUri.Offer -> respondToOffer(data)

            is ParsedPairingUri.Hub -> respondToHubOffer(data)

            ParsedPairingUri.UnknownRole ->
                _uiState.update { it.copy(scanError = PairingScanError.UnknownRole) }

            ParsedPairingUri.Malformed ->
                _uiState.update { it.copy(scanError = PairingScanError.Malformed) }
        }
    }

    /**
     * Offer-flow responder entry (`sync-pairing-inverted-direction.md` §3.3 step 2-3): hand the
     * scanned `tenshu://offer?…` URI to the proxy, which parses it, derives the shared secret +
     * SAS, and returns the [org.tenshu.sync.PairingOfferApprovalPrompt] this device shows. The
     * responder's ephemeral private key never crosses the op boundary; the UI only holds the
     * device name, SAS, and `pairingSecretHash` to thread into approve/deny.
     */
    private fun respondToOffer(scannedUri: String) {
        _uiState.update {
            it.copy(
                step = PairingStep.ResponderApproval,
                isLoading = true,
                responderDeviceName = null,
                responderSas = null,
                responderSecretHash = null,
                status = null,
                error = null,
                scanError = null,
            )
        }
        viewModelScope.launch {
            val result = syncRepository.respondToPairingOffer(scannedUri)
            result.onSuccess { prompt: org.tenshu.sync.PairingOfferApprovalPrompt? ->
                if (prompt == null) {
                    // A well-formed URI the proxy still couldn't turn into a prompt — treat as a
                    // bad scan rather than stranding the user on a blank approval screen.
                    _uiState.update {
                        it.copy(step = PairingStep.ScanQr, isLoading = false, scanError = PairingScanError.Malformed)
                    }
                    return@onSuccess
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        responderDeviceName = prompt.deviceName.get(),
                        responderSas = prompt.sas.get(),
                        responderSecretHash = prompt.pairingSecretHash.get(),
                    )
                }
            }
            result.onFailure { error: org.tenshu.core.TError ->
                println("PairingWizard -> respondToPairingOffer failed: $error")
                _uiState.update {
                    it.copy(step = PairingStep.ScanQr, isLoading = false, scanError = PairingScanError.Malformed)
                }
            }
        }
    }

    /**
     * Hub-flow responder entry (hub proposal §11.3, H2): the scanned code is a headless
     * device's PRINTED label. The proxy fetches the hub's signed ephemeral offer from the
     * relay and verifies it against the printed key — the anti-MITM anchor that replaces
     * the SAS ceremony a headless device cannot render (§11.4). On success the same
     * [PairingStep.ResponderApproval] renders, with the hub copy (no SAS) via
     * [PairingWizardUiState.responderIsHub]; approve/deny then reuse the offer-flow ops
     * unchanged. Failures map to their own scan errors — a verification failure in
     * particular is a hard "this code doesn't match this hub", never a silent retry.
     */
    private fun respondToHubOffer(scannedUri: String) {
        _uiState.update {
            it.copy(
                step = PairingStep.ResponderApproval,
                isLoading = true,
                responderDeviceName = null,
                responderSas = null,
                responderSecretHash = null,
                responderIsHub = true,
                status = null,
                error = null,
                scanError = null,
            )
        }
        viewModelScope.launch {
            val result = syncRepository.respondToHubPairing(scannedUri)
            result.onSuccess { prompt: org.tenshu.sync.PairingOfferApprovalPrompt? ->
                if (prompt == null) {
                    _uiState.update {
                        it.copy(
                            step = PairingStep.ScanQr,
                            isLoading = false,
                            responderIsHub = false,
                            scanError = PairingScanError.Malformed,
                        )
                    }
                    return@onSuccess
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        responderDeviceName = prompt.deviceName.get(),
                        // No SAS in the hub flow (§11.4) — the prompt's field is empty by design.
                        responderSas = null,
                        responderSecretHash = prompt.pairingSecretHash.get(),
                    )
                }
            }
            result.onFailure { error: org.tenshu.core.TError ->
                println("PairingWizard -> respondToHubPairing failed: $error")
                // ⚠⚠ Matches the typed error, not a prefix of an English sentence. The codes
                // were always codes — they just travelled in `GenericError.message`, so this had
                // to `startsWith` its way to them (`core-error-copy-catalogue.md` §5.6).
                val scanError = when {
                    error is RelayRouteUnsupportedError -> PairingScanError.HubUnsupported
                    error is PairingFailedError -> when (error.reason) {
                        PairingFailure.VerifyFailed -> PairingScanError.HubVerifyFailed
                        PairingFailure.OfferNotFound -> PairingScanError.HubNotArmed
                        PairingFailure.AdmitterUnrecognised,
                        PairingFailure.NoAuthority,
                        PairingFailure.NoPendingOffer -> PairingScanError.Malformed
                    }
                    else -> PairingScanError.Malformed
                }
                _uiState.update {
                    it.copy(
                        step = PairingStep.ScanQr,
                        isLoading = false,
                        responderIsHub = false,
                        scanError = scanError,
                    )
                }
            }
        }
    }

    /**
     * Offer-flow responder approve (`sync-pairing-inverted-direction.md` §3.3 step 3): admit the
     * scanned new device. `Success(true)` → the "device added" success step.
     */
    fun approveOffer() {
        val hash = _uiState.value.responderSecretHash ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = syncRepository.approvePairingOffer(hash)
            result.onSuccess { ok: TBoolean? ->
                if (ok?.get() == true) {
                    _uiState.update {
                        it.copy(step = PairingStep.ResponderSuccess, isLoading = false, error = null)
                    }
                    // The responder is the host role of the offer flow (an existing device that
                    // admitted a new one), so: no settle. Read only to arm the hub-flow
                    // auto-continue — the SAS responder terminal stays manual regardless (§9).
                    watchForUnfundedRealm(settle = false)
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = PairingError.Unexpected(ErrorCopyKey.UNEXPECTED))
                    }
                }
            }
            result.onFailure { error: org.tenshu.core.TError ->
                _uiState.update { it.copy(isLoading = false, error = error.toPairingError()) }
            }
        }
    }

    /**
     * Offer-flow responder deny: tell the offerer (POST a signed `denied`) and reset to the
     * scanner. Best-effort — a network hiccup still returns the user to the scanner.
     */
    fun denyOffer() {
        val hash = _uiState.value.responderSecretHash
        viewModelScope.launch {
            if (hash != null) {
                bestEffort("PairingWizard -> denying the offer") { syncRepository.denyPairingOffer(hash) }
            }
            resetResponder()
        }
    }

    private fun resetResponder() {
        _uiState.update {
            it.copy(
                step = PairingStep.ScanQr,
                responderDeviceName = null,
                responderSas = null,
                responderSecretHash = null,
                responderIsHub = false,
                isLoading = false,
                status = null,
                error = null,
                scanError = null,
            )
        }
    }

    fun confirmPairing(deviceName: String) {
        _uiState.value.scannedDeviceId ?: return
        val secret = _uiState.value.pairingSecret ?: return
        val otherPublicKey = _uiState.value.otherDevicePublicKey ?: return
        // The owner id is carried by the scanned pairing code (tenshu://join?o=...).
        // A null here means a malformed/old code, not a dev session — fail the scan rather
        // than join under a placeholder owner.
        val ownerId = _uiState.value.ownerId ?: run {
            // ⚠ App-authored: the scanned code is malformed, which the app knows locally — this
            // is not a proxy failure and never crossed the boundary.
            _uiState.update { it.copy(error = PairingError.Unexpected(ErrorCopyKey.UNEXPECTED)) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    step = PairingStep.Approval,
                    isLoading = true,
                    status = "Registering device...",
                    error = null
                )
            }

            // 1. Initiate Pairing (Registers on relay + generates local keys
            //    + computes the SAS the host will display alongside its
            //    approval prompt — see sync-pairing-flow-improvements.md §3.2.)
            //    An operation failure (relay reject, etc.) comes back as a
            //    TResult.Failure via the proxy catch-all
            //    (`LocalTaskExecutor.executeAsync` → wrapException), not a
            //    throw — it lands in `onFailure` below. A relay that *hangs* is
            //    bounded by the relay HttpClient's socket timeout, which then
            //    also surfaces as a Failure.
            val result = syncRepository.initiatePairing(
                ownerId = ownerId,
                pairingSecret = secret,
                deviceName = deviceName,
                otherDevicePublicKey = otherPublicKey,
            )

            result.onSuccess { initiation: org.tenshu.sync.PairingInitiation? ->
                // 2. Surface the SAS for §4a.4 + start handshake polling.
                // The platform allows a successful TResult with a null
                // value through (see test fakes); the real proxy always
                // populates `initiation`.
                if (initiation != null) {
                    _uiState.update { it.copy(sas = initiation.sas.get()) }
                }
                // §7.5: one cancellable wait task instead of a UI poll loop.
                launch {
                    val handle = syncRepository.awaitPairing(secret, otherPublicKey)
                    pairingHandle = handle
                    awaitHandshake(handle)
                }
            }

            result.onFailure { error: org.tenshu.core.TError ->
                println("PairingWizard -> Initiation failed: $error")
                _uiState.update { it.copy(isLoading = false, error = error.toPairingError()) }
            }
        }
    }

    /**
     * §7.5: await the single in-proxy pairing-wait task instead of polling.
     * `Success(true)` = handshake complete (the proxy already initialised the
     * SyncManager). `Success(false)` = the proxy-side ~5-min budget elapsed
     * (same "timed out" UX as the old loop). A [CancellationException] means
     * the user backed out (`onBackToPickMethod` cancelled the handle) — not an
     * error; the proxy handshake was torn down by the op's own teardown.
     */
    private suspend fun awaitHandshake(handle: TaskHandle<TBoolean>) {
        _uiState.update { it.copy(status = "Waiting for other device to authorize...") }

        val response = try {
            handle.await()
        } catch (e: CancellationException) {
            return
        }
        pairingHandle = null

        if (response is TResult.Success<*> && (response.value as? TBoolean)?.get() == true) {
            _uiState.update {
                it.copy(
                    step = PairingStep.Success,
                    isPairingComplete = true,
                    isLoading = false,
                )
            }
            // D1: "your existing data will sync over the next few minutes" is a promise an
            // unfunded realm cannot keep, and this is the moment the user believes it. The joiner
            // is the side of the join race, so it waits out the settle.
            watchForUnfundedRealm(settle = true)
        } else {
            _uiState.update {
                it.copy(isLoading = false, error = PairingError.Unexpected(ErrorCopyKey.UNEXPECTED))
            }
        }
    }
}

/**
 * Step state for the §4a pairing-as-requester flow per
 * `docs/proposals/control-onboarding-ux.md`. The VM updates this as the
 * underlying handshake progresses; the UI switches sub-screens on it.
 */
sealed class PairingStep {
    /**
     * §4a.1 — pick scan-QR or enter-code.
     *
     * Retained for the joiner-step taxonomy, but no longer the entry step: the unified
     * "Add a device" screen (`control-pairing-ux.md` Phase 2) defaults Scan straight to
     * [ScanQr]; "enter a pairing code instead" lives on the scanner surface.
     */
    data object PickMethod : PairingStep()

    /** §4a.2a — full-screen camera scanner. Default entry for the Scan tab. */
    data object ScanQr : PairingStep()

    /** §4a.2b — manual base32 entry. */
    data object ManualCode : PairingStep()

    /** §4a.3 — name this device. */
    data object NameDevice : PairingStep()

    /** §4a.4 — `InitiatePairing` posted; polling `GetPairingStatus`. */
    data object Approval : PairingStep()

    /** §4a.5 — pairing complete; user taps Continue → Done. */
    data object Success : PairingStep()

    /**
     * Offer-flow responder (`sync-pairing-inverted-direction.md` §5.2): this device scanned a
     * new device's `tenshu://offer?…` QR and shows the device-name + SAS approval prompt.
     */
    data object ResponderApproval : PairingStep()

    /** Offer-flow responder terminal — the scanned device was admitted; "device added". */
    data object ResponderSuccess : PairingStep()

    /** Terminal — screen signals completion to the navigation host. */
    data object Done : PairingStep()
}

/**
 * The active role on the unified "Add a device" screen (`control-pairing-ux.md` §3.2).
 * Defaults to [Scan] — the common case (you're holding the new device).
 */
enum class AddDeviceMode {
    /** "This is the new device" — scan a code shown on a device already signed in. */
    Scan,

    /** "This device invites a new one" — show the origin QR + pairing code. */
    Show,
}

data class PairingWizardUiState(
    /** Active tab on the unified screen; defaults to [AddDeviceMode.Scan]. */
    val mode: AddDeviceMode = AddDeviceMode.Scan,
    /** Live host pairing string (QR / manual code), mirrored from [HostPairingMonitor]. Only
     *  meaningful in [AddDeviceMode.Show]. Null when no host session is active. */
    val hostPairingString: String? = null,
    /** Mirrors [HostPairingMonitor.connectionLost] — rendered as a banner on the Show tab. */
    val hostConnectionLost: Boolean = false,
    /**
     * Mirrors [HostPairingMonitor.deviceUnrecognized] — the relay answered and refused THIS device,
     * and the automatic repair could not fix it. Rendered instead of [hostConnectionLost], never
     * beside it: "can't reach the relay" would be false (it replied) and its promise to keep
     * retrying unachievable (a joiner cannot re-register itself).
     */
    val hostDeviceUnrecognized: Boolean = false,
    /** Mirrors [HostPairingMonitor.pairingComplete] — the Show tab's terminal "device added"
     *  state, shown in place of the QR/spinner once the host approves a request. */
    val hostPairingComplete: Boolean = false,
    /**
     * The pending host-side approval, mirrored from [HostPairingMonitor.activeRequest]: the
     * joiner's self-chosen name and the locally-derived 6-digit [hostRequestSas] to compare
     * against its screen. Non-null means a device is waiting on this one's Approve/Deny
     * ([PairingWizardViewModel.approveHostRequest] / [PairingWizardViewModel.denyHostRequest]).
     *
     * [hostRequestSas] can be null on its own while [hostRequestDeviceName] is set — the field is
     * optional on the wire and only populated by the proxy — so render the prompt off the name and
     * treat the SAS as an enrichment.
     *
     * ⚠ Read only by surfaces with no app-global modal (Control-on-iOS). Compose keeps
     * `HostPairingApprovalDialog` at the App root; see the collector in
     * [PairingWizardViewModel]'s `init`.
     */
    val hostRequestDeviceName: String? = null,
    val hostRequestSas: String? = null,
    /** Mirrors [HostPairingMonitor.approvalError] — why the last Approve failed, if it did. */
    /** ⚠ A typed failure the UI renders; never a proxy sentence (§5.0). */
    val hostApprovalError: PairingError? = null,
    /**
     * The realm has **no storage plan**, so the relay refuses every write and nothing this device
     * does will ever sync (`enforcement-followup-tracks.md` Track D). Set on a terminal step only,
     * and only once a *refusal by the relay* has outlived the join-race settle — see
     * [PairingWizardViewModel.watchForUnfundedRealm]. `false` means "not established", never
     * "funded": every uncertainty leaves it false.
     *
     * ⚠ It does not make the pairing a failure. The device **is** in the realm; what it changes is
     * the promise the terminal screen is allowed to make about syncing.
     */
    val realmUnfunded: Boolean = false,
    /**
     * The unfunded-realm check for the **current** terminal has finished, either way — refused,
     * not refused, timed out, or failed. Reset to `false` whenever a new terminal starts its check
     * ([PairingWizardViewModel.watchForUnfundedRealm]).
     *
     * ⚠ This is what makes a terminal *settled*: until it is true, [realmUnfunded] may still flip
     * and swap the body to "Nothing will sync yet", so nothing may auto-continue off that screen
     * ([autoContinueArmed], `control-pairing-ux.md` §9).
     */
    val fundingCheckSettled: Boolean = false,
    /**
     * Offerer-role fields (`sync-pairing-inverted-direction.md` §5.1) — the Show tab on an
     * *unpaired* device. [offerRole] picks the offer surface over the host one; [offerUri] is
     * the `tenshu://offer?…` QR payload (null while minting); [offerPaired] is the terminal
     * success (the proxy installed the keys) with [offerSas] for post-hoc comparison;
     * [offerDenied] / [offerFailed] are the responder-declined / couldn't-mint terminals.
     */
    val offerRole: Boolean = false,
    val offerUri: String? = null,
    val offerSas: String? = null,
    /**
     * Sticky per offer session: the SAS was on this device's screen **before** the responder
     * approved — a `scanned` announce carrying it arrived ahead of `approved`
     * (`sync-pairing-scanned-announce.md`). Mirrored from `OfferPairingMonitor`, which sets it in
     * the same poll step that surfaces the scanned SAS, so collector ordering cannot fake it.
     *
     * ⚠ The announce is best-effort. When it never arrived, the only comparison left is the
     * post-hoc one on the paired terminal (T-P-07), and that terminal must stay manual — which is
     * what `false` here means to [autoContinueArmed].
     */
    val offerSasShownBeforeApproval: Boolean = false,
    val offerPaired: Boolean = false,
    val offerDenied: Boolean = false,
    val offerFailed: Boolean = false,
    val step: PairingStep = PairingStep.ScanQr,
    val scannedDeviceId: String? = null,
    val otherDevicePublicKey: ByteArray? = null,
    val pairingSecret: String? = null,
    val ownerId: String? = null,
    /**
     * 6-digit SAS surfaced from `InitiatePairing`'s response per
     * `docs/proposals/sync-pairing-flow-improvements.md` §3.2. Non-null
     * once initiation has succeeded — the UI displays it during
     * `PairingStep.Approval` so the user can verify it matches the
     * value the host's approval prompt shows.
     */
    val sas: String? = null,
    /**
     * Offer-flow responder ([PairingWizardViewModel.respondToOffer]): the scanned new device's
     * self-chosen name, the derived 6-digit [responderSas] to compare against its screen, and the
     * [responderSecretHash] threaded into approve/deny. All non-null once `respondToPairingOffer`
     * resolves; null while [PairingStep.ResponderApproval] is still loading.
     */
    val responderDeviceName: String? = null,
    val responderSas: String? = null,
    val responderSecretHash: String? = null,
    /**
     * Hub-flow responder (hub proposal §11, [PairingWizardViewModel.respondToHubOffer]):
     * true when the approval prompt came from a scanned printed hub label. There is no
     * SAS in that flow ([responderSas] stays null) — the proxy verified the relay-posted
     * offer's signature against the printed key, and the approval copy says so.
     */
    val responderIsHub: Boolean = false,
    val isLoading: Boolean = false,
    val isPairingComplete: Boolean = false,
    val status: String? = null,
    /**
     * ⚠ A typed failure the UI renders; never a proxy sentence (§5.0). On the Show tab's host
     * role it is the host start's failure ([HostPairingMonitor.startError]): no code was minted,
     * so the surface offers a retry ([PairingWizardViewModel.onSelectShow]) instead of a spinner.
     */
    val error: PairingError? = null,
    /**
     * Typed scan-path error ([onQrScanned]); the screen maps each case to its localised §3.2
     * prompt via `stringResource`. Kept apart from the freetext [error] channel so the new
     * user-facing copy is authored in resources (ADR 0016), not in the VM.
     */
    val scanError: PairingScanError? = null,
) {

    /**
     * The responder has scanned and announced its key, but has **not decided yet** — so the SAS is
     * on screen while its approval prompt is still up (A3 of `sync-pairing-scanned-announce.md`).
     *
     * ⚠ Derived, not stored, deliberately. [offerSas] is populated on `scanned` *and* on
     * `approved`, and [offerPaired] is exactly what separates them — so a stored flag would be a
     * third value that could disagree with those two. It would also add a constructor field to a
     * shared `*UiState`, which owes the Swift build for no gain.
     */
    val offerScanned: Boolean get() = offerSas != null && !offerPaired

    /**
     * Whether the terminal on screen may **auto-continue** (`control-pairing-ux.md` §9). One rule
     * for every renderer — Compose and SwiftUI both read this rather than re-deriving it:
     *
     * - Always requires [fundingCheckSettled] and `!`[realmUnfunded]: a screen whose body may still
     *   change, or that carries the "Set up storage" CTA, never counts down.
     * - **Host** "Device added" ([hostPairingComplete]) — armed.
     * - **Joiner** [PairingStep.Success] — armed.
     * - **Offerer** paired terminal — armed only if [offerSasShownBeforeApproval]; otherwise the
     *   post-hoc SAS on that screen is the only comparison there was, and it stays manual.
     * - **Responder** [PairingStep.ResponderSuccess] — armed only in the hub flow
     *   ([responderIsHub], no SAS). The SAS responder stays manual: it cannot know whether the
     *   offerer saw the code before approval, and its screen exists for the side-by-side check.
     *
     * ⚠ Derived, not stored, for the reason [offerScanned] is: it adds no constructor field.
     */
    val autoContinueArmed: Boolean
        get() {
            if (!fundingCheckSettled || realmUnfunded) return false
            return when (mode) {
                AddDeviceMode.Show ->
                    if (offerRole) offerPaired && offerSasShownBeforeApproval else hostPairingComplete
                AddDeviceMode.Scan -> when (step) {
                    PairingStep.Success -> true
                    PairingStep.ResponderSuccess -> responderIsHub
                    else -> false
                }
            }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingWizardUiState) return false

        if (mode != other.mode) return false
        if (hostPairingString != other.hostPairingString) return false
        if (hostConnectionLost != other.hostConnectionLost) return false
        if (hostDeviceUnrecognized != other.hostDeviceUnrecognized) return false
        if (hostPairingComplete != other.hostPairingComplete) return false
        if (hostRequestDeviceName != other.hostRequestDeviceName) return false
        if (hostRequestSas != other.hostRequestSas) return false
        if (hostApprovalError != other.hostApprovalError) return false
        if (realmUnfunded != other.realmUnfunded) return false
        if (fundingCheckSettled != other.fundingCheckSettled) return false
        if (offerRole != other.offerRole) return false
        if (offerUri != other.offerUri) return false
        if (offerSas != other.offerSas) return false
        if (offerSasShownBeforeApproval != other.offerSasShownBeforeApproval) return false
        if (offerPaired != other.offerPaired) return false
        if (offerDenied != other.offerDenied) return false
        if (offerFailed != other.offerFailed) return false
        if (step != other.step) return false
        if (scannedDeviceId != other.scannedDeviceId) return false
        if (otherDevicePublicKey != null) {
            if (other.otherDevicePublicKey == null) return false
            if (!otherDevicePublicKey.contentEquals(other.otherDevicePublicKey)) return false
        } else if (other.otherDevicePublicKey != null) return false
        if (pairingSecret != other.pairingSecret) return false
        if (ownerId != other.ownerId) return false
        if (sas != other.sas) return false
        if (responderDeviceName != other.responderDeviceName) return false
        if (responderSas != other.responderSas) return false
        if (responderSecretHash != other.responderSecretHash) return false
        if (responderIsHub != other.responderIsHub) return false
        if (isLoading != other.isLoading) return false
        if (isPairingComplete != other.isPairingComplete) return false
        if (status != other.status) return false
        if (error != other.error) return false
        if (scanError != other.scanError) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + (hostPairingString?.hashCode() ?: 0)
        result = 31 * result + hostConnectionLost.hashCode()
        result = 31 * result + hostDeviceUnrecognized.hashCode()
        result = 31 * result + hostPairingComplete.hashCode()
        result = 31 * result + (hostRequestDeviceName?.hashCode() ?: 0)
        result = 31 * result + (hostRequestSas?.hashCode() ?: 0)
        result = 31 * result + (hostApprovalError?.hashCode() ?: 0)
        result = 31 * result + realmUnfunded.hashCode()
        result = 31 * result + fundingCheckSettled.hashCode()
        result = 31 * result + offerRole.hashCode()
        result = 31 * result + (offerUri?.hashCode() ?: 0)
        result = 31 * result + (offerSas?.hashCode() ?: 0)
        result = 31 * result + offerSasShownBeforeApproval.hashCode()
        result = 31 * result + offerPaired.hashCode()
        result = 31 * result + offerDenied.hashCode()
        result = 31 * result + offerFailed.hashCode()
        result = 31 * result + step.hashCode()
        result = 31 * result + (scannedDeviceId?.hashCode() ?: 0)
        result = 31 * result + (otherDevicePublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (pairingSecret?.hashCode() ?: 0)
        result = 31 * result + (ownerId?.hashCode() ?: 0)
        result = 31 * result + (sas?.hashCode() ?: 0)
        result = 31 * result + (responderDeviceName?.hashCode() ?: 0)
        result = 31 * result + (responderSas?.hashCode() ?: 0)
        result = 31 * result + (responderSecretHash?.hashCode() ?: 0)
        result = 31 * result + responderIsHub.hashCode()
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + isPairingComplete.hashCode()
        result = 31 * result + (status?.hashCode() ?: 0)
        result = 31 * result + (error?.hashCode() ?: 0)
        result = 31 * result + (scanError?.hashCode() ?: 0)
        return result
    }
}

/**
 * The scan branches of [PairingWizardViewModel.onQrScanned] that end in an error prompt
 * (`sync-pairing-inverted-direction.md` §3.2 + §5.2). The screen owns the localised copy.
 */
enum class PairingScanError {
    /** Retired `tenshu://pair?…` host — the code is from before the flag day; regenerate it. */

    /** A `tenshu://` role host this build doesn't know — the app is older than the code. */
    UnknownRole,

    /** Structurally broken payload. */
    Malformed,

    /**
     * Hub flow (hub proposal §11.4): the relay-fetched offer failed Ed25519 verification
     * against the scanned printed key — the code doesn't match this hub. Hard error.
     */
    HubVerifyFailed,

    /** Hub flow: no live offer at the rendezvous — the hub isn't armed for pairing. */
    HubNotArmed,

    /** Hub flow: the relay predates `/sync/pairing/hub-offer`. */
    HubUnsupported,
}

/**
 * Why a pairing step failed, in the wizard's own vocabulary.
 *
 * ⚠⚠ No member carries a message. `PairingWizardUiState.error` used to be a `String?` holding
 * whatever `ApprovePairingOfferImplementation` wrote — `core-error-copy-catalogue.md` §1 — and on
 * iOS `ControlSurface.pairingErrorMessage` handed that straight to SwiftUI.
 */
sealed class PairingError {
    /**
     * This device's relay admission is gone, so it cannot admit another. ⚠ Its own member because
     * the copy is specific and actionable ("pair this device again first"), and folding it into
     * [Unexpected] is what §1 was complaining about.
     */
    data object AdmitterUnrecognised : PairingError()

    /** This device holds no pairing authority at all. */
    data object NoAuthority : PairingError()

    /** Anything else, rendered from the shared catalogue by key. */
    data class Unexpected(val copyKey: ErrorCopyKey) : PairingError()
}

/**
 * Map a typed proxy failure onto the wizard's vocabulary. Never reads a message (§5.0).
 *
 * ⭐ The inner `when` is **exhaustive** over [PairingFailure] — no `else`. Adding a pairing failure
 * breaks this build rather than silently rendering the generic sentence, which is the whole reason
 * the reason set is a `TConstant` and not a `String`.
 */
internal fun TError.toPairingError(): PairingError =
    if (this !is PairingFailedError) PairingError.Unexpected(errorCopyKey())
    else when (reason) {
        PairingFailure.AdmitterUnrecognised -> PairingError.AdmitterUnrecognised
        PairingFailure.NoAuthority -> PairingError.NoAuthority
        PairingFailure.NoPendingOffer,
        PairingFailure.OfferNotFound,
        PairingFailure.VerifyFailed -> PairingError.Unexpected(errorCopyKey())
    }
