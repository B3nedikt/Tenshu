package app.tenshu.control.ui.screens.sync

import org.tenshu.core.TString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.foundation.layout.Column
import app.tenshu.ui.feedback.AUTO_CONTINUE_DURATION_MS
import kotlin.test.assertEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.tenshu.control.data.SyncRepository
import org.tenshu.core.file.sync.SyncPolicy
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.identity.Device
import org.tenshu.sync.SyncStatus
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeBlocking

class PairingWizardScreenTest {

    private lateinit var mockSyncRepository: SyncRepository
    private val viewModels = OwnedViewModels()

    // ⚠ The store the composition resolves its `koinViewModel()`s into. Owned rather than
    // merely cleared: `clear()` cancels each `viewModelScope` without waiting for it.
    private val viewModelStore = viewModels.ownStore(ViewModelStore())

    @Before
    fun setup() {
        stopKoin()
        mockSyncRepository = object : SyncRepository {
            override val devices = MutableStateFlow<List<Device>>(emptyList())


            override suspend fun readmitDevice(deviceId: String): TResult<org.tenshu.core.Data?> =
                ResultSuccessDto<org.tenshu.core.Data>(listOf(null))
            override suspend fun revokeDevice(deviceId: String): TResult<org.tenshu.core.Data?> =
                ResultSuccessDto<org.tenshu.core.Data>(listOf(null))

            // ⚠ Widening SyncRepository broke every implementor — the interface change is
            // only 'additive' from the caller's side.
            override suspend fun forgetThisDevice(): TResult<TString> = error("not used")

            override suspend fun getRecoveryPhrase(): TResult<org.tenshu.core.TString> =
                ResultSuccessDto(org.tenshu.core.common.impl.TStringDto(""))

            // Benign defaults: the unified screen wires in a HostPairingMonitor whose init
            // reads getSyncStatus + polls getPendingPairingRequests. Return clean empties so
            // the monitor's process-lifetime loop doesn't throw under test.
            override suspend fun getSyncStatus(): TResult<SyncStatus> = ResultSuccessDto(null)
            override suspend fun getPendingPairingRequests(): TResult<org.tenshu.core.collection.list.TList<org.tenshu.sync.PairingRequest>> =
                ResultSuccessDto(emptyList<org.tenshu.sync.PairingRequest>().toTList())

            override suspend fun startPairing(): TResult<SyncStatus> = ResultSuccessDto(null)
            override suspend fun stopPairing(): TResult<SyncStatus> = ResultSuccessDto(null)
            override suspend fun approvePairingRequest(deviceId: String): TResult<org.tenshu.core.Data?> =
                error("")

            override suspend fun getDeviceId(): String = ""

            override suspend fun initiatePairing(
                ownerId: String,
                pairingSecret: String,
                deviceName: String,
                otherDevicePublicKey: ByteArray,
            ): TResult<org.tenshu.sync.PairingInitiation> = ResultSuccessDto(listOf(null))

            override suspend fun pollPairingStatus(
                pairingSecret: String,
                otherDevicePublicKey: ByteArray
            ): TResult<org.tenshu.core.TBoolean> = ResultSuccessDto(listOf(null))

            override suspend fun awaitPairing(
                pairingSecret: String,
                otherDevicePublicKey: ByteArray
            ): TaskHandle<org.tenshu.core.TBoolean> =
                object : TaskHandle<org.tenshu.core.TBoolean> {
                    override val taskId = "fake"
                    override suspend fun await(): TResult<org.tenshu.core.TBoolean> =
                        ResultSuccessDto(listOf(null))

                    override fun cancel() {}
                }

            override suspend fun createRealm(
                recoveryPhrase: String,
                deviceName: String
            ): TResult<org.tenshu.core.Data?> = ResultSuccessDto(null)

            override suspend fun reregisterDevice(): TResult<org.tenshu.core.Data?> =
                ResultSuccessDto(null)

            override suspend fun recoveryClaim(
                recoveryPhrase: String,
                deviceName: String
            ): TResult<org.tenshu.core.Data?> = ResultSuccessDto(null)

            override suspend fun getUnlockStatus(): TResult<org.tenshu.sync.UnlockStatus> =
                ResultSuccessDto(null)

            override suspend fun setUpLocalUnlock(passphrase: String): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override suspend fun unlockLocal(passphrase: String): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override suspend fun relaxToHeadless(): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override suspend fun clearBiometricSetup(): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
            override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
            override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()

            override suspend fun cancelPairing(pairingSecret: String): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)
        }

