package org.tenshu.sdk.ios

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.tenshu.control.data.AppDetail
import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.Event
import app.tenshu.control.data.EventRepository
import app.tenshu.control.data.GrantState
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.OpenHandlerChoice
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.RealmDataClient
import app.tenshu.control.data.RealmDataResetOutcome
import app.tenshu.control.data.ShareManager
import app.tenshu.control.data.ShareSummary
import app.tenshu.control.data.SharedFolderSummary
import app.tenshu.control.data.SharedRealmMember
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.UninstallScope
import app.tenshu.control.data.UnwrapResult
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.notifications.RequestActionResult
import app.tenshu.error.ErrorCopyKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.GenericErrorDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.UnlockStatus

/**
 * The Control-on-iOS **"runtime not installed"** repositories — the honest face of a
 * [ControlSurface] whose [ControlSurface.installRealRuntime] has not run.
 *
 * ### Why these exist
 *
 * Every `installed*` seam on [ControlSurface] used to *default* to a Mock/Fake, and what kept
 * fabricated data off a user's screen was one call: `ControlIosApp.init` invokes
 * `installRealRuntime()` on every non-screenshot boot. That is an **ordering guarantee, not a
 * structural one** — any new entry point (an extension, a widget, a test harness, a future scene)
 * that forgets the call inherits invented data silently, and the screens cannot tell.
 *
 * Fit and Timeline already learned this: `NotConnectedFitRepository` and
 * [NotConnectedTimelineRepository] replaced exactly this shape after
 * `ios-fit-timeline-maps-parity.md` §2.2 (*"convincing invented numbers are worse than empty"*).
 * Control was the last iOS surface still on the old default. These classes close it: the safe
 * state is now what you get by *doing nothing*, and the fakes became opt-in — `installFakes()`
 * is called explicitly by the DEBUG screenshot routes that want them.
 *
 * ### What "not connected" means here, and why it is not "empty"
 *
 * ⚠⚠ **A failure, not an empty success.** This is the whole point, and it is the same distinction
 * `NotConnectedFitRepository` documents at length. With no runtime these reads did not *answer* —
 * they could not be made. Returning empty successes would make the screens draw confident
 * conclusions about the user's realm: *"No recent events"*, *"You are all caught up"*, *"Nothing
 * shared yet"*, an empty Apps list. Every one of those is a **fabricated claim about the user's
 * data**, which is the same anti-pattern as fabricated rows — just quieter, because emptiness
 * looks like a legitimate state rather than invented content.
 *
 * ⚠ The writes are the sharper half. [FakeControlSyncRepository] answers `createRealm`,
 * `revokeDevice` and `approvePairingRequest` with `ResultSuccessDto(null)` — a *success* — and
 * hands out a well-formed `tenshu://join` pairing link. Under the old defaults, an entry point
 * missing the install call would show a pairing code that pairs nothing and report a realm
 * created that was never created. Here every write fails.
 */

// ---------------------------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------------------------

/**
 * ⚠ The paging analogue of `ListLoadState.Failed`: a source that **errors** rather than one that
 * returns zero rows. `PagingData.empty()` would render Home's *"No recent events — sync activity
 * from your paired devices will show up here"*, telling a user with a busy realm that nothing has
 * happened. `LoadResult.Error` surfaces through `LazyPagingItems.loadState` as a real failure.
 */
class NotConnectedEventRepository : EventRepository {
    override fun getEvents(): Flow<PagingData<Event>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false, prefetchDistance = 5),
        pagingSourceFactory = { NotConnectedPagingSource() },
    ).flow

    private class NotConnectedPagingSource : PagingSource<Int, Event>() {
        override fun getRefreshKey(state: PagingState<Int, Event>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Event> =
            LoadResult.Error(NotConnectedToControl())
    }
}

// ---------------------------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------------------------

/**
 * ⚠ An empty center reads *"You are all caught up"* — a claim that there is nothing awaiting the
 * user. With no runtime we do not know that. The lists are empty because there is nothing to show,
 * and every action fails rather than pretending to resolve a request.
 */
