package org.tenshu.sdk.ios

import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.GrantState
import app.tenshu.control.data.RealmDataResetOutcome
import app.tenshu.control.data.UnwrapResult
import app.tenshu.control.notifications.MockPendingRequestsRepository
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.RequestActionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the one invariant these classes exist for: **a not-connected repository never answers
 * successfully.**
 *
 * The failure mode being guarded is not a crash — it is a confident wrong answer. Before this,
 * every [ControlSurface] seam defaulted to a Mock/Fake, so a path that forgot
 * [ControlSurface.installRealRuntime] got fabricated events, fabricated pending requests, a
 * well-formed pairing link that pairs nothing, and a `createRealm` that reports success having
 * created nothing. Each test below would pass just as happily against the *fakes* if the
 * assertions were about shape rather than about **success vs failure**, which is why they all
 * assert the latter.
 *
 * ⚠ These tests deliberately construct the classes directly rather than reading
 * [ControlSurface]'s defaults: the surface is a process-wide `object`, and a test that asserted on
 * its mutable installed state would pass or fail depending on what ran before it. The defaults
 * themselves are held by the declaration-site comment and by the Swift side calling
 * [ControlSurface.installFakes] explicitly for the screenshot routes.
 */
class NotConnectedControlRepositoriesTest {

    // ⚠⚠ The sharpest one. The fake answers every one of these with `ResultSuccessDto` — including
    // the writes, and including `unlockLocal` for *any* passphrase.
    @Test
    fun syncRepository_fails_every_read_and_write() = runTest {
        val repo = NotConnectedControlSyncRepository()

        assertIs<TResult.Failure<*>>(repo.getSyncStatus(), "getSyncStatus")
        assertIs<TResult.Failure<*>>(repo.getRecoveryPhrase(), "getRecoveryPhrase")
        assertIs<TResult.Failure<*>>(repo.getPendingPairingRequests(), "getPendingPairingRequests")
        assertIs<TResult.Failure<*>>(repo.getUnlockStatus(), "getUnlockStatus")

        // The writes. A success here is the failure this whole file is about.
        assertIs<TResult.Failure<*>>(repo.createRealm("phrase", "device"), "createRealm")
        assertIs<TResult.Failure<*>>(repo.recoveryClaim("phrase", "device"), "recoveryClaim")
        assertIs<TResult.Failure<*>>(repo.revokeDevice("d"), "revokeDevice")
        assertIs<TResult.Failure<*>>(repo.approvePairingRequest("d"), "approvePairingRequest")
        assertIs<TResult.Failure<*>>(repo.reregisterDevice(), "reregisterDevice")

        // ⚠ The pairing surface: the fake hands back a real `tenshu://join` link here.
        assertIs<TResult.Failure<*>>(repo.startPairing(), "startPairing")
        assertIs<TResult.Failure<*>>(repo.stopPairing(), "stopPairing")

        // ⚠⚠ Unlock. The fake returns `true` for any passphrase.
        assertIs<TResult.Failure<*>>(repo.unlockLocal("anything"), "unlockLocal")
        assertIs<TResult.Failure<*>>(repo.setUpLocalUnlock("anything"), "setUpLocalUnlock")
        assertIs<TResult.Failure<*>>(repo.relaxToHeadless(), "relaxToHeadless")
    }

    @Test
    fun syncRepository_awaitPairing_does_not_complete_the_handshake() = runTest {
        // The fake's handle resolves Success(true) after a delay, driving the joiner wizard all the
        // way to its Success step with no second device in the room.
        val handle = NotConnectedControlSyncRepository().awaitPairing("secret", ByteArray(32))
        assertIs<TResult.Failure<*>>(handle.await(), "awaitPairing().await()")
    }

    @Test
    fun syncRepository_deviceId_is_not_a_plausible_device() = runTest {
        // No TResult to fail in, so the guard is that it cannot be mistaken for a real id in a log.
        assertEquals("not-connected", NotConnectedControlSyncRepository().getDeviceId())
    }

    @Test
    fun pendingRequests_are_empty_and_every_action_fails() = runTest {
        val repo = NotConnectedPendingRequestsRepository()

        assertTrue(repo.requests.value.isEmpty(), "requests")
        assertTrue(repo.unread.value.isEmpty(), "unread")
        assertTrue(repo.seenIds.value.isEmpty(), "seenIds")

        // Actions on a request that cannot exist still have to fail rather than report resolution.
        val sample = MockPendingRequestsRepository().requests.value.first()
        assertIs<RequestActionResult.Failure>(repo.dismiss(sample), "dismiss")

        when (sample) {
            is PendingRequest.MembershipGrant -> {
                assertIs<RequestActionResult.Failure>(repo.acceptGrant(sample), "acceptGrant")
                assertIs<RequestActionResult.Failure>(repo.declineGrant(sample), "declineGrant")
            }

            is PendingRequest.ContactInviteRedeemed -> {
                assertIs<RequestActionResult.Failure>(repo.confirmInvite(sample, "name", true), "confirmInvite")
                assertIs<RequestActionResult.Failure>(repo.declineInvite(sample), "declineInvite")
            }

            else -> Unit
        }
    }

    @Test
    fun shareManager_reads_empty_and_writes_throw() = runTest {
        val manager = NotConnectedShareManager()

        assertTrue(manager.shares().first().isEmpty(), "shares")
        assertTrue(manager.sharedFolders().first().isEmpty(), "sharedFolders")
        assertTrue(manager.members("realm").first().isEmpty(), "members")

        assertFailsWith<NotConnectedToControl> { manager.revoke("share") }
        assertFailsWith<NotConnectedToControl> { manager.removeMember("realm", "member") }
    }

    @Test
    fun appRegistry_reads_empty_and_grant_writes_throw() = runTest {
        val registry = NotConnectedAppRegistry()

        assertTrue(registry.installedApps().first().isEmpty(), "installedApps")
        assertNull(registry.appDetail("app").first(), "appDetail")

        // ⚠ A swallowed grant write would tell the user a permission changed while nothing recorded it.
        assertFailsWith<NotConnectedToControl> {
            registry.setGrant("app", "service", "operation", GrantState.Granted)
        }
        assertFailsWith<NotConnectedToControl> { registry.revokeAllPermissions("app") }
    }

    @Test
    fun realmData_lists_nothing_and_reset_reports_failure() = runTest {
        val client = NotConnectedRealmDataClient()

        assertTrue(client.realmDataTypeIds().isEmpty(), "realmDataTypeIds")
        assertEquals(false, client.isResettable("tenshu/file"), "isResettable")

        // ⚠ `Failed`, never `Deleted(0)` — the latter claims a completed reset of zero rows.
        assertEquals(RealmDataResetOutcome.Failed, client.deleteAll("tenshu/file"))
    }

    @Test
    fun biometrics_deny_and_do_not_claim_enrolment() = runTest {
        val delegate = NotConnectedBiometricUnlockDelegate()

        assertEquals(false, delegate.isAvailable(), "isAvailable")
        assertEquals(false, delegate.hasEnrolled(), "hasEnrolled")

        // ⚠ The fake returns EnrollResult.Success here, claiming an enrolment that never happened.
        assertEquals(
            EnrollResult.NotAvailable,
            delegate.enrollWrapKey(ByteArray(32), BiometricPromptHost(), promptCopy),
        )
        assertEquals(UnwrapResult.NotAvailable, delegate.unwrapWrapKey(BiometricPromptHost(), promptCopy))
    }

    // iOS's BiometricPromptHost is an inert marker (the OS presents its own sheet).
    private val promptCopy = BiometricPromptCopy(title = "t", negativeButton = "cancel")
}
