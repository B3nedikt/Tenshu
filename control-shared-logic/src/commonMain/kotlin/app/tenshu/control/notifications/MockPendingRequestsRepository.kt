package app.tenshu.control.notifications

import app.tenshu.error.ErrorCopyKey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [PendingRequestsRepository] for screenshots, Compose previews and screen tests —
 * the sibling of [app.tenshu.control.data.MockEventRepository] and
 * [app.tenshu.control.data.MockShareManager], and the fallback an iOS shell binds before
 * `installRealRuntime()` swaps in the client-backed one.
 *
 * Actions mutate the in-memory set the way the real sources converge: accepting or declining
 * **resolves** a request (it leaves the feed, as the grant poll re-running or the invite row
 * flipping would do), while dismissing only marks it seen — so a preview exercises the
 * distinction the whole Phase-4 design turns on rather than collapsing the two.
 *
 * ⚠ [failing] makes every action fail with a fixed message. That path is worth being able to
 * render: the error state is a card a user can get stuck on, and nothing else can produce it
 * on demand.
 *
 * ⚠⚠ **[unread] is recomputed synchronously, not `combine(...).stateIn(scope)`.** The real
 * repository derives it on a process-lifetime scope, which is correct there — a badge that
 * converges a beat later is invisible. Here it is not: a `stateIn` on a background dispatcher
 * makes `unread` read *stale immediately after a mutation*, so every test touching dismissal
 * has to sleep or poll, and the first such test written against this class duly failed. One
 * `recompute()` on the mutation path keeps it deterministic, and keeps a single derivation
 * (`requests - seenIds`) rather than a second copy that could drift from it.
 */
class MockPendingRequestsRepository(
    initial: List<PendingRequest> = SAMPLE,
    private val failing: Boolean = false,
) : PendingRequestsRepository {

    private val _requests = MutableStateFlow(initial.sortedByDescending { it.timestampMillis })
    private val _seenIds = MutableStateFlow<Set<String>>(emptySet())
    private val _unread = MutableStateFlow(initial.sortedByDescending { it.timestampMillis })

    override val requests: StateFlow<List<PendingRequest>> = _requests.asStateFlow()

    override val seenIds: StateFlow<Set<String>> = _seenIds.asStateFlow()

    override val unread: StateFlow<List<PendingRequest>> = _unread.asStateFlow()

    /** The single derivation, applied after every mutation — never a second source of truth. */
    private fun recompute() {
        _unread.value = _requests.value.filterNot { it.id in _seenIds.value }
    }

    override fun refresh() = Unit

    override suspend fun dismiss(request: PendingRequest): RequestActionResult = act {
        _seenIds.value = _seenIds.value + request.id
    }

    override suspend fun acceptGrant(request: PendingRequest.MembershipGrant): RequestActionResult =
        act { resolve(request.id) }

    override suspend fun declineGrant(request: PendingRequest.MembershipGrant): RequestActionResult =
        act { resolve(request.id) }

    override suspend fun confirmInvite(
        request: PendingRequest.ContactInviteRedeemed,
        petname: String,
        sasConfirmed: Boolean,
    ): RequestActionResult = act { resolve(request.id) }

    override suspend fun declineInvite(request: PendingRequest.ContactInviteRedeemed): RequestActionResult =
        act { resolve(request.id) }

    /** Resolved, not dismissed: the request leaves the feed and takes its seen marker with it. */
    private fun resolve(id: String) {
        _requests.value = _requests.value.filterNot { it.id == id }
        _seenIds.value = _seenIds.value - id
    }

    private inline fun act(block: () -> Unit): RequestActionResult =
        if (failing) {
            RequestActionResult.Failure(ErrorCopyKey.UNEXPECTED)
        } else {
            block()
            recompute()
            RequestActionResult.Success
        }

    companion object {

        /** One of each variant, newest first — the two the center must render differently. */
        val SAMPLE: List<PendingRequest> = listOf(
            PendingRequest.ContactInviteRedeemed(
                inviteId = "invite-sample",
                claimedName = "Jo",
                claimedFingerprint = "9f2a7c1e",
                claimedSas = "482 913",
                timestampMillis = 1_717_000_000_000L,
            ),
            PendingRequest.MembershipGrant(
                grantorFingerprint = "1b4d6e0a",
                timestampMillis = 1_716_800_000_000L,
            ),
        )
    }
}