class NotConnectedPendingRequestsRepository : PendingRequestsRepository {
    override val requests: StateFlow<List<PendingRequest>> = MutableStateFlow(emptyList())
    override val seenIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val unread: StateFlow<List<PendingRequest>> = MutableStateFlow(emptyList())

    override fun refresh() = Unit

    override suspend fun dismiss(request: PendingRequest): RequestActionResult = NOT_CONNECTED
    override suspend fun acceptGrant(request: PendingRequest.MembershipGrant): RequestActionResult = NOT_CONNECTED
    override suspend fun declineGrant(request: PendingRequest.MembershipGrant): RequestActionResult = NOT_CONNECTED

    override suspend fun confirmInvite(
        request: PendingRequest.ContactInviteRedeemed,
        petname: String,
        sasConfirmed: Boolean,
    ): RequestActionResult = NOT_CONNECTED

    override suspend fun declineInvite(request: PendingRequest.ContactInviteRedeemed): RequestActionResult =
        NOT_CONNECTED

    private companion object {
        val NOT_CONNECTED = RequestActionResult.Failure(ErrorCopyKey.CONTROL_NOT_RUNNING)
    }
}

// ---------------------------------------------------------------------------------------------
// Shares
// ---------------------------------------------------------------------------------------------

/** ⚠ Empty here would read *"Nothing shared yet"* — a statement about the realm we cannot make. */
class NotConnectedShareManager : ShareManager {
    override fun shares(): Flow<List<ShareSummary>> = flowOf(emptyList())
    override fun sharedFolders(): Flow<List<SharedFolderSummary>> = flowOf(emptyList())
    override fun members(realmId: String): Flow<List<SharedRealmMember>> = flowOf(emptyList())

    override suspend fun revoke(shareId: String): Unit = throw NotConnectedToControl()
    override suspend fun removeMember(realmId: String, memberId: String): Unit = throw NotConnectedToControl()
}

// ---------------------------------------------------------------------------------------------
// Apps
// ---------------------------------------------------------------------------------------------

/**
 * ⚠ The grant writes are the reason this one matters beyond cosmetics: a silently-swallowed
 * [setGrant] would tell the user a permission had been changed while nothing recorded it.
 */
class NotConnectedAppRegistry : AppRegistry {
    override fun installedApps(): Flow<List<InstalledApp>> = flowOf(emptyList())
    override fun appDetail(appId: String): Flow<AppDetail?> = flowOf(null)

    override suspend fun setGrant(
        appId: String,
        serviceId: String,
        operationId: String,
        grant: GrantState,
    ): Unit = throw NotConnectedToControl()

    /**
     * ⚠ Empty rather than a throw, unlike the writes: with no Control there are no installed apps,
     * so there is genuinely nothing to choose between — and the picker's own contract is that fewer
     * than two candidates renders no section (`control-cross-app-open.md` TT-4b). This is the same
     * answer a connected Control gives a realm with one place app.
     */
    override fun openHandlerChoices(): Flow<List<OpenHandlerChoice>> = flowOf(emptyList())

    /** ⚠⚠ A throw, like the grant writes: a swallowed choice would tell the user their default had
     *  changed while nothing recorded it. */
    override suspend fun setPrimaryHandler(type: String, appId: String?): Unit =
        throw NotConnectedToControl()

    override suspend fun revokeAllPermissions(appId: String): Unit = throw NotConnectedToControl()
    override suspend fun uninstall(appId: String, scope: UninstallScope): Unit = throw NotConnectedToControl()
}

// ---------------------------------------------------------------------------------------------
// Realm data
// ---------------------------------------------------------------------------------------------

/**
 * ⚠ No type ids, so the Data section lists nothing rather than offering resets for types it cannot
 * see — and [deleteAll] reports [RealmDataResetOutcome.Failed] rather than a count it never deleted.
 */
