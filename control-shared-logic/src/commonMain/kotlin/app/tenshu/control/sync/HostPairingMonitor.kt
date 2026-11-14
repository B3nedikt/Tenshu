package app.tenshu.control.sync

import app.tenshu.control.ui.screens.sync.toPairingError
import app.tenshu.control.ui.screens.sync.PairingError
import app.tenshu.error.ErrorCopyKey
import app.tenshu.control.data.SyncRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.getOrNull
import org.tenshu.core.onFailure
import org.tenshu.core.messageOrNull
import org.tenshu.sync.GetPendingPairingRequests
import org.tenshu.sync.PairingRequest

/**
 * Process-lifetime owner of Control's host-side pairing poll
 * (`docs/proposals/control-pairing-ux.md`, Phase 1).
 *
 * The host's pairing session (`SyncManager.activePairingContext`) is *durable* — it lives
 * in the Control daemon process and survives the UI being torn down. Previously the poll
 * loop that surfaces an incoming approval request lived on the Sync screen's
 * `SyncDashboardViewModel.viewModelScope`, so it stopped the moment the user navigated away
 * from the Sync screen or closed the window — the request a paired device already submitted
 * would never surface (the tech-debt item "Control host-side pairing poll stops
 * mid-session"). This class hoists that loop onto a [scope] that is process-lifetime (a Koin
 * `single`, like `TenshuPendingRequestsRepository`), so the host keeps listening for the
 * whole session regardless of which screen is shown — and the approval surfaces as an
 * app-global modal mounted at the App root, not inside the Sync screen.
 *
 * [scope] is constructor-injectable (defaulting to `SupervisorJob() + Dispatchers.Default`)
 * so the unit test can drive it.
 */
