package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.Setting
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import org.tenshu.preference.client.setForDevice
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 2 canonical DoD for `core-preference-service.md`: host writes a
 * `RealmSetting`, joiner observes it through the SDK chain; ditto
 * `DeviceSetting`. Both rides on the regular `Syncable(Eager)` propagation
 * path, so this test also indirectly proves the schema sync-trait wiring.
 *
 * Mirrors `FolderSyncViaSdkSystemTest` structurally (pairing + read via
 * SDK chain). The chain shape is
 * `service.realmSettings.search<RealmSetting> { preferenceId equalTo X }.get()`
 * — same shape the proposal §6.1 documents.
 */
class PreferenceSyncSystemTest {

    private val testWifiOnly = Preference(
        id = "test.wifiOnly",
        scope = PreferenceScope.REALM,
        default = false,
        adapter = BooleanAdapter,
    )

    private val testDevicePinned = Preference(
        id = "test.devicePinned",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )

    @Test fun joinerSdkChain_findsHostWrittenRealmSetting() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)

            assertTrue(
                host.tenshuClient.preferenceService
                    .set(testWifiOnly, value = true) is TResult.Success<*>,
                "host write should succeed"
            )

            system.advanceUntil(timeout = 15.seconds) {
                val maybe = runCatching {
                    joiner.tenshuClient.preferenceService.realmSettings
                        .search<RealmSetting> { this.preferenceId equalTo testWifiOnly.id }
                        .resolve()
                }.getOrNull()
                maybe is TResult.Success<*> && maybe.value != null
            }

            val maybe = joiner.tenshuClient.preferenceService.realmSettings
                .search<RealmSetting> { this.preferenceId equalTo testWifiOnly.id }
                .resolve()
            val raw = (maybe as TResult.Success<*>).value
            assertNotNull(raw, "joiner should see the realm setting written by host")
            val composite = raw as Composite

            val decoded = BooleanAdapter.fromData(composite.getField<Data>(Setting.Field.VALUE))
            assertEquals(true, decoded, "joiner should decode the round-tripped boolean")
        } finally {
            system.shutdown()
        }
    }

    @Test fun joinerSdkChain_findsHostWrittenDeviceSetting() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)

            // Use the host's deviceId so the (deviceId, preferenceId) row is
            // anchored to a real device. Joiner sees the row keyed by host's
            // deviceId — that's the §3.9 "every device sees every other
            // device's rows" guarantee.
            val hostDeviceId = host.syncManager.currentDeviceId
            assertTrue(
                host.tenshuClient.preferenceService.setForDevice(
                    pref = testDevicePinned,
                    value = true,
                    deviceId = hostDeviceId,
                ) is TResult.Success<*>,
                "host setForDevice should succeed"
            )

            val key = DeviceSetting.compositeKey(hostDeviceId, testDevicePinned.id)
            system.advanceUntil(timeout = 15.seconds) {
                val maybe = runCatching {
                    joiner.tenshuClient.preferenceService.deviceSettings
                        .search<DeviceSetting> { this.compositeKey equalTo key }
                        .resolve()
                }.getOrNull()
                maybe is TResult.Success<*> && maybe.value != null
            }

            val maybe = joiner.tenshuClient.preferenceService.deviceSettings
                .search<DeviceSetting> { this.compositeKey equalTo key }
                .resolve()
            val raw = (maybe as TResult.Success<*>).value
            assertNotNull(raw, "joiner should see the device setting written by host")
            val composite = raw as Composite

            val decoded = BooleanAdapter.fromData(composite.getField<Data>(Setting.Field.VALUE))
            assertEquals(true, decoded)
        } finally {
            system.shutdown()
        }
    }

    private suspend fun pairHostAndJoiner(system: TestSystem): Pair<TestClient, TestClient> {
        val host = system.client("host", TestClientRole.RealmHost)
        val joiner = system.client("joiner", TestClientRole.JoiningDevice)

        host.bootstrapAsHost(deviceName = "Host Tablet")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Joiner Phone",
            otherDevicePublicKey = parsed.hostPairingPublicKey
        )
        val request = host.tenshuClient.syncService.getPendingPairingRequests()
            .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
        host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        system.advanceUntil(timeout = 15.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            status is TResult.Success && status.value?.get() == true
        }

        return host to joiner
    }
}