class NotConnectedRealmDataClient : RealmDataClient {
    override fun realmDataTypeIds(): List<String> = emptyList()
    override fun isResettable(typeId: String): Boolean = false
    override suspend fun hasData(typeId: String): Boolean? = null
    override suspend fun deleteAll(typeId: String): RealmDataResetOutcome = RealmDataResetOutcome.Failed
}

// ---------------------------------------------------------------------------------------------
// Biometrics
// ---------------------------------------------------------------------------------------------

/**
 * The one seam whose *reads* were already safe — [FakeControlBiometricUnlockDelegate] reports
 * unavailable and unwraps to [UnwrapResult.NotAvailable], i.e. it fails **closed**, denying rather
 * than granting. It is replaced anyway for one reason: its `enrollWrapKey` returns
 * [EnrollResult.Success], claiming an enrolment that never happened. ⚠ Uniformity is the other
 * reason — a default list of six `NotConnected*` and one `Fake*` invites the same question at
 * every reading.
 */
class NotConnectedBiometricUnlockDelegate : BiometricUnlockDelegate {
    override suspend fun isAvailable(): Boolean = false
    override suspend fun hasEnrolled(): Boolean = false

    override suspend fun enrollWrapKey(
        wrapKey: ByteArray,
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): EnrollResult = EnrollResult.NotAvailable

    override suspend fun unwrapWrapKey(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): UnwrapResult = UnwrapResult.NotAvailable

    override fun clear() = Unit
}

/** Thrown by the not-connected writes; the message is what a surfaced error shows. */
class NotConnectedToControl : IllegalStateException("Tenshu Control is not running")

// ---------------------------------------------------------------------------------------------
// Sync
// ---------------------------------------------------------------------------------------------

/**
 * ⚠⚠ The sharpest of the seven, and the reason this file was worth writing.
 * [FakeControlSyncRepository] answers **every** write with `ResultSuccessDto` — `createRealm`,
 * `revokeDevice`, `approvePairingRequest`, `recoveryClaim` — and hands the Show tab a well-formed
 * canonical `tenshu://join` link. It also unlocks: `unlockLocal` returns `true` for *any*
 * passphrase. None of that is a vulnerability while it is reached (no real runtime is installed,
 * so there are no keys and no store behind the shell — nothing real is unlocked or created), but
 * every one of them is a **confident false answer** about an operation that never happened, and
 * "the entry point that reaches it forgot one call" is precisely the case this file defends.
 *
 * Here every read and every write fails. ⚠ [getDeviceId] is the one exception: it is a
 * non-`TResult` `String` with nowhere to put a failure, so it returns a clearly non-device
 * sentinel rather than the fake's plausible `"ios-control-device"`.
 */
class NotConnectedControlSyncRepository : SyncRepository {

    override val devices: Flow<List<Device>> = MutableStateFlow(emptyList())

    override suspend fun readmitDevice(deviceId: String): TResult<Data?> = failure()
    override suspend fun revokeDevice(deviceId: String): TResult<Data?> = failure()
    // ⚠⚠ `failure()`, never a success: a not-connected repository must not report that this
    // device forgot itself when nothing happened. Forgetting is destructive and irreversible —
    // a false success here is the worst possible answer.
    override suspend fun forgetThisDevice(): TResult<TString> = failure()

    override suspend fun getRecoveryPhrase(): TResult<TString> = failure()
    override suspend fun getSyncStatus(): TResult<SyncStatus> = failure()
    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> = failure()
    override suspend fun startPairing(): TResult<SyncStatus> = failure()
    override suspend fun stopPairing(): TResult<SyncStatus> = failure()
    override suspend fun approvePairingRequest(deviceId: String): TResult<Data?> = failure()
    override suspend fun createRealm(recoveryPhrase: String, deviceName: String): TResult<Data?> = failure()
    override suspend fun reregisterDevice(): TResult<Data?> = failure()
    override suspend fun recoveryClaim(recoveryPhrase: String, deviceName: String): TResult<Data?> = failure()

    // ⚠ No TResult to fail in. The fake returned "ios-control-device", which reads like a real
    // device id in a log; this cannot be mistaken for one.
    override suspend fun getDeviceId(): String = "not-connected"

    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<PairingInitiation> = failure()

