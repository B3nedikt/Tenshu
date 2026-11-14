package org.tenshu.sdk.ios

import app.tenshu.drive.data.InboundSharedRealm
import app.tenshu.drive.data.PendingGrantModel
import app.tenshu.drive.data.ShareModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Reactive coverage of the Shared destination VM over the three fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedWithMeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun share(id: String) =
        ShareModel(shareId = id, filePath = "/$id.txt", fileName = "$id.txt", createdAtMillis = 0, expiresAtMillis = null, maxOpens = null, revoked = false)

    @Test
    fun my_shares_show_and_revoke_drops_the_row() = vmTest(dispatcher) { vms ->
        val files = FakeFilesRepository(initialShares = listOf(share("s1")))
        val vm = vms.own(DriveShareSurface.sharedWithMe(files, FakeContactsRepository(), FakeMembershipRepository()))
        advanceUntilIdle()
        assertEquals(listOf("s1"), vm.uiState.value.myShares.map { it.shareId })

        vm.onRevokeShareClicked("s1")
        advanceUntilIdle()
        assertEquals(listOf("s1"), files.revokedShares)
        assertTrue(vm.uiState.value.myShares.isEmpty())
    }

    @Test
    fun shared_with_me_lists_inbound_realms() = vmTest(dispatcher) { vms ->
        val files = FakeFilesRepository(inboundShared = listOf(InboundSharedRealm(sharedRealmId = "r1", name = "Photos")))
        val vm = vms.own(DriveShareSurface.sharedWithMe(files, FakeContactsRepository(), FakeMembershipRepository()))
        advanceUntilIdle()
        assertEquals(listOf("r1"), vm.uiState.value.sharedWithMe.map { it.sharedRealmId })
    }

    @Test
    fun a_pending_grant_can_be_accepted() = vmTest(dispatcher) { vms ->
        val membership = FakeMembershipRepository(pendingGrants = listOf(PendingGrantModel(grantorFingerprint = "fp1", discoveredAt = 0)))
        val vm = vms.own(DriveShareSurface.sharedWithMe(FakeFilesRepository(), FakeContactsRepository(), membership))
        advanceUntilIdle()
        assertEquals(listOf("fp1"), vm.uiState.value.pendingGrants.map { it.fingerprint })

        vm.onAcceptPendingGrant("fp1")
        advanceUntilIdle()
        assertEquals(listOf("fp1"), membership.accepted)
        assertTrue(vm.uiState.value.pendingGrants.isEmpty())
    }

    @Test
    fun a_pending_grant_can_be_declined() = vmTest(dispatcher) { vms ->
        val membership = FakeMembershipRepository(pendingGrants = listOf(PendingGrantModel(grantorFingerprint = "fp1", discoveredAt = 0)))
        val vm = vms.own(DriveShareSurface.sharedWithMe(FakeFilesRepository(), FakeContactsRepository(), membership))
        advanceUntilIdle()

        vm.onDeclinePendingGrant("fp1")
        advanceUntilIdle()
        assertEquals(listOf("fp1"), membership.declined)
        assertTrue(vm.uiState.value.pendingGrants.isEmpty())
    }
}
