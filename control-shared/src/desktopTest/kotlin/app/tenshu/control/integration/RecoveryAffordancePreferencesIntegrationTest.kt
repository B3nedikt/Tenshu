package app.tenshu.control.integration

import org.tenshu.core.TResult
import org.tenshu.core.TString
import kotlinx.coroutines.flow.Flow
import app.tenshu.control.data.SyncRepository
import org.tenshu.core.file.sync.SyncPolicy
import app.tenshu.control.data.TenshuRecoveryAffordancePreferences
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.tenshu.core.common.TenshuClient
import org.tenshu.identity.Device
import kotlin.test.assertEquals

/**
 * Regression test for the broken Dismiss button on the recovery banner: when
 * the synced device id isn't available (`getDeviceId()` returns blank — the
 * common single-device, never-synced state in which the banner shows), the
 * dismiss timestamp must still persist and be observable. Previously both
 * read and write early-returned on a blank id, so Dismiss was a silent no-op.
 */
class RecoveryAffordancePreferencesIntegrationTest {

    @Before
    fun setup() {
        startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
    }

    @After
    fun tearDown() {
        stopAppIntegrationKoin()
    }

    @Test
    fun dismissPersistsEvenWithoutADeviceId() = runTest {
        val client = GlobalContext.get().get<TenshuClient>()
        val prefs = TenshuRecoveryAffordancePreferences(client, BlankDeviceIdSyncRepository())

        // Never dismissed → default 0.
        assertEquals(0L, prefs.observeDismissedUntilMs().first())

        prefs.setDismissedUntilMs(12_345L)

        // The write is readable back through the (fallback-keyed) observe flow.
        assertEquals(12_345L, prefs.observeDismissedUntilMs().first { it == 12_345L })
    }
}

/** Only [getDeviceId] is exercised by the preferences; it returns blank. */
private class BlankDeviceIdSyncRepository : SyncRepository {
    override val devices: Flow<List<Device>> get() = error("unused")
    override suspend fun readmitDevice(deviceId: String) = error("unused")
    override suspend fun revokeDevice(deviceId: String) = error("unused")
    // ⚠ Widening SyncRepository broke every implementor — the interface change is
    // only 'additive' from the caller's side.
    override suspend fun forgetThisDevice(): TResult<TString> = error("not used")

    override suspend fun getRecoveryPhrase() = error("unused")
    override suspend fun getSyncStatus() = error("unused")
    override suspend fun getPendingPairingRequests() = error("unused")
    override suspend fun startPairing() = error("unused")
    override suspend fun stopPairing() = error("unused")
    override suspend fun approvePairingRequest(deviceId: String) = error("unused")
    override suspend fun getDeviceId(): String = ""
    override suspend fun createRealm(recoveryPhrase: String, deviceName: String) = error("unused")
    override suspend fun reregisterDevice() = error("unused")
    override suspend fun recoveryClaim(recoveryPhrase: String, deviceName: String) = error("unused")
    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ) = error("unused")

    override suspend fun pollPairingStatus(pairingSecret: String, otherDevicePublicKey: ByteArray) =
        error("unused")

    override suspend fun awaitPairing(pairingSecret: String, otherDevicePublicKey: ByteArray) =
        error("unused")

    override suspend fun getUnlockStatus() = error("unused")
    override suspend fun setUpLocalUnlock(passphrase: String) = error("unused")
    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?) = error("unused")
    override suspend fun unlockLocal(passphrase: String) = error("unused")
    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray) = error("unused")
    override suspend fun unlockBiometric(wrapKey: ByteArray) = error("unused")
    override suspend fun relaxToHeadless() = error("unused")
    override suspend fun clearBiometricSetup() = error("unused")
    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? = null
    override val deviceHardwareNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun deviceLastSeen(): Map<String, Long> = emptyMap()

    override suspend fun cancelPairing(pairingSecret: String) = error("unused")
}