class HostPairingMonitor(
    private val syncRepository: SyncRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** The pause between two polls for pending requests. A parameter so a test on real time can shorten it. */
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    /**
     * The pause itself, after each poll: [pollIntervalMs] of real time by default. A parameter so a
     * test can decide when the next poll happens rather than wait for it (`PairingVmFlowSystemTest`).
     */
    private val pacePoll: suspend () -> Unit = { delay(pollIntervalMs) },
) {

    /** The pending pairing request awaiting approval, or null when none. Drives the global
     *  approval modal. */
    private val _activeRequest = MutableStateFlow<PairingRequest?>(null)
    val activeRequest: StateFlow<PairingRequest?> = _activeRequest.asStateFlow()

    /** The live pairing string (QR / manual code) when a session is active; null otherwise.
     *  Surfaced for the Sync screen's "show QR" dialog. */
    private val _pairingString = MutableStateFlow<String?>(null)
    val pairingString: StateFlow<String?> = _pairingString.asStateFlow()

    /** True while the host has an active pairing session on the proxy side. The poll only
     *  runs while this is true. */
    private val _isPairing = MutableStateFlow(false)
    val isPairing: StateFlow<Boolean> = _isPairing.asStateFlow()

    /**
     * True once the host has approved a request and the proxy accepted it — a terminal
     * success for the *current* "Show a code" session. [approveActiveRequest] sets this and
     * [clearSessionFlows] deliberately does **not** clear it: approval clears the session in
     * the same breath (pairingString → null), and without this flag the Show screen would
     * fall straight back into its "no code yet" waiting spinner and sit there forever. It is
     * reset the next time a session begins or is torn down ([startPairing] / [stopPairing] /
     * [denyActiveRequest]), so re-entering the Show tab shows a fresh QR rather than a stale
     * success.
     */
    private val _pairingComplete = MutableStateFlow(false)
    val pairingComplete: StateFlow<Boolean> = _pairingComplete.asStateFlow()

    /**
     * Set when the host's pairing-request poll fails (typically a relay 401 because this
     * device's registration was lost on a relay restart). Surfaced so a silent failure no
     * longer looks identical to "nobody has asked to pair yet". Cleared on the next clean
     * poll.
     */
    private val _connectionLost = MutableStateFlow(false)
    val connectionLost: StateFlow<Boolean> = _connectionLost.asStateFlow()

    /**
     * Set when the poll failed because the relay **does not recognise this device** (`401`,
     * [GetPendingPairingRequests.ERROR_DEVICE_UNRECOGNIZED]) rather than being unreachable, **and**
     * the automatic repair could not fix it — i.e. this device holds no master identity, so it is a
     * pairing joiner whose only route back is being paired again.
     *
     * ⚠⚠ Kept separate from [connectionLost] because the two need opposite things from the user, and
     * conflating them made the pairing banner wrong twice over: it said *"Can't reach the relay —
     * retrying…"* when the relay had in fact answered (sending the user to check a network that is
     * fine) and promised a retry that cannot succeed. Observed 2026-08-17 on a desktop whose
     * registration a relay wipe had dropped.
     *
     * An A1-holding device normally never shows this: [maybeReregisterDevice] repairs it and the
     * next poll is clean. Cleared on any clean poll.
     */
    private val _deviceUnrecognized = MutableStateFlow(false)
    val deviceUnrecognized: StateFlow<Boolean> = _deviceUnrecognized.asStateFlow()

    /**
     * Seconds left in the bounded *waiting-for-joiner* window, or null when no session is
     * armed (`docs/proposals/control-pairing-ux.md` §3.5). A host session self-destructs when
     * this hits zero with no request in flight — the ephemeral X25519 key the SAS is derived
     * from lives in memory only, so a session left open indefinitely is dead weight a paired
     * device can no longer answer. The countdown **pauses** the moment a request arrives
     * ([activeRequest] non-null): SAS approval is a human comparison step that must not be
     * guillotined mid-approval. Surfaced for the "in Pairing" placeholder row + the Android
     * foreground-service countdown notification.
     */
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

    /**
     * A user-facing reason the last approval failed, or null. Set when
     * [approveActiveRequest] fails (most notably: this device is a phrase-less joiner and
     * can't admit new devices — see `delegated-device-admission-authority.md` P0) so the
     * approval modal surfaces *why* instead of silently dismissing. Cleared on the next
     * session transition ([clearSessionFlows]).
     */
    private val _approvalError = MutableStateFlow<PairingError?>(null)
    val approvalError: StateFlow<PairingError?> = _approvalError.asStateFlow()

    /**
     * Why the last [startPairing] produced no session, or null. A start that yields no code — a
     * failed `StartPairing`, or a success carrying no pairing string — is **not** a session:
     * [isPairing] stays false and no countdown is armed, so the Show surface can say the start
     * failed instead of spinning on "still starting" for a session that does not exist. Cleared
     * when the next start begins, and by a start that succeeds.
     *
     * ⚠ NOT cleared when a session ends ([clearSessionFlows]). Stop and start are independent
     * coroutines, so a stop issued BEFORE a start can land after it — and would erase the failure
     * that start just reported, putting the Show surface back on the spinner this exists to end.
     * A session that ends had a successful start, so there is nothing of its own to clear.
     */
    private val _startError = MutableStateFlow<PairingError?>(null)
    val startError: StateFlow<PairingError?> = _startError.asStateFlow()

    /** The active countdown coroutine, cancelled/replaced on every session transition. */
    private var countdownJob: Job? = null

    /**
     * Makes "is the session this poll was asked under still live?" and "publish its answer" one
     * step, so a session cannot end in between.
     *
     * ⚠⚠ A poll's answer may only be published into the session it was asked under. A poll is
     * answered a relay round-trip after it is asked, and the proxy reads the pairing context when
     * the poll *starts* — so one in flight while the user approves (or denies, or the window
     * expires) still comes back carrying the request. Published unconditionally it reopens the
     * approval modal (shown whenever [activeRequest] is non-null) on a device that was just
     * approved, until the next 2 s tick withdraws it; and across a stop → start it puts the
     * previous session's request, with the previous session's SAS, into the new session, where
     * an empty poll never clears it.
     *
     * [sessionEpoch] moves every time a session ends ([clearSessionFlows]); both are touched only
     * under [sessionLock].
     */
    private val sessionLock = Mutex()
    private var sessionEpoch = 0

    init {
        scope.launch {
            resumeActivePairingSession()
        }
        startPairingObserver()
    }

    /**
     * Re-adopt a pairing session that is still live on the proxy after the UI (and the old
     * VM-scoped poll) was torn down.
     *
     * The host's pairing session (`SyncManager.activePairingContext`) is *durable* — it
     * lives in the Control daemon process and survives the UI being torn down. The monitor,
     * however, may be (re)constructed with `isPairing = false`. Because
     * [startPairingObserver] only polls while `isPairing` is true, a request the other
     * device already submitted would never surface after such a restart — the user would
     * have to click "Add device" again to re-arm polling, even though the session was never
     * actually lost.
     *
     * [SyncRepository.getSyncStatus] reports the live pairing string read-only (no mutation,
     * no fresh secret). If one is present and we aren't already tracking a session, adopt it
     * so the observer resumes. The approve dialog auto-opens on its own once a request
     * arrives (the global modal shows whenever `activeRequest != null`).
     */
    private suspend fun resumeActivePairingSession() {
        // ⚠⚠ Caught, as the poll loop's body is: [scope] has no exception handler, and on K/N an
        // uncaught throw there aborts the process. This is often the call that mints the in-process
        // session, and a mint over iOS's not-ready stand-in (or a fenced store) throws from
        // `initFileService`'s first write rather than answering a TResult. Nothing is lost: the
        // user's next "Add device" starts a session, and the graph is rebuilt over the real store.
        try {
            if (_isPairing.value) return
            val pairingString = syncRepository.getSyncStatus().getOrNull()?.pairingString?.get()
            if (pairingString != null && !_isPairing.value) {
                _pairingString.value = pairingString
                _isPairing.value = true
                // A resumed session gets a fresh bound. The proxy holds no deadline (the window is
                // a client-side UX/liveness bound, not a protocol one), so we can't recover the
                // original remaining time — and §3.5 makes a restart cheap by design.
                armCountdown()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("HostPairingMonitor: resuming the pairing session threw: $e")
        }
    }

    private fun startPairingObserver() {
        scope.launch {
            while (isActive) {
                try {
                    // Poll while a pairing session is active on the host side, regardless
                    // of whether the QR dialog is currently shown. The natural workflow
                    // is "open dialog → copy/show code → close dialog → use the other
                    // device → return to host to approve". Gating on dialog visibility
                    // (the previous `isPairing == true` check) broke that workflow: the
                    // observer stopped the moment the user closed the dialog, and by the
                    // time the other device's request arrived the host wasn't listening.
                    // The dialog now auto-reopens with the Approve/Deny UI when a request
                    // is detected (the global modal shows whenever `activeRequest != null`
                    // regardless of `isPairing`).
                    val session = liveSessionOrNull()
                    if (session != null) {
                        val result = syncRepository.getPendingPairingRequests()
                        // A failed poll is no longer swallowed by the op (see
                        // GetPendingPairingRequestsImplementation). Surface it so
                        // the host doesn't sit showing the QR forever while the
                        // joiner waits — the common cause is a relay 401 after the
                        // relay forgot this device's registration on a restart.
                        result.onFailure { error ->
                            // Which of the two failures is this? A `401` means the relay answered
                            // and refused us; anything else means we could not reach it. The repair
                            // is attempted either way (it is idempotent, and a transient failure
                            // simply fails again), but only the identity case may claim the device
                            // is unrecognised — and only once the repair has had its chance.
                            val refused = error.messageOrNull()
                                ?.contains(GetPendingPairingRequests.ERROR_DEVICE_UNRECOGNIZED) == true
                            _connectionLost.value = !refused
                            maybeReregisterDevice(refused = refused)
                        }
                        val requests = result.getOrNull()?.toList() ?: emptyList()
                        val match = requests.firstOrNull()
                        if (match != null) {
                            publishRequest(session, match)
                            _connectionLost.value = false
                            _deviceUnrecognized.value = false
                        } else if (result.getOrNull() != null) {
                            // A clean (authenticated) empty poll clears any prior banner — both of
                            // them: the poll authenticating IS the proof that the relay recognises
                            // this device again (that is how an A1 holder's repair becomes visible).
                            _connectionLost.value = false
                            _deviceUnrecognized.value = false
                        }
                    } else if (_activeRequest.value != null) {
                        _activeRequest.value = null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                pacePoll()
            }
        }
    }

    fun approveActiveRequest() {
        val request = _activeRequest.value ?: return
        scope.launch {
            val result = syncRepository.approvePairingRequest(request.deviceId.get())
            if (result is TResult.Success<*>) {
                // Surface a terminal success so the "Show a code" screen leaves its waiting
                // spinner — clearSessionFlows just nulled pairingString, which would otherwise
                // render the "no code yet" spinner indefinitely.
                // ⚠ Raised BEFORE the session is retired: an observer of [isPairing] going false
                // must be able to tell "a device was added" from "the session was abandoned".
                cancelCountdown()
                _pairingComplete.value = true
                clearSessionFlows()
            } else {
                // Approval failed. The common P0 case: this device is a phrase-less joiner and
                // can't admit new devices (delegated-device-admission-authority.md). Keep the
                // modal up and surface the reason instead of silently dismissing — the user
                // dismisses via Deny. Leaving the session intact means the poll keeps the
                // request visible so the message stays put.
                // ⚠⚠ A typed failure, not the proxy's sentence (§5.0). The common P0 case —
                // a phrase-less joiner that cannot admit devices — is now
                // `PairingError.AdmitterUnrecognised`, with its copy in the app.
                _approvalError.value = (result as? TResult.Failure<*>)?.error?.toPairingError()
                    ?: PairingError.Unexpected(ErrorCopyKey.UNEXPECTED)
            }
        }
    }

    fun denyActiveRequest() {
        scope.launch {
            syncRepository.stopPairing()
            cancelCountdown()
            clearSessionFlows()
            _pairingComplete.value = false
        }
    }

    /** Returns the start's job, so a caller can read [startError] once this attempt has landed. */
    fun startPairing(): Job =
        scope.launch {
            // A fresh session supersedes any prior session's terminal success.
            _pairingComplete.value = false
            _startError.value = null
            val result = syncRepository.startPairing()
            val pairingString = result.getOrNull()?.pairingString?.get()
            _connectionLost.value = false
            // A new attempt must re-discover whether the relay accepts us: the flag is only ever
            // cleared by a clean poll, and stopping the session (below) stops polling — so without
            // this reset a device that has since been re-paired would still be told it is
            // unrecognised the next time this tab is opened.
            _deviceUnrecognized.value = false
            if (pairingString == null) {
                // ⚠ No code, no session: never raise [isPairing] or arm the countdown here. A
                // session already live (re-entering Show re-runs this start) keeps its code — it is
                // still being served, so only a start with nothing to show reports a failure.
                if (!_isPairing.value) {
                    _startError.value = (result as? TResult.Failure<*>)?.error?.toPairingError()
                        ?: PairingError.Unexpected(ErrorCopyKey.UNEXPECTED)
                }
                return@launch
            }
            // An overlapping attempt (a second tap) may have failed first; this one is the truth.
            _startError.value = null
            _pairingString.value = pairingString
            _isPairing.value = true
            armCountdown()
        }

    fun stopPairing() {
        scope.launch {
            syncRepository.stopPairing()
            cancelCountdown()
            clearSessionFlows()
            _pairingComplete.value = false
        }
    }

    /**
     * Arm (or re-arm) the bounded waiting window. Cancels any prior countdown, then ticks
     * down once per second while the session is *waiting* — paused whenever a request is in
     * flight ([activeRequest] non-null, the human SAS-comparison step). On expiry with no
     * request the session self-destructs: the proxy context is cleared and its ephemeral key
     * zeroed (`SyncManagerImpl.stopPairing`), and the local session flows reset.
     */
    private fun armCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            _remainingSeconds.value = PAIRING_WINDOW_SECONDS
            while (isActive && (_remainingSeconds.value ?: 0) > 0) {
                delay(1000)
                // Hold the countdown while a request awaits human approval — see
                // [remainingSeconds]. The request only clears via approve/deny (which
                // cancel this job), so a paused window simply idles until teardown.
                if (_activeRequest.value != null) continue
                _remainingSeconds.update { (it ?: 0) - 1 }
            }
            // Timed out while still waiting for a joiner — a session that expired failed
            // anyway, so tear it all down (and let the proxy zero the ephemeral key).
            if (_isPairing.value && _activeRequest.value == null) {
                syncRepository.stopPairing()
                clearSessionFlows()
            }
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    /**
     * Reset every per-session flow to the no-session baseline, and retire the session so a poll
     * still in flight for it can no longer publish ([sessionLock]).
     */
    private suspend fun clearSessionFlows() {
        sessionLock.withLock {
            sessionEpoch += 1
            _pairingString.value = null
            _activeRequest.value = null
            _isPairing.value = false
            _remainingSeconds.value = null
            _approvalError.value = null
        }
    }

    /** The live session's epoch, or null when no session is live — the poll's gate. */
    private suspend fun liveSessionOrNull(): Int? = sessionLock.withLock {
        if (_isPairing.value) sessionEpoch else null
    }

    /** Surface [request] iff the session its poll was issued under ([session]) has not ended. */
    private suspend fun publishRequest(session: Int, request: PairingRequest) {
        sessionLock.withLock {
            if (session == sessionEpoch) _activeRequest.value = request
        }
    }

    // Guards [maybeReregisterDevice] so a persistent failure (polled every 2s) doesn't
    // hammer the relay with re-bootstrap posts — at most one attempt per backoff window.
    private var reregisterInFlight = false

    /**
     * Attempt to re-assert this device's relay registration after a failed pairing poll.
     *
     * Background: the relay's known-devices registry is process-local (in-memory until
     * the Phase 4 persistence work). When the relay restarts under a long-lived,
     * autostarted Control daemon, this device's registration is gone and every
     * JWT-authenticated call — including the host's pairing-request poll — 401s, with
     * nothing re-asserting it. That is what makes the approval dialog never appear.
     *
     * The underlying [SyncRepository.reregisterDevice] op is idempotent and safe to
     * retry, so this fires on *any* poll failure rather than discriminating
     * auth-vs-network: a transient network blip just fails again (re-tried next window),
     * while a 401 gets repaired. On success the banner clears and the next poll
     * authenticates. A pairing joiner (no master identity) gets a typed failure and
     * stays in the banner state — its recourse is re-pairing.
     *
     * Note: re-registration is cross-cutting (the sync push/pull loop hits the same
     * 401); the longer-term home is the relay client / SyncManager auto-retrying on 401.
     * This is the pairing-flow entry point, where the bug first surfaced.
     */
    private fun maybeReregisterDevice(refused: Boolean = false) {
        if (reregisterInFlight) {
            // A repair is already in flight from an earlier failure. Don't claim the device is
            // unrecognised yet — that verdict belongs to the attempt that actually completes.
            return
        }
        reregisterInFlight = true
        scope.launch {
            try {
                val result = syncRepository.reregisterDevice()
                if (result is TResult.Success<*>) {
                    // Registration restored — drop both banners; the next poll authenticates.
                    _connectionLost.value = false
                    _deviceUnrecognized.value = false
                } else if (refused) {
                    // The relay refused this device AND the repair could not fix it — this device
                    // holds no master identity (a pairing joiner), so retrying will never help and
                    // the only route back is being paired again. Say that, instead of "can't reach
                    // the relay — retrying…", which is false on both halves.
                    _deviceUnrecognized.value = true
                    _connectionLost.value = false
                    // ⚠⚠ And END the session. A host session on an unrecognised device cannot
                    // complete by construction: the joiner's request lands in the relay mailbox,
                    // this device's `GET /sync/pairing/requests` 401s, and it never sees it — so the
                    // other device spins on "Confirm on your other device" forever while a QR sits
                    // on screen inviting exactly that. Observed 2026-08-17, after the banner alone
                    // proved not to be enough: the screen showed "this device isn't recognised"
                    // directly above a code for adding devices.
                    stopPairing()
                }
                // Any other failure (network) leaves the connection banner up; the next failed poll
                // re-triggers after the backoff window.
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // Debounce regardless of the 2s poll cadence.
                delay(REREGISTER_BACKOFF_MS)
                reregisterInFlight = false
            }
        }
    }

    private companion object {
        /** Min gap between device re-registration attempts (poll cadence is 2s). */
        const val REREGISTER_BACKOFF_MS = 15_000L

        /** How often a live session asks the relay for pending pairing requests. */
        const val POLL_INTERVAL_MS = 2_000L

        /**
         * Bounded waiting window before a host session self-destructs (§3.5). 2 minutes:
         * 30 s was rejected as too tight for the human flow (the joiner must pick up the
         * other device, open the app, and scan before the host even compares the SAS); the
         * draft spec's 5-min value is superseded here.
         */
        const val PAIRING_WINDOW_SECONDS = 120
    }
}
