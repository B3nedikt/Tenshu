package app.tenshu.control.ui.screens.sync

import org.tenshu.core.TString
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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

/**
 * Shared `SyncRepository` stand-in for the sync-screen UI tests.
 *
 * ⚠ Extracted from `SyncDashboardScreenTest`, unchanged, so a second screen test can reuse it
 * rather than hand-rolling a 29-method duplicate that would drift.
 */
internal open class FakeSyncRepository : SyncRepository {
    val devicesFlow = MutableStateFlow<List<Device>>(emptyList())
    override val devices: Flow<List<Device>> = devicesFlow

    override suspend fun readmitDevice(deviceId: String): TResult<Data?> = error("")
    override suspend fun revokeDevice(deviceId: String): TResult<Data?> = error("")
    // ⚠ Widening SyncRepository broke every implementor — the interface change is
    // only 'additive' from the caller's side.
    override suspend fun forgetThisDevice(): TResult<TString> = error("not used")

    override suspend fun getRecoveryPhrase(): TResult<org.tenshu.core.TString> =
        ResultSuccessDto(org.tenshu.core.common.impl.TStringDto("test phrase"))

    override suspend fun getSyncStatus(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> =
        ResultSuccessDto(emptyList<PairingRequest>().toTList())

    override suspend fun startPairing(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun stopPairing(): TResult<SyncStatus> = ResultSuccessDto(null)
    override suspend fun approvePairingRequest(deviceId: String): TResult<Data?> =
        ResultSuccessDto(null)

    var deviceIdOverride: String = ""
    override suspend fun getDeviceId(): String = deviceIdOverride

    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<org.tenshu.sync.PairingInitiation> = ResultSuccessDto(null)

    override suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): TResult<org.tenshu.core.TBoolean> = ResultSuccessDto(null)

    override suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): TaskHandle<org.tenshu.core.TBoolean> =
        object : TaskHandle<org.tenshu.core.TBoolean> {
            override val taskId = "fake"
            override suspend fun await(): TResult<org.tenshu.core.TBoolean> =
                ResultSuccessDto(null)

            override fun cancel() {}
        }

    override suspend fun createRealm(
        recoveryPhrase: String,
        deviceName: String
    ): TResult<Data?> = ResultSuccessDto(null)

    override suspend fun reregisterDevice(): TResult<Data?> = ResultSuccessDto(null)

    override suspend fun recoveryClaim(
        recoveryPhrase: String,
        deviceName: String
    ): TResult<Data?> = ResultSuccessDto(null)

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
