package app.tenshu.control.ui.screens.notifications

import androidx.lifecycle.ViewModel
import app.tenshu.error.ErrorCopyKey
import androidx.lifecycle.viewModelScope
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.notifications.RequestActionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-request UI state for an in-flight or failed action (keyed by [PendingRequest.id]). */
data class RequestActionUiState(
    val inProgress: Boolean = false,
    /** ⚠ A catalogue key; the UI layer renders it. Never a sentence (§5.0). */
    val error: ErrorCopyKey? = null,
)

/**
 * Backs Control's notification surface (`control-central-notifications.md` Phase 2/2b) — the
 * top-bar bell/badge, the notification center, and the pinned Home cards. A lens over
 * [PendingRequestsRepository.requests] (the Phase-1 aggregation core; badge count =
 * `requests.size`) plus the Phase-2b per-request **actions** (accept/decline a grant,
 * confirm/decline a redeemed invite). [actionState] tracks each request's in-flight /
 * error state by id; a resolved request drops out of [requests] (its underlying synced
 * state changed) and its action entry is cleared.
 */
class NotificationsViewModel(
    private val repository: PendingRequestsRepository,
) : ViewModel() {
    /** Every inbound actionable request, newest first — incl. dismissed ones (the center lists them). */
    val requests: StateFlow<List<PendingRequest>> = repository.requests

    /**
     * Ids dismissed (marked seen) on any paired device (Phase 4). The center uses this
     * to hide the per-card dismiss affordance on already-seen requests.
     */
    val seenIds: StateFlow<Set<String>> = repository.seenIds

    /**
     * Requests not yet dismissed — drives the bell badge count and the pinned Home
     * slot. A dismissed request leaves this (clearing the badge) but stays in [requests].
     */
    val unread: StateFlow<List<PendingRequest>> = repository.unread

    private val _actionState = MutableStateFlow<Map<String, RequestActionUiState>>(emptyMap())

    /** In-flight / error state per request id; absent id means idle. */
    val actionState: StateFlow<Map<String, RequestActionUiState>> = _actionState

    fun acceptGrant(request: PendingRequest.MembershipGrant) =
        act(request.id) { repository.acceptGrant(request) }

    fun declineGrant(request: PendingRequest.MembershipGrant) =
        act(request.id) { repository.declineGrant(request) }

    fun confirmInvite(request: PendingRequest.ContactInviteRedeemed, petname: String, sasConfirmed: Boolean) =
        act(request.id) { repository.confirmInvite(request, petname, sasConfirmed) }

    /**
     * Accept the request with [id] for a surface that holds a flat row, not the leaf (the iOS
     * center: the sealed type never crosses the bridge). An unknown id is a no-op — the request
     * may have resolved between render and tap.
     *
     * ⚠⚠ [shownFingerprint] is the raw claimed fingerprint the card **rendered**
     * ([app.tenshu.control.notifications.PendingRequestFacts.shownFingerprint]), and it is what an
     * invite's confirmation submits — the claim re-resolved here by id is NOT. The claim is a
     * synced last-writer-wins value: a second inviter device's write can replace it by merge
     * after the card rendered and before the surface re-renders, and submitting the re-read claim
     * would then send the NEW key with a tick made against the OLD code (threat model T-CI-10).
     * The proxy refuses any response that is not [shownFingerprint].
     */
    fun acceptById(id: String, shownFingerprint: String, petname: String, sasConfirmed: Boolean) {
        when (val request = requests.value.firstOrNull { it.id == id }) {
            is PendingRequest.MembershipGrant -> acceptGrant(request)
            is PendingRequest.ContactInviteRedeemed ->
                confirmInvite(request.copy(claimedFingerprint = shownFingerprint), petname, sasConfirmed)
            null -> Unit
        }
    }

    fun declineInvite(request: PendingRequest.ContactInviteRedeemed) =
        act(request.id) { repository.declineInvite(request) }

    /** Mark a request seen (Phase 4) — clears it from [unread] on every device; it stays in [requests]. */
    fun dismiss(request: PendingRequest) =
        act(request.id) { repository.dismiss(request) }

    /**
     * Re-poll the poll-only sources (membership grants are a 30 s sweep, not reactive).
     *
     * Forwards [PendingRequestsRepository.refresh], which had no caller anywhere until the
     * iOS center's pull-to-refresh: Compose Control shows this screen inside a scaffold that
     * never offered the gesture, so the sweep was the only thing that moved a grant.
     */
    fun refresh() = repository.refresh()

    /** Clear a surfaced error so the card returns to its actionable state. */
    fun dismissError(id: String) {
        _actionState.update { it - id }
    }

    private fun act(id: String, block: suspend () -> RequestActionResult) {
        if (_actionState.value[id]?.inProgress == true) return
        _actionState.update { it + (id to RequestActionUiState(inProgress = true)) }
        viewModelScope.launch {
            when (val result = block()) {
                // Resolved: the request leaves [requests] once its synced state converges
                // (grant poll re-runs / invite row flips); drop the transient action entry.
                is RequestActionResult.Success -> _actionState.update { it - id }
                is RequestActionResult.Failure ->
                    _actionState.update { it + (id to RequestActionUiState(error = result.copyKey)) }
            }
        }
    }
}