    override suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<TBoolean> = failure()

    override suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray,
    ): TaskHandle<TBoolean> = NotConnectedTaskHandle()

    override suspend fun getUnlockStatus(): TResult<UnlockStatus> = failure()

    // ⚠⚠ The fake returned `true` for all of these — i.e. any passphrase unlocked, and every
    // credential setup reported success.
    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> = failure()
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> = failure()
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> = failure()
    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> = failure()
    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> = failure()
    override suspend fun relaxToHeadless(): TResult<TBoolean> = failure()
    override suspend fun clearBiometricSetup(): TResult<TBoolean> = failure()
    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> = failure()

    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()

    private fun <T : Data> failure(): TResult<T> = ResultFailureDto(GenericErrorDto(MESSAGE))

    private class NotConnectedTaskHandle : TaskHandle<TBoolean> {
        override val taskId: String = "not-connected"
        override suspend fun await(): TResult<TBoolean> = ResultFailureDto(GenericErrorDto(MESSAGE))
        override fun cancel() = Unit
    }

    private companion object {
        const val MESSAGE = "Tenshu Control is not running"
    }
}

// ---------------------------------------------------------------------------------------------
// Commerce account (S2-pre)
// ---------------------------------------------------------------------------------------------

/**
 * ⚠⚠ **The failure channel here is a returned `Error`, not a throw** — the one place in this file
 * that deliberately breaks the "writes throw" pattern above.
 *
 * [PublisherClient]'s writes do not signal failure by throwing; every one of them returns a sealed
 * result with its own `Error` arm, and `FreeAccountSignupViewModel` consumes them in a bare
 * `viewModelScope.launch` with no `try`. A throw there would not surface as an error message — it
 * would escape the coroutine uncaught. So "not connected" is reported the way the interface already
 * says failure, which is also the way the screen already renders it.
 *
 * ⚠ The two non-write members answer truthfully rather than emptily, and the difference is worth
 * naming: [account] is `null` and [currentSessionToken] is `null` because with no runtime **nothing
 * is bound** — that is a fact about this device's local state, not a claim about the user's account
 * on the service. (`ServicesStatusModel.empty()` already takes the same position.) Compare the
 * repositories above, where empty would have asserted something about the realm.
 */
class NotConnectedPublisherClient : PublisherClient {
    override val account: StateFlow<PublisherAccount?> = MutableStateFlow(null)

    override fun currentSessionToken(): String? = null

    override suspend fun register(email: String, displayName: String?): PublisherClient.RegisterResult =
        PublisherClient.RegisterResult.Error(NOT_RUNNING)

    override suspend fun verifyOtp(code: String): PublisherClient.VerifyResult =
        PublisherClient.VerifyResult.Error(NOT_RUNNING)

    override suspend fun redeem(code: String): PublisherClient.RedeemResult =
        PublisherClient.RedeemResult.Error(NOT_RUNNING)

    override suspend fun startCheckout(
        productId: String,
        withdrawalAcknowledged: Boolean,
    ): PublisherClient.CheckoutResult = PublisherClient.CheckoutResult.Error(NOT_RUNNING)

    override suspend fun openBillingPortal(): PublisherClient.PortalResult =
        PublisherClient.PortalResult.Error(NOT_RUNNING)

    override suspend fun syncPlan(): PublisherClient.PlanSyncResult =
        PublisherClient.PlanSyncResult.Error(NOT_RUNNING)

    /**
     * ⚠ A no-op, and the only member here that is not an error — because its postcondition already
     * holds. `disconnect` promises "the local account binding and session token are gone"; with no
     * runtime there was never a binding or a token, so the call has nothing to fail at. It is also
     * the one member with no failure channel (it returns `Unit`), and its single caller —
     * `DisconnectConfirmViewModel.onConfirm` — awaits it uncaught, so a throw would crash rather
     * than inform.
     */
    override suspend fun disconnect() = Unit

    private companion object {
        const val NOT_RUNNING = "Tenshu Control is not running"
    }
}
