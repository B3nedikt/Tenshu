package org.tenshu.sdk.ios

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.data.SharedFolderModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tenshu.contact.ContactInvite
import org.tenshu.identity.Member
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reactive coverage of the Manage Access VM over the contacts + membership fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageAccessTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invites_for_the_folder_show_and_revoke() = runTest(dispatcher) {
        val invite = ContactInviteModel(
            inviteId = "inv-1",
            status = ContactInvite.Status.PENDING,
            intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
            intentLabel = "Shared",
            createdAtMillis = 0,
            expiresAtMillis = 0,
            claimedName = "",
            claimedFingerprint = "",
            claimedSas = "",
        )
        val contacts = FakeContactsRepository(invites = listOf(invite))
        val vm = DriveShareSurface.manageAccess(FakeFilesRepository(), contacts, FakeMembershipRepository(), "/Shared")
        advanceUntilIdle()

        assertEquals(listOf("inv-1"), vm.uiState.value.invites.map { it.inviteId })

        vm.onRevokeInviteClicked("inv-1")
        advanceUntilIdle()
        assertEquals(listOf("inv-1"), contacts.revoked)
    }

    @Test
    fun a_shared_realm_folder_loads_members_and_grants_and_removes() = runTest(dispatcher) {
        val files = FakeFilesRepository(
            sharedFolders = listOf(SharedFolderModel(sharedRealmId = "realm-1", name = "Shared", parentPath = "/")),
        )
        val membership = FakeMembershipRepository(
            members = listOf(MemberModel(memberId = "m-1", displayName = "Bob", contactFingerprint = "fp", role = Member.Role.MEMBER)),
        )
        val contacts = FakeContactsRepository(
            contacts = listOf(ContactModel(personId = "p-1", displayName = "Carol", fingerprint = "fp2", grantable = true)),
        )
        val vm = DriveShareSurface.manageAccess(files, contacts, membership, "/Shared")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.backsSharedRealm)
        assertEquals(listOf("m-1"), vm.uiState.value.members.map { it.memberId })
        assertEquals(listOf("p-1"), vm.uiState.value.grantableContacts.map { it.personId })

        vm.onGrantToContact("p-1")
        advanceUntilIdle()
        assertEquals("p-1", membership.granted.single().personId)

        vm.onRemoveMember("m-1")
        advanceUntilIdle()
        assertEquals(listOf("realm-1" to "m-1"), membership.removed)
    }
}
