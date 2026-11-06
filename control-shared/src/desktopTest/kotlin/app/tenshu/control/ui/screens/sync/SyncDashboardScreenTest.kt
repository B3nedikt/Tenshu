package app.tenshu.control.ui.screens.sync

import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.tenshu.control.data.AppLockAuthResult
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.sync.HostPairingMonitor
import org.tenshu.core.file.sync.SyncPolicy
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeBlocking

class SyncDashboardScreenTest {

    private val viewModels = OwnedViewModels()

    // ⚠ The store the composition resolves its `koinViewModel()`s into. Owned rather than
    // merely cleared: `clear()` cancels each `viewModelScope` without waiting for it.
    private val viewModelStore = viewModels.ownStore(ViewModelStore())


    private lateinit var mockSyncRepository: FakeSyncRepository

    /**
     * Ph-reauth (RA-3). Answers `Success` by default so every pre-existing case keeps asserting
     * what it was written to assert; the refusal case swaps it before starting Koin.
     */
    private var reauthenticator = FakeReauthenticator(AppLockAuthResult.Success)

    @Before
    fun setup() {
        stopKoin()
        mockSyncRepository = FakeSyncRepository()
        reauthenticator = FakeReauthenticator(AppLockAuthResult.Success)

        startKoin {
            modules(module {
                single<SyncRepository> { mockSyncRepository }
                single { HostPairingMonitor(get()) }
                single<Reauthenticator> { reauthenticator }
                viewModel { SyncDashboardViewModel(get(), get()) }
            })
        }
    }

