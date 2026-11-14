package app.tenshu.control.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.SyncRepository
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.identity.Device
import app.tenshu.control.sync.HostPairingMonitor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.getOrNull
import org.tenshu.core.messageOrNull

/**
 * Sync screen VM. Owns only the device-list + realm-bootstrap concerns.
 *
 * Host-side pairing (the poll, the pairing string, start/stop/approve/deny) lives entirely
 * in the process-lifetime [app.tenshu.control.sync.HostPairingMonitor]
 * (`docs/proposals/control-pairing-ux.md`). The only "add a device" entry point is the FAB →
 * unified "Add a device" screen ([PairingWizardViewModel] owns both the Scan and Show roles),
 * so this VM surfaces no pairing state. The Approve/Deny
 * approval surface is the app-global modal mounted at the App root.
 */
class SyncDashboardViewModel(
    private val syncRepository: SyncRepository,
    private val pairingMonitor: HostPairingMonitor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncDashboardUiState())
    val uiState: StateFlow<SyncDashboardUiState> = _uiState.asStateFlow()

    private var currentDeviceId: String = ""

    /**
     * Serialises refresh passes.
     *
     * ⚠⚠ **Declared here, above the `init` blocks, and it has to stay above them.** Every surface
     * runs `viewModelScope` on `Dispatchers.Main.immediate` — the Swing EDT on desktop, the main
     * queue on K/N — so a `launch` from an `init` block does **not** hand the body to a queue: it
     * runs INLINE, inside the constructor, until something genuinely suspends. The roster
     * collector below suspends at nothing when the repository answers from memory (the iOS
     * not-connected/fake repositories, and every test fake), and `collectLatest` starts its block
     * undispatched on top of that — so [refreshNow] executes while the constructor is still part
     * way down the class body. A property declared *after* those `init` blocks is still `null`
     * then, and `withLock` on a null `Mutex` raises an NPE with `<init>` still on the stack: it
     * kills the collector, so the roster never lands and the screen sits empty on its spinner —
     * and on iOS it crosses into Swift undeclared, which aborts. Kotlin initialises in declaration
     * order and warns about nothing here, because the read is behind a lambda.
     *
     * ⚠⚠ [refresh] launches a detached coroutine, so without this every trigger — the roster
     * collector, `approveDevice`, `createRealm`, `revokeDevice`, `readmitDevice` — runs a pass
     * concurrently with the others. They are all fired by the same actions that make the roster
     * emit, each issues a different number of IPC round trips, and each ends in one whole-snapshot
     * `_uiState.update`. So completion order is not start order, and a slower earlier pass
     * finishing last overwrites the newer roster with a stale one.
     *
     * ⚠ The lock also keeps `isLoading` honest: overlapping passes would let the first one's
     * `finally` clear the spinner while a later pass is still reading.
     */
    private val refreshLock = Mutex()

    init {
        // ⚠⚠ Loading from construction, before anything suspends. Until the relation's first
        // emission lands the state is `SyncDashboardUiState()` defaults — `isLoading = false`,
        // `isSyncEnabled = false` — and the screen skips the progress bar while the first is false
        // and renders the "no realm — Create Realm" onboarding prompt while the second is. So a
        // device that HAS a realm would flash an invitation to create one, for as long as the
        // subscribe plus first emission takes. Setting it here rather than inside the first pass is
        // what closes the window, because the pass itself is what we are waiting for.
        _uiState.update { it.copy(isLoading = true) }
    }

    init {
        // ⭐ The roster is a live relation (`IdentityService.devices`), and this collects it: with a
        // one-shot `first()` a device joining from elsewhere does not appear until something
        // happens to call [refresh] — re-entering the screen, approving, or creating a realm —
        // even though its row is already in the local store.
        //
        // ⚠⚠ The device id is resolved FIRST, in the same coroutine, because every pass writes it
        // into the state. Starting the collector in a second coroutine would let a pass land before
        // the id was known and publish an empty one.
        //
        // ⚠ Re-running the existing pass rather than mapping the flow into state directly: the
        // screen also reads a sync-policy row, the admission lineage and last-seen per device, all
        // one-shot by nature. One code path for "what this screen shows" beats two that can differ.
        //
        // ⚠ `collectLatest`, not `collect`: a pass is several suspending IPC reads, so a newer
        // emission must cancel the one it supersedes rather than race it.
        //
        // ⚠ No separate initial refresh. The relation always emits — `TenshuSyncRepository` maps a
        // failed read to `emptyList()` rather than nothing — so the first emission IS the initial
        // load. A flow that genuinely never emitted would throw in `devices.first()` inside the
        // pass anyway, which this screen does not handle.
        viewModelScope.launch {
            currentDeviceId = syncRepository.getDeviceId()
            syncRepository.devices.collectLatest { roster -> refreshNow(roster) }
        }
        observePairingSession()
        // Hardware models arrive on their own schedule — a newly joined peer publishes its row only
        // after it has joined — so they are collected separately and patched into the state rather
        // than read once per roster pass.
        viewModelScope.launch {
            syncRepository.deviceHardwareNames.collect { names ->
                _uiState.update { it.copy(deviceHardwareNames = names) }
            }
        }
    }

    /**
     * Mirror the process-lifetime [HostPairingMonitor] session state into the dashboard so it
     * can render the "in Pairing" placeholder row (control-pairing-ux.md §3.5): a visible home
     * + re-entry point while a host session counts down, flipping to "wants to join" when a
     * request arrives. The approval itself stays the app-global modal — this row only routes
     * the user back to the pairing surface.
     */
    private fun observePairingSession() {
        viewModelScope.launch {
            combine(
                pairingMonitor.isPairing,
                pairingMonitor.remainingSeconds,
                pairingMonitor.activeRequest,
            ) { pairing, remaining, request ->
                Triple(pairing, remaining, request != null)
            }.collect { (pairing, remaining, requestPending) ->
                _uiState.update {
                    it.copy(
                        isPairing = pairing,
                        pairingRemainingSeconds = remaining,
                        pairingRequestPending = requestPending,
                    )
                }
            }
        }
    }

    /**
     * Approves a device-list "Pending approval" row. NOTE: this is the device-management
     * approve (a device the user wants to authorise from the list), distinct from the
     * host-side *pairing* approval, which the global modal handles via the monitor.
     */
    fun approveDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            syncRepository.approvePairingRequest(deviceId)
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    /**
     * @param emitted the roster the collector just received, or `null` for an action-triggered
     *   pass that has none.
     *
     * ⚠⚠ The collector MUST pass its value through rather than let the pass re-read. Production's
     * `StreamInteractorImpl` catches a late subscriber up by replaying its buffer **from the
     * initial snapshot**, so a `devices.first()` inside the pass opens a new subscription and gets
     * the ORIGINAL roster back — the fresh one the collector received is discarded and the screen
     * stays stale. ⚠ A `MutableStateFlow` replays
     * only the newest value, so a test built on one cannot see this.
     */
    private suspend fun refreshNow(emitted: List<Device>? = null) = refreshLock.withLock {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // One-shot read: this screen renders a snapshot per refresh() rather than
                // tracking the roster continuously.
                // ⚠ Only an action-triggered pass re-reads; see the note on [emitted].
                val allDevices = emitted ?: syncRepository.devices.first()
                val isEnabled =
                    syncRepository.getSyncStatus().getOrNull()?.isEnabled?.get() == true
                val authorized = allDevices.filter { dev -> dev.isAuthorized.get() }
                // Read each device's offline-sync policy (read-only, §4.6); skip
                // devices with no explicit policy row so they show no line.
                val policies = authorized.mapNotNull { dev ->
                    val id = dev.deviceId.get()
                    syncRepository.deviceSyncPolicy(id)?.let { id to it }
                }.toMap()
                // Admission lineage ("Added by …"): resolve each device's admitter id
                // to its display name within the same list. A removed admitter falls
                // back to its raw id — still actionable forensics for the user.
                val namesById = allDevices.associate { it.deviceId.get() to it.name.get() }
                val admittedBy = authorized.mapNotNull { dev ->
                    val id = dev.deviceId.get()
                    val by = dev.admittedByDeviceId?.get()
                        ?.takeIf { b -> b.isNotEmpty() && b != id }
                        ?: return@mapNotNull null
                    id to (namesById[by] ?: by)
                }.toMap()
                // One read for the whole roster (a relation, not an op per row). Best-effort: a
                // failure here must not empty the device list, so it degrades to "no last-seen
                // line" rather than taking the screen down with it.
                val lastSeen = runCatchingCancellable { syncRepository.deviceLastSeen() }.getOrDefault(emptyMap())
                _uiState.update {
                    it.copy(
                        devices = authorized,
                        deviceSyncPolicies = policies,
                        deviceAdmittedBy = admittedBy,
                        deviceLastSeen = lastSeen,
                        currentDeviceId = currentDeviceId,
                        isSyncEnabled = isEnabled,
                    )
                }
            } finally {
                // Guaranteed regardless of how the reads above end — the spinner must never be
                // left spinning.
                _uiState.update { it.copy(isLoading = false) }
            }
    }

    /**
     * Bootstraps a new realm using this device's locally-stored recovery phrase.
     *
     * Explicit, never automatic on refresh. Creating a realm is a destructive choice — it
     * commits the device to a master identity that the relay then registers; pairing into a
     * *different* realm afterwards requires a factory reset. An automatic bootstrap would let
     * two devices booting to the dashboard each silently create their own realm with disjoint
     * master keys, then never be able to pair to each other.
     */
    fun createRealm() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val phrase = syncRepository.getRecoveryPhrase().getOrNull() ?: run {
                _uiState.update {
                    it.copy(isLoading = false, error = "No recovery phrase available")
                }
                return@launch
            }
            syncRepository.createRealm(
                recoveryPhrase = phrase.get(),
                deviceName = org.tenshu.core.common.util.getPlatformDeviceName()
            )
            refresh()
        }
    }

    /**
     * Remove a device from the realm.
     *
     * ⚠ A failure must not be invisible: [refresh] re-renders the *unchanged* list, so a
     * discarded refusal looks exactly like a slow revocation. Refusal is a normal outcome — the
     * op bails when the local `Device` entity is absent, which is the normal state of a store
     * that was wiped (tech-debt *"`Device` rows have no regeneration path"*). The result is
     * folded into [SyncDashboardUiState.error] so a surface can say so. (Compose does not
     * render `error` on this screen; the iOS roster does.)
     */
    fun revokeDevice(deviceId: String) = revokeDeviceAndAdmittees(deviceId, emptyList())

    /**
     * The devices [deviceId] admitted, for the removal surface to ask about (§7j.4), read off the
     * roster this screen already holds. See [removalAdmittees].
     */
    fun removalAdmitteesOf(deviceId: String): RemovalAdmittees {
        val state = _uiState.value
        return removalAdmittees(state.devices, deviceId, state.currentDeviceId)
    }

    /**
     * Remove [deviceId] and, in the same action, each of [alsoRemove] — the admittees the user chose
     * to stop trusting (`admission-revocation-names-the-capability.md` §7j.4). Each is a full removal
     * (tombstone, F2′, its own key revoked); a kept admittee is untouched and stays a member that can
     * admit (§7j.10 — removal no longer strips the removed device's lineage). The removed device goes
     * first.
     *
     * ⚠ A separate name rather than a default parameter: Swift does not see Kotlin defaults.
     * ⚠ Every removal is attempted even if one fails; the first failure is what the surface shows.
     */
    fun revokeDeviceAndAdmittees(deviceId: String, alsoRemove: List<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            var firstFailure = NO_FAILURE
            for (id in listOf(deviceId) + alsoRemove.filter { it != deviceId }.distinct()) {
                // The op reports refusal as a Failure and transport loss as a throw; neither may
                // pass silently, so both funnel into the same message.
                val failure = failureMessage(
                    runCatchingCancellable { syncRepository.revokeDevice(id) },
                    REVOKE_FAILED,
                )
                if (firstFailure == NO_FAILURE) firstFailure = failure
            }
            if (firstFailure != NO_FAILURE) {
                _uiState.update { it.copy(error = firstFailure) }
            }
            refresh()
        }
    }

    /**
     * Restore a **peer** device's relay registration ([SyncRepository.readmitDevice]).
     *
     * Offered on every peer row rather than only on rows that look stranded, because this side
     * cannot tell: only the refused device observes its own `401`, and an absent `DeviceLastSeen`
     * is equally what a long-offline peer looks like. The op is idempotent, so offering it
     * speculatively costs a no-op; guessing wrong in the other direction costs a user the only
     * exit their stranded phone has.
     *
     * ⚠⚠ **Announced in both directions.** The effect lands on the *other* device, on its next
     * tick — there is nothing on this screen for the user to read the outcome off, so a silent
     * success is indistinguishable from a button that does nothing. The three refusals
     * (`ReadmitDevice` guards: no master identity here, self, or a row that is gone) arrive as
     * throws carrying a sentence that says what to do instead, which is why [failureMessage]
     * unwraps rather than printing the exception.
     */
    fun readmitDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, notice = null) }
            val failure = failureMessage(
                runCatchingCancellable { syncRepository.readmitDevice(deviceId) },
                READMIT_FAILED,
            )
            _uiState.update {
                if (failure == NO_FAILURE) it.copy(notice = READMIT_DONE) else it.copy(error = failure)
            }
            refresh()
        }
    }

    /**
     * Fold an operation outcome into a user-facing failure message, or [NO_FAILURE].
     *
     * ⚠ A Failure carrying no message must still read as a failure — folding a null message onto
     * the success sentinel would re-create the silence this exists to end.
     *
     * ⚠⚠ A thrown [TenshuException]'s `message` is the typed error's **id**, not its payload — the
     * same trap `SyncScheduler.drainCauseLabel` documents. Reading it directly renders
     * `tenshu/result/error/generic` at the user, throwing away the sentence the op attached for
     * exactly this moment. `ReadmitDevice` reports all three of its guards as throws, so
     * unwrapping is what makes the action explain itself instead of failing cryptically.
     */
    private fun failureMessage(outcome: Result<TResult<*>>, fallback: String): String =
        outcome.fold(
            onSuccess = { result ->
                if (result is TResult.Failure) result.error.messageOrNull() ?: fallback
                else NO_FAILURE
            },
            onFailure = { thrown ->
                (thrown as? TenshuException)?.error?.messageOrNull() ?: thrown.message ?: fallback
            },
        )

    /** Dismiss a surfaced [SyncDashboardUiState.error] (the iOS roster's alert "OK"). */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Dismiss a surfaced [SyncDashboardUiState.notice] once a surface has shown it. */
    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private companion object {
        /** Sentinel for "the call succeeded" — distinct from a Failure carrying no message. */
        const val NO_FAILURE = ""
        const val REVOKE_FAILED = "Could not remove the device"
        const val READMIT_FAILED = "Could not restore access for that device"
        const val READMIT_DONE =
            "Access restored. That device starts syncing again on its next check — it needs no " +
                "action there."
    }
}
