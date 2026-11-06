package app.tenshu.control.sync

import app.tenshu.control.data.SyncRepository
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
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.getOrNull
import org.tenshu.core.onFailure
import org.tenshu.sync.OfferStatus

/**
 * Single owner of the **offerer**-side pairing poll, extracted from
 * `PairingWizardViewModel.startOfferSession`.
 *
 * ## ⚠ Lifecycle stays with the VM — deliberately, unlike [HostPairingMonitor]
 *
 * The obvious move is to hoist this to process lifetime the way the host poll was, and it would
 * be wrong here. The host's session is *durable by design*; the offer session is deliberately
 * ephemeral — `sync-pairing-inverted-direction.md` §5.1 and the VM's own note: *"an abandoned
 * offer has no approval to surface globally, so leaving the screen tears it down"*. Surviving
 * navigation would leave a pairing QR valid after the user walked away, with nothing on screen to
 * report the outcome. That is a product decision with a security flavour, not a refactor, so this
 * class is *started and cancelled by the VM* and today's teardown-on-leave is unchanged.
 *
 * What the extraction buys is a single, testable place where the offer status is obtained — which
 * is what makes `sync-relay-api.md` Phase 3 (long-poll on pairing) a drop-in later: the interval
 * below becomes a held-open request and nothing outside this file moves.
 *
 * ## Two defects this fixes on the way
 *
 * ⚠⚠ **A failed poll was silently swallowed** — `getOfferStatus(hash).getOrNull() ?: continue`.
 * An offerer whose status calls all failed waited forever with nothing on screen: the ownerless
 * eternal spinner the offer role exists to avoid. Failures now raise [connectionLost], which a
 * later clean poll clears — the transient-banner shape [HostPairingMonitor] already uses. ⚠ It is
 * deliberately **not** terminal: a blip must not kill a live pairing.
 *
 * ⚠⚠ **Nothing bounded the wait.** There is no failure count here on purpose — a count has to
 * answer "how many is too many", and every answer is wrong in one direction. The bound is a
 * **deadline**, mirroring [HostPairingMonitor]'s countdown: [OFFER_WINDOW_SECONDS] is the
 * offerer's client-side window, and the relay's `PAIRING_OFFER_RESPONSE_TTL_MS` is deliberately
 * that window **plus grace**, so the client stops while the code is still servable rather than
 * after it has silently died. On expiry the offer is cancelled proxy-side and [failed] is raised,
 * which the user resolves by showing a new code — the honest, recoverable outcome.
 *
 * ⚠ Expiry raises [failed] rather than a distinct "expired" state so `PairingWizardUiState` keeps
 * its exact shape: iOS mirrors that state in `PairingView.swift`, and a new field would surface
 * nowhere there. A dedicated "this code expired" message is a UI refinement, tracked separately.
 *
 * [scope] is constructor-injectable (defaulting to `SupervisorJob() + Dispatchers.Default`) so
 * tests can drive it, exactly as [HostPairingMonitor] does. ⚠ It outlives the VM only so
 * [cancel]'s suspend proxy-side call can complete after `onCleared` — not so the session does.
 */