        startKoin {
            modules(module {
                single { mockSyncRepository }
                single { app.tenshu.control.sync.HostPairingMonitor(get()) }
                viewModel { PairingWizardViewModel(get(), get()) }
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
    fun pairingWizardFlow() = runComposeUiTest {
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@PairingWizardScreenTest.viewModelStore
        }

        // Generate a valid canonical join URI for the test
        val publicKey = ByteArray(32) { 0x01.toByte() }
        val secret = "test-secret"
        val pairingUri = org.tenshu.core.common.util.PairingUri.joinUri(
            ownerId = "user-1",
            hostDeviceId = "device-2",
            hostPublicKey = publicKey,
            pairingSecret = secret.encodeToByteArray(),
        )

        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    PairingWizardScreen(onBack = {}, onComplete = {})
                }
            }
        }

        // Phase 2: the unified screen defaults to the Scan tab → camera scanner. The
        // segmented control offers Scan / Show; the scanner surface links out to manual entry.
        onNodeWithText("Scan a code").assertIsDisplayed()
        onNodeWithText("Show a code").assertIsDisplayed()
        onNodeWithText("Enter code instead").performClick()

        // §4a.2b — manual code entry
        onNodeWithText("Pairing Code").performTextReplacement(pairingUri)
        onNodeWithText("Continue").performClick()

        // §4a.3 — name device
        onNodeWithText("Name this device").assertIsDisplayed()
        onNodeWithText("Device Name").performTextReplacement("My Test Device")
        onNodeWithText("Continue").performClick()
    }

    // --- Auto-continue on the terminals (control-pairing-ux.md §9) ---

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hostDeviceAdded_autoContinuesAfterThreeSeconds() = runComposeUiTest {
        mainClock.autoAdvance = false
        var done = 0
        setContent {
            TenshuTheme {
                Column {
                    ShowCodeStep(
                        pairingString = null,
                        connectionLost = false,
                        pairingComplete = true,
                        realmUnfunded = false,
                        startError = null,
                        onRetry = {},
                        onStop = {},
                        onDone = { done++ },
                        onSetUpStorage = {},
                        autoContinueArmed = true,
                    )
                }
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithText("Device added").assertIsDisplayed()
        onNodeWithText("Stay").assertIsDisplayed()

        mainClock.advanceTimeBy(AUTO_CONTINUE_DURATION_MS - 500)
        assertEquals(0, done)
        mainClock.advanceTimeBy(1_000)
        assertEquals(1, done, "the host's 'Device added' leaves by itself after 3 s")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hostDeviceAdded_onAnUnfundedRealm_neverCountsDown() = runComposeUiTest {
        mainClock.autoAdvance = false
        var done = 0
        setContent {
            TenshuTheme {
                Column {
                    ShowCodeStep(
                        pairingString = null,
                        connectionLost = false,
                        pairingComplete = true,
                        realmUnfunded = true,
                        startError = null,
                        onRetry = {},
                        onStop = {},
                        onDone = { done++ },
                        onSetUpStorage = {},
                        // Even a wrongly-armed caller must not race the storage CTA.
                        autoContinueArmed = true,
                    )
                }
            }
        }
        mainClock.advanceTimeBy(10_000)
        assertEquals(0, done)
        assertEquals(0, onAllNodesWithText("Stay").fetchSemanticsNodes().size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sasResponderSuccess_staysManual() = runComposeUiTest {
        mainClock.autoAdvance = false
        var done = 0
        setContent {
            TenshuTheme {
                Column {
                    ResponderSuccessStep(
                        sas = "482913",
                        isHub = false,
                        onContinue = { done++ },
                        // The VM never arms this screen; the step refuses to even if asked.
                        autoContinueArmed = true,
                    )
                }
            }
        }
        mainClock.advanceTimeBy(10_000)
        assertEquals(0, done, "the SAS stays up for the side-by-side check (T-P-07)")
        onNodeWithText("482 913").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("Stay").fetchSemanticsNodes().size)
        onNodeWithText("Continue").performClick()
        assertEquals(1, done)
    }
}
