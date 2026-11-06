package app.tenshu.drive.ui.shared

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.SharedFolderModel
import app.tenshu.drive.ui.FakeContactsRepository
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.FakeMembershipRepository
import app.tenshu.drive.ui.screens.shared.SharedScreenViewModel
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.contact.ContactInvite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **deferred grant offer** — the decoupled replacement for the offer that used to be
 * chained onto the completion prompt (`contacts-app.md` §9.1 alternative).
 *
 * The property under test throughout: the offer is **derived, not remembered**. It exists
 * exactly while a `COMPLETED` `SHARE_FOLDER` invite names a grantable contact and an owned
 * realm they are not yet a member of — which is why granting makes it vanish on every
 * device with no bookkeeping, and why dismissing it (the one non-derivable act) does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedScreenGrantOfferTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val bobsKey = "abcdef0123456789"

    private fun completedFolderInvite(
        id: String = "invite-1",
        label: String = "Holiday",
        resultFingerprint: String = bobsKey,
        status: String = ContactInvite.Status.COMPLETED,
        intentKind: String = ContactInvite.IntentKind.SHARE_FOLDER,
    ) = ContactInviteModel(
        inviteId = id,
        status = status,
        intentKind = intentKind,
        intentLabel = label,
        createdAtMillis = 0L,
        expiresAtMillis = 0L,
        claimedName = "Bob",
        claimedFingerprint = bobsKey,
        claimedSas = "",
        resultFingerprint = resultFingerprint,
    )

    private fun contacts(grantable: Boolean = true) = FakeContactsRepository(
        connectedContactsResult = RepositoryResult.Success(
            listOf(ContactModel("p1", "Bobby", fingerprint = bobsKey, grantable = grantable))
        )
    )

    private fun files() = FakeFilesRepository(
        sharedFoldersResult = listOf(SharedFolderModel("r1", "Holiday", "/"))
    )

    @Test
    fun aCompletedFolderInvite_offersTheGrantItNeverMade() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts()
        val vm = vms.own(SharedScreenViewModel(files(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()

        contacts.invites.value = listOf(completedFolderInvite())
        advanceUntilIdle()

        val offer = vm.uiState.value.grantOffers.single()
        assertEquals("r1", offer.realmId)
        assertEquals("p1", offer.personId)
        assertEquals("Holiday", offer.label)
        assertEquals("Bobby", offer.name)
    }

    @Test
    fun grantingClearsTheOfferStructurally() = vmTest { vms ->
        // The card disappears because the contact became a member, not because anything
        // was remembered — so it stays gone on a fresh process and on another device.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts()
        val membership = FakeMembershipRepository()
        val vm = vms.own(SharedScreenViewModel(files(), contacts, membership))
        advanceUntilIdle()
        contacts.invites.value = listOf(completedFolderInvite())
        advanceUntilIdle()

        // The grant lands, and the roster now names Bob.
        membership.membersResult = RepositoryResult.Success(
            listOf(MemberModel("m1", "Bobby", contactFingerprint = bobsKey, role = "member"))
        )
        vm.onGrantOfferAccepted("invite-1")
        advanceUntilIdle()

        assertEquals(
            listOf(FakeMembershipRepository.GrantCall("r1", "p1", "Bobby")),
            membership.grantCalls,
        )
        assertTrue(vm.uiState.value.grantOffers.isEmpty())
    }

    @Test
    fun anExistingMemberIsNeverOffered() = vmTest { vms ->
        // The same structural filter, arrived at from the other direction: a contact
        // granted on another device must not be offered again here.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts()
        val membership = FakeMembershipRepository(
            membersResult = RepositoryResult.Success(
                listOf(MemberModel("m1", "Bobby", contactFingerprint = bobsKey, role = "member"))
            )
        )
        val vm = vms.own(SharedScreenViewModel(files(), contacts, membership))
        advanceUntilIdle()

        contacts.invites.value = listOf(completedFolderInvite())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.grantOffers.isEmpty())
    }

    @Test
    fun aFailedGrantKeepsTheOfferUp() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts()
        val membership = FakeMembershipRepository(grantResult = FakeMembershipRepository.error())
        val vm = vms.own(SharedScreenViewModel(files(), contacts, membership))
        advanceUntilIdle()
        contacts.invites.value = listOf(completedFolderInvite())
        advanceUntilIdle()

        vm.onGrantOfferAccepted("invite-1")
        advanceUntilIdle()

        assertTrue(
            vm.uiState.value.grantOffers.isNotEmpty(),
            "a failed grant is exactly when the offer should still be there to retry",
        )
    }

    @Test
    fun anUnknownRosterKeepsTheOfferRatherThanHidingIt() = vmTest { vms ->
        // Failing open is the deliberate choice: offering a grant twice is harmless (the op
        // is addressed by member), whereas silently dropping the offer strands the share.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts()
        val membership = FakeMembershipRepository(membersResult = FakeMembershipRepository.error())
        val vm = vms.own(SharedScreenViewModel(files(), contacts, membership))
        advanceUntilIdle()

        contacts.invites.value = listOf(completedFolderInvite())
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.grantOffers.size)
    }

    @Test
    fun dismissingHidesTheOffer_butOnlyForThisProcess() = vmTest { vms ->
        // ⚠ The honest cost of decoupling. The chained offer needed no dismissal state at
        // all — it was modal, so declining ended it. A derived card has to be told to go
        // away, and "go away" is not derivable from any synced row. This test pins the
        // current, deliberately shallow behaviour so the gap is visible rather than assumed
        // solved: a second view model over the same data offers it again.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts()
        val vm = vms.own(SharedScreenViewModel(files(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()
        contacts.invites.value = listOf(completedFolderInvite())
        advanceUntilIdle()

        vm.onGrantOfferDismissed("invite-1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.grantOffers.isEmpty())

        val restarted = vms.own(SharedScreenViewModel(files(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()
        assertEquals(
            1,
            restarted.uiState.value.grantOffers.size,
            "dismissal is in-memory only — a restart brings the offer back",
        )
    }

    @Test
    fun aNonGrantableContactIsNotOffered() = vmTest { vms ->
        // A pre-GR-3 contact carries no grant seed, so there is no address to grant to.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts(grantable = false)
        val vm = vms.own(SharedScreenViewModel(files(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()

        contacts.invites.value = listOf(completedFolderInvite())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.grantOffers.isEmpty())
    }

    @Test
    fun bareAndUnfinishedInvitesAreNotOffers() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = contacts()
        val vm = vms.own(SharedScreenViewModel(files(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()

        contacts.invites.value = listOf(
            // A bare invite has no folder to grant.
            completedFolderInvite(id = "bare", intentKind = "", label = ""),
            // A redeemed one is not finished — it is the completion prompt's business.
            completedFolderInvite(id = "redeemed", status = ContactInvite.Status.REDEEMED),
            // A completed one whose label names no owned realm.
            completedFolderInvite(id = "elsewhere", label = "Not a folder of mine"),
        )
        advanceUntilIdle()

        assertTrue(vm.uiState.value.grantOffers.isEmpty())
    }
}