    @After
    fun tearDown() {
        viewModels.closeBlocking()
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dashboardDisplaysEmptyState() = runComposeUiTest {
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@SyncDashboardScreenTest.viewModelStore
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    SyncDashboardScreen(
                        onAddDevice = {},
                        onShowRecoveryPhrase = {}
                    )
                }
            }
        }

        onNodeWithText("Realm devices").assertIsDisplayed()
        onNodeWithText("View Recovery Phrase").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dashboardDisplaysDevices() = runComposeUiTest {
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@SyncDashboardScreenTest.viewModelStore
        }

        val context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(
            identityServiceCommonModule
        )

        val deviceId = "current-id"
        val device = Device.create(
            context = context,
            deviceId = deviceId,
            name = "Current Phone",
            publicKey = byteArrayOf(1)
        )
        mockSyncRepository.devicesFlow.value = listOf(device)
        mockSyncRepository.deviceIdOverride = deviceId

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    SyncDashboardScreen(
                        onAddDevice = {},
                        onShowRecoveryPhrase = {}
                    )
                }
            }
        }

        onNodeWithText("Current Phone").assertIsDisplayed()
        onNodeWithText("This Device").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dashboardDisplaysDeviceSyncPolicy() = runComposeUiTest {
        // §4.6: the device list surfaces each device's read-only offline-sync
        // policy, read through the typed FileSynchronisationSettings facade.
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@SyncDashboardScreenTest.viewModelStore
        }

        val context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
        val device = Device.create(
            context = context,
            deviceId = "dev-keep",
            name = "Office Desktop",
            publicKey = byteArrayOf(3)
        )

        val mockRepo = object : FakeSyncRepository() {
            override suspend fun deviceSyncPolicy(deviceId: String) =
                org.tenshu.core.file.sync.SyncPolicy.KEEP_OFFLINE
        }
        mockRepo.devicesFlow.value = listOf(device)

        stopKoin()
        startKoin {
            modules(module {
                single<SyncRepository> { mockRepo }
                single { HostPairingMonitor(get()) }
                single<Reauthenticator> { reauthenticator }
                viewModel { SyncDashboardViewModel(get(), get()) }
            })
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    SyncDashboardScreen(onAddDevice = {}, onShowRecoveryPhrase = {})
                }
            }
        }

        waitUntil(timeoutMillis = 10000) {
            onAllNodesWithText("Office Desktop").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Keeps all files on this device").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun approvalDialogDisplaysRequest() = runComposeUiTest {
        // control-pairing-ux.md Phase 1: the Approve/Deny approval is now the app-global
        // HostPairingApprovalDialog (mounted at the App root, driven by HostPairingMonitor),
        // not the Sync screen. Render it directly with a mock request.
        val registry = org.tenshu.core.common.createCoreTestRegistry()
        registry.registerConfig(org.tenshu.sync.common.syncServiceCommonModule)
        @Suppress("UNUSED_VARIABLE")
        val context = createTestContext(schemaRegistry = registry)

        val request = object : PairingRequest {
            override val deviceId = "tablet-id".toTString()
            override val deviceName = "New Tablet".toTString()
            override val publicKey = byteArrayOf().toTByteArray()
            override val pairingSecretHash = "secret".toTString()
            override val schema: org.tenshu.core.schema.CompositeSchema get() = PairingRequest.Schema
            override fun <T : Data> getField(reference: String): T {
                @Suppress("UNCHECKED_CAST")
                return when (reference) {
                    PairingRequest.Fields.DEVICE_ID -> deviceId
                    PairingRequest.Fields.DEVICE_NAME -> deviceName
                    PairingRequest.Fields.PUBLIC_KEY -> publicKey
                    PairingRequest.Fields.PAIRING_SECRET_HASH -> pairingSecretHash
                    else -> error("Field not mocked: $reference")
                } as T
            }

            override fun <T : Data> getOptionalField(reference: String): T? = null
        }

        setContent {
            TenshuTheme {
                app.tenshu.control.ui.sync.HostPairingApprovalDialog(
                    request = request,
                    onApprove = {},
                    onDeny = {},
                )
            }
        }

        waitUntil(timeoutMillis = 10000) {
            onAllNodesWithText("Authorization Request").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Device 'New Tablet' wants to join", substring = true).assertIsDisplayed()
        onNodeWithText("Approve").assertIsDisplayed()
        onNodeWithText("Deny").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dashboardRevokeDeviceTriggersRepository() {
        var revokeCalledWith: String? = null
        val mockRepo = object : FakeSyncRepository() {
            override suspend fun revokeDevice(deviceId: String): TResult<Data?> {
                revokeCalledWith = deviceId
                return ResultSuccessDto(null)
            }
        }

        val context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(
            identityServiceCommonModule
        )
        val device = Device.create(
            context = context,
            deviceId = "other-id",
            name = "Other Phone",
            publicKey = byteArrayOf(2)
        )
        mockRepo.devicesFlow.value = listOf(device)

        stopKoin()
        startKoin {
            modules(module {
                single<SyncRepository> { mockRepo }
                single { HostPairingMonitor(get()) }
                single<Reauthenticator> { reauthenticator }
                viewModel { SyncDashboardViewModel(get(), get()) }
            })
        }

        runComposeUiTest {
            val viewModelStoreOwner = object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = this@SyncDashboardScreenTest.viewModelStore
            }

            setContent {
                TenshuTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                        SyncDashboardScreen(onAddDevice = {}, onShowRecoveryPhrase = {})
                    }
                }
            }

            waitUntil(timeoutMillis = 10000) {
                onAllNodesWithText("Other Phone").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Other Phone").assertIsDisplayed()
            onNodeWithContentDescription("Revoke Device").performClick()

            // ⚠ The tap now opens a confirmation rather than revoking — removal is final for that
            // identity, so it is acknowledged before it is confirmed. This test keeps its original
            // claim (the repository is reached) and gains the two steps that have to happen first.
            // `revokingADevice_asksFirst_…` and `…_needsTheAcknowledgement_…` cover the negatives.
            onNodeWithText("I understand this cannot be undone").performClick()
            onNodeWithText("Remove device").performClick()

            waitUntil(timeoutMillis = 10000) { revokeCalledWith == "other-id" }
        }
    }

    // --- ReadmitDevice ("Restore access"): the exit for a peer the relay no longer recognises ---

    private fun peerDevice(id: String, name: String): Device {
        val context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
        return Device.create(context = context, deviceId = id, name = name, publicKey = byteArrayOf(2))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun restoreAccessOnAPeerRow_callsBackWithThatDevicesId() = runComposeUiTest {
        var readmitCalledWith: String? = null
        setContent {
            TenshuTheme {
                SyncDashboardContent(
                    uiState = SyncDashboardUiState(
                        devices = listOf(peerDevice("other-id", "Other Phone")),
                        isSyncEnabled = true,
                    ),
                    currentDeviceId = "this-id",
                    onRefresh = {},
                    onRevokeDevice = {},
                    onApproveDevice = {},
                    onReadmitDevice = { readmitCalledWith = it },
                    onAddDevice = {},
                    onShowRecoveryPhrase = {},
                    onCreateRealm = {},
                )
            }
        }

        // ⚠ No confirmation step, unlike Revoke: this restores access the roster already asserts,
        // grants nothing new, and is idempotent. The tap IS the action.
        onNodeWithContentDescription("Restore access").performClick()
        assertEquals("other-id", readmitCalledWith)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun restoreAccessIsNotOfferedForThisDevice() = runComposeUiTest {
        // `ReadmitDevice` refuses self and points at `ReregisterDevice`, which re-asserts genesis
        // first. An affordance that can only ever refuse is worse than none.
        setContent {
            TenshuTheme {
                SyncDashboardContent(
                    uiState = SyncDashboardUiState(
                        devices = listOf(peerDevice("this-id", "This Phone")),
                        isSyncEnabled = true,
                    ),
                    currentDeviceId = "this-id",
                    onRefresh = {},
                    onRevokeDevice = {},
                    onApproveDevice = {},
                    onReadmitDevice = {},
                    onAddDevice = {},
                    onShowRecoveryPhrase = {},
                    onCreateRealm = {},
                )
            }
        }

        onNodeWithText("This Phone").assertIsDisplayed()
        onAllNodesWithContentDescription("Restore access").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anOutcomeReachesTheUser_ratherThanVanishing() = runComposeUiTest {
        // ⚠⚠ Until this screen grew a snackbar host it rendered `uiState.error` NOWHERE — only the
        // iOS roster ever showed it. `Restore access` acts on ANOTHER device, so with no message
        // here a success and a dead button are the same thing. Pinned for both outcomes.
        setContent {
            TenshuTheme {
                SyncDashboardContent(
                    uiState = SyncDashboardUiState(
                        isSyncEnabled = true,
                        error = "Only a device holding the master identity can readmit another device",
                    ),
                    currentDeviceId = "this-id",
                    onRefresh = {},
                    onRevokeDevice = {},
                    onApproveDevice = {},
                    onReadmitDevice = {},
                    onAddDevice = {},
                    onShowRecoveryPhrase = {},
                    onCreateRealm = {},
                )
            }
        }

        waitUntil(timeoutMillis = 10000) {
            onAllNodesWithText(
                "Only a device holding the master identity can readmit another device"
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pairingRowShowsCountdownWhileWaiting() = runComposeUiTest {
        // control-pairing-ux.md §3.5: the "in Pairing" placeholder row shows the bounded-window
        // countdown while waiting for a joiner. Driven through SyncDashboardContent directly
        // (the monitor state is mirrored into uiState) to keep the assertion deterministic.
        setContent {
            TenshuTheme {
                SyncDashboardContent(
                    uiState = SyncDashboardUiState(
                        isSyncEnabled = true,
                        isPairing = true,
                        pairingRemainingSeconds = 95,
                    ),
                    currentDeviceId = "d",
                    onRefresh = {},
                    onRevokeDevice = {},
                    onApproveDevice = {},
                    onAddDevice = {},
                    onShowRecoveryPhrase = {},
                    onCreateRealm = {},
                )
            }
        }

        onNodeWithText("Pairing in progress — 1:35 left").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pairingRowFlipsToReviewWhenRequestArrives() = runComposeUiTest {
        // §3.6 / Phase 3-Android: once a joiner's request arrives the row flips to the
        // PII-free "wants to join" review prompt (the SAS comparison is the global modal).
        setContent {
            TenshuTheme {
                SyncDashboardContent(
                    uiState = SyncDashboardUiState(
                        isSyncEnabled = true,
                        isPairing = true,
                        pairingRequestPending = true,
                    ),
                    currentDeviceId = "d",
                    onRefresh = {},
                    onRevokeDevice = {},
                    onApproveDevice = {},
                    onAddDevice = {},
                    onShowRecoveryPhrase = {},
                    onCreateRealm = {},
                )
            }
        }

        onNodeWithText("A device wants to join — tap to review").assertIsDisplayed()
    }

    // NOTE (control-pairing-ux.md Phase 2): the dashboard no longer hosts a "show QR" dialog
    // or a top-bar QR button — "Add a device" is the FAB → unified PairingWizardScreen, whose
    // Show tab hosts the origin QR. The two former dialog tests
    // (pairingDialogStaysOpenOnInternalClick / pairingDialogBlocksBackgroundClicks) are gone
    // with the dialog; that surface is covered by PairingWizardScreenTest /
    // PairingWizardViewModelTest.

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun revokingADevice_asksFirst_andDoesNotRevokeOnTheTapAlone() = runComposeUiTest {
        // ⚠⚠ THE point of the confirmation: removing a device takes its access away immediately and
        // is final for that identity — under option F
        // (admission-revocation-names-the-capability.md §4.1) there is no re-admission at all, so
        // the device can only ever come back as a NEW one. Before this, that was one un-confirmed
        // icon tap, while revoking a SHARE link had had a confirm dialog since it shipped.
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@SyncDashboardScreenTest.viewModelStore
        }
        val context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
        val device = Device.create(
            context = context,
            deviceId = "dev-other",
            name = "Office Desktop",
            publicKey = byteArrayOf(4),
            isAuthorized = true,
        )

        var revoked: String? = null
        val mockRepo = object : FakeSyncRepository() {
            override suspend fun revokeDevice(deviceId: String): TResult<Data?> {
                revoked = deviceId
                return ResultSuccessDto(null)
            }
        }
        mockRepo.devicesFlow.value = listOf(device)

        stopKoin()
        startKoin {
            modules(module {
                single<SyncRepository> { mockRepo }
                single { HostPairingMonitor(get()) }
                single<Reauthenticator> { reauthenticator }
                viewModel { SyncDashboardViewModel(get(), get()) }
            })
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    SyncDashboardScreen(onAddDevice = {}, onShowRecoveryPhrase = {})
                }
            }
        }

        waitUntil(timeoutMillis = 10000) {
            onAllNodesWithText("Office Desktop").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithContentDescription("Revoke Device").performClick()
        waitForIdle()

        assertNull(revoked, "the tap alone must NOT revoke — that is the whole change")
        onNodeWithText("Remove this device?").assertIsDisplayed()

        // ⚠ Acknowledging is what arms the confirm button — see
        // revokingADevice_needsTheAcknowledgement_notJustTheConfirmTap for the gate itself.
        onNodeWithText("I understand this cannot be undone").performClick()
        waitForIdle()

        onNodeWithText("Remove device").performClick()
        waitForIdle()
        assertEquals("dev-other", revoked, "confirming must actually revoke")
    }

    /**
     * ⚠⚠ Ph-reauth's guard for RA-3 (`control-keep-device-unlocked.md` §6.1), written so that
     * DELETING the gate turns it red: it drives the dialog all the way through — tick, confirm —
     * with a refusing prompt, and asserts nothing was revoked.
     *
     * ⚠ The gate is on the **confirm**, not on opening the dialog: the dialog is where the user
     * learns removal is final for that identity, so prompting before they have read it would be
     * asking them to authorise something unexplained. (The recovery-phrase and forget-device gates
     * sit *before* their dialogs, because those dialogs disclose the phrase.)
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun refusingTheReauthPrompt_doesNotRevoke() = runComposeUiTest {
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@SyncDashboardScreenTest.viewModelStore
        }
        val context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
        val device = Device.create(
            context = context,
            deviceId = "dev-other",
            name = "Office Desktop",
            publicKey = byteArrayOf(4),
            isAuthorized = true,
        )

        var revoked: String? = null
        val mockRepo = object : FakeSyncRepository() {
            override suspend fun revokeDevice(deviceId: String): TResult<Data?> {
                revoked = deviceId
                return ResultSuccessDto(null)
            }
        }
        mockRepo.devicesFlow.value = listOf(device)
        reauthenticator = FakeReauthenticator(AppLockAuthResult.Cancelled)

        stopKoin()
        startKoin {
            modules(module {
                single<SyncRepository> { mockRepo }
                single { HostPairingMonitor(get()) }
                single<Reauthenticator> { reauthenticator }
                viewModel { SyncDashboardViewModel(get(), get()) }
            })
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    SyncDashboardScreen(onAddDevice = {}, onShowRecoveryPhrase = {})
                }
            }
        }

        waitUntil(timeoutMillis = 10000) {
            onAllNodesWithText("Office Desktop").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithContentDescription("Revoke Device").performClick()
        waitForIdle()
        onNodeWithText("I understand this cannot be undone").performClick()
        waitForIdle()
        onNodeWithText("Remove device").performClick()
        waitForIdle()

        assertEquals(1, reauthenticator.calls, "confirming must ask")
        assertNull(revoked, "a refused prompt must not remove the device")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun revokingADevice_needsTheAcknowledgement_notJustTheConfirmTap() = runComposeUiTest {
        // ⚠⚠ This is the guard for the checkbox gate, and it is written so that DELETING the gate
        // turns it red: it taps "Remove device" while the box is unchecked and asserts nothing was
        // revoked. Remove `enabled = acknowledged` and the tap lands, `revoked` is set, and this
        // fails — which a test that only checked the box first would never notice.
        //
        // ⚠⚠ Why the gate exists at all: option F trades away re-admission, so a mistaken removal
        // cannot be undone by anyone, including the recovery phrase holder. The mis-tap has to be
        // caught BEFORE it happens. That makes this checkbox load-bearing, not decoration.
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@SyncDashboardScreenTest.viewModelStore
        }
        val context = createTestContext()
        (context.schemaProvider() as SchemaRegistry).registerConfig(identityServiceCommonModule)
        val device = Device.create(
            context = context,
            deviceId = "dev-other",
            name = "Office Desktop",
            publicKey = byteArrayOf(4),
            isAuthorized = true,
        )

        var revoked: String? = null
        val mockRepo = object : FakeSyncRepository() {
            override suspend fun revokeDevice(deviceId: String): TResult<Data?> {
                revoked = deviceId
                return ResultSuccessDto(null)
            }
        }
        mockRepo.devicesFlow.value = listOf(device)

        stopKoin()
        startKoin {
            modules(module {
                single<SyncRepository> { mockRepo }
                single { HostPairingMonitor(get()) }
                single<Reauthenticator> { reauthenticator }
                viewModel { SyncDashboardViewModel(get(), get()) }
            })
        }

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    SyncDashboardScreen(onAddDevice = {}, onShowRecoveryPhrase = {})
                }
            }
        }

        waitUntil(timeoutMillis = 10000) {
            onAllNodesWithText("Office Desktop").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithContentDescription("Revoke Device").performClick()
        waitForIdle()

        // The confirm tap WITHOUT the acknowledgement must do nothing at all.
        onNodeWithText("Remove device").performClick()
        waitForIdle()
        assertNull(revoked, "an unacknowledged confirm tap must not revoke — the gate is the point")

        // And the same tap must work once acknowledged, so the test cannot pass by the button
        // being permanently dead.
        onNodeWithText("I understand this cannot be undone").performClick()
        waitForIdle()
        onNodeWithText("Remove device").performClick()
        waitForIdle()
        assertEquals("dev-other", revoked, "acknowledging must arm the confirm button")
    }

}
