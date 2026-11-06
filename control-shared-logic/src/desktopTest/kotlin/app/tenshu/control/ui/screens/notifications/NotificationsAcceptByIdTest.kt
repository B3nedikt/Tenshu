package app.tenshu.control.ui.screens.notifications

import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.notifications.RequestActionResult
import app.tenshu.control.notifications.pendingRequestFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The id-keyed accept the iOS center drives (`ControlSurface.acceptRequest`). The SwiftUI card
 * holds a flat row; the view model re-resolves the leaf by id at tap time. Threat model T-CI-10:
 * the invite's claim is a synced last-writer-wins value that a second inviter device can flip by
 * merge **between render and tap** — before the Swift `for await` collector re-renders — so the
 * fingerprint submitted must be the one the tapped card rendered, never the re-resolved one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsAcceptByIdTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val shown = PendingRequest.ContactInviteRedeemed(
        inviteId = "invite-1",
        claimedName = "Bob",
        claimedFingerprint = "b0b0b0b0",
        claimedSas = "111 111",
        timestampMillis = 1_717_000_000_000L,
    )

    @Test
    fun theClaimFlipsBetweenRenderAndTap_theRenderedFingerprintIsSubmitted() = vmTest(dispatcher) { vms ->
        val repo = RecordingRepository(shown)
        val vm = vms.own(NotificationsViewModel(repo))

        // What the card rendered — the row Swift will hand back on tap.
        val rendered = pendingRequestFacts(vm.requests.value.single())

        // Mallory's claim lands by merge; Swift has not re-rendered yet.
        repo.requestsFlow.value = listOf(
            shown.copy(claimedFingerprint = "ma11ma11", claimedSas = "999 999"),
        )

        vm.acceptById(rendered.id, rendered.shownFingerprint, petname = "Bob", sasConfirmed = true)
        advanceUntilIdle()

        val confirmed = repo.confirmed.single()
        assertEquals(
            "b0b0b0b0", confirmed.claimedFingerprint,
            "the key submitted must be the key the card showed, not the claim re-read at tap time",
        )
        assertTrue(repo.confirmedSas.single())
    }

    @Test
    fun anUnknownId_isANoOp() = vmTest(dispatcher) { vms ->
        val repo = RecordingRepository(shown)
        val vm = vms.own(NotificationsViewModel(repo))

        vm.acceptById("invite:gone", "b0b0b0b0", petname = "Bob", sasConfirmed = false)
        advanceUntilIdle()

        assertTrue(repo.confirmed.isEmpty())
    }

    private class RecordingRepository(initial: PendingRequest) : PendingRequestsRepository {
        val requestsFlow = MutableStateFlow(listOf(initial))
        val confirmed = mutableListOf<PendingRequest.ContactInviteRedeemed>()
        val confirmedSas = mutableListOf<Boolean>()

        override val requests: StateFlow<List<PendingRequest>> = requestsFlow
        override val seenIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val unread: StateFlow<List<PendingRequest>> = requestsFlow
        override fun refresh() = Unit
        override suspend fun dismiss(request: PendingRequest) = RequestActionResult.Success
        override suspend fun acceptGrant(request: PendingRequest.MembershipGrant) = RequestActionResult.Success
        override suspend fun declineGrant(request: PendingRequest.MembershipGrant) = RequestActionResult.Success
        override suspend fun confirmInvite(
            request: PendingRequest.ContactInviteRedeemed,
            petname: String,
            sasConfirmed: Boolean,
        ): RequestActionResult {
            confirmed += request
            confirmedSas += sasConfirmed
            return RequestActionResult.Success
        }
        override suspend fun declineInvite(request: PendingRequest.ContactInviteRedeemed) = RequestActionResult.Success
    }
}