class OfferPairingMonitor(
    private val syncRepository: SyncRepository,
    /**
     * Runs the poll and the countdown. ⚠ Pass the **VM's** scope: these must die with the screen,
     * and doing that structurally beats relying on [cancel] being called. Passing a longer-lived
     * scope here compiles and mostly works, which is exactly why it is worth saying — the poll
     * would then outlive the VM and stop only by discipline.
     */
    private val pollScope: CoroutineScope,
    /**
     * Runs the proxy-side cancel only. ⚠ Must **outlive** the VM: [cancel] is called from
     * `onCleared`, by which point `viewModelScope` is already cancelled and a suspend call
     * launched there would never run — the offer would be orphaned on the relay until its TTL.
     */
    private val teardownScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** The `tenshu://offer?…` URI to render as a QR, or null when no session is live. */
    private val _offerUri = MutableStateFlow<String?>(null)
    val offerUri: StateFlow<String?> = _offerUri.asStateFlow()

    /**
     * The 6-digit SAS once it is derivable. ⚠ Set on **scanned** as well as approved (A3): the
     * responder's announce makes it derivable while their approval prompt is still up, which is
     * the whole point of the announce.
     */
    private val _sas = MutableStateFlow<String?>(null)
    val sas: StateFlow<String?> = _sas.asStateFlow()

    /**
     * Sticky per session: a `scanned` status carrying a SAS was observed **before** `approved`, so
     * the code was on this screen while the responder's prompt was still up (A3). Set in the same
     * step that surfaces that SAS, so it cannot be confused with the SAS an `approved` status
     * carries — which is why it lives here and not in a VM collector watching [sas] and [paired].
     * ⚠ The announce is best-effort; `false` after pairing means the comparison was post-hoc only.
     */
    private val _sasShownBeforeApproval = MutableStateFlow(false)
    val sasShownBeforeApproval: StateFlow<Boolean> = _sasShownBeforeApproval.asStateFlow()

    /** Terminal: the responder approved and this device is paired. */
    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    /** Terminal: the responder declined. */
    private val _denied = MutableStateFlow(false)
    val denied: StateFlow<Boolean> = _denied.asStateFlow()

    /** Terminal: the offer could not be minted, or the window closed with no decision. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /**
     * Transient: the last status poll did not reach the relay. Cleared by the next clean poll.
     * ⚠ Not terminal — see the class KDoc. This exists so the wait is *visible*, not so it ends.
     */
    private val _connectionLost = MutableStateFlow(false)
    val connectionLost: StateFlow<Boolean> = _connectionLost.asStateFlow()

    /** Seconds left in the offer window, or null when no session is live. */
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

    private var secretHash: String? = null
    private var pollJob: Job? = null
    private var countdownJob: Job? = null

    /** True while a session is live and undecided — the guard that makes [start] idempotent. */
    private val isLive: Boolean
        get() = secretHash != null && !_paired.value && !_denied.value && !_failed.value

    /**
     * Mint an offer and begin polling. **Idempotent**: re-entering the Show screen while a session
     * is live re-adopts it rather than minting a second code and orphaning the first — the QR the
     * user is already showing keeps working.
     */
    fun start(deviceName: String) {
        if (isLive) return
        cancelJobs()
        _offerUri.value = null
        _sas.value = null
        _sasShownBeforeApproval.value = false
        _paired.value = false
        _denied.value = false
        _failed.value = false
        _connectionLost.value = false

        pollJob = pollScope.launch {
            val session = syncRepository.offerPairing(deviceName).getOrNull()
            if (session == null) {
                _failed.value = true
                return@launch
            }
            val hash = session.pairingSecretHash.get()
            secretHash = hash
            _offerUri.value = session.offerUri.get()
            armCountdown()

            while (isActive) {
                delay(OFFER_POLL_MS)
                val result = syncRepository.getOfferStatus(hash)
                result.onFailure { _connectionLost.value = true }
                val status = result.getOrNull() ?: continue
                _connectionLost.value = false
                when (status.state.get()) {
                    OfferStatus.APPROVED -> {
                        secretHash = null
                        _sas.value = status.sas.get().takeIf(String::isNotEmpty)
                        _paired.value = true
                        cancelCountdown()
                        return@launch
                    }

                    // A3: scanned and announced, but NOT decided. Derivable SAS goes on screen
                    // while the responder's prompt is still up. Not terminal — keep polling.
                    OfferStatus.SCANNED -> {
                        val sas = status.sas.get().takeIf(String::isNotEmpty)
                        _sas.value = sas
                        if (sas != null) _sasShownBeforeApproval.value = true
                    }

                    OfferStatus.DENIED -> {
                        secretHash = null
                        _offerUri.value = null
                        _denied.value = true
                        cancelCountdown()
                        return@launch
                    }

                    else -> Unit // pending — keep polling
                }
            }
        }
    }

    /** Tear the session down and fire the idempotent proxy-side cancel. */
    fun cancel() {
        cancelJobs()
        val hash = secretHash
        secretHash = null
        if (hash != null) {
            teardownScope.launch {
                bestEffort("OfferPairingMonitor -> withdrawing the offer on teardown") {
                    syncRepository.cancelPairingOffer(hash)
                }
            }
        }
        _offerUri.value = null
        _sas.value = null
        _sasShownBeforeApproval.value = false
        _paired.value = false
        _denied.value = false
        _failed.value = false
        _connectionLost.value = false
        _remainingSeconds.value = null
    }

    /**
     * The window. On expiry with no decision the offer is cancelled proxy-side and [failed] is
     * raised — the code is dead either way, and saying so beats an unbounded wait.
     */
    private fun armCountdown() {
        countdownJob?.cancel()
        countdownJob = pollScope.launch {
            _remainingSeconds.value = OFFER_WINDOW_SECONDS
            while (isActive && (_remainingSeconds.value ?: 0) > 0) {
                delay(1000)
                _remainingSeconds.update { (it ?: 0) - 1 }
            }
            if (isActive && isLive) {
                val hash = secretHash
                secretHash = null
                pollJob?.cancel()
                if (hash != null) {
                    bestEffort("OfferPairingMonitor -> withdrawing the offer that timed out") {
                        syncRepository.cancelPairingOffer(hash)
                    }
                }
                _offerUri.value = null
                _remainingSeconds.value = null
                _failed.value = true
            }
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _remainingSeconds.value = null
    }

    private fun cancelJobs() {
        pollJob?.cancel()
        pollJob = null
        cancelCountdown()
    }

    companion object {
        /** Status poll cadence — unchanged from the VM-scoped loop this replaces. */
        const val OFFER_POLL_MS = 2_000L

        /**
         * The offerer's client-side window. ⚠ Deliberately shorter than the relay's
         * `PAIRING_OFFER_RESPONSE_TTL_MS` (7 min = this window **plus grace**), so the client
         * stops while the code is still servable rather than after it has silently expired.
         */
        const val OFFER_WINDOW_SECONDS = 300
    }
}
