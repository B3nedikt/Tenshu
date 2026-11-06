package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.file.sync.FileSyncSetting
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.ruleSet
import org.tenshu.file.client.fileSyncDefaultPolicyOrNull
import org.tenshu.file.client.fileSyncSettingFor
import org.tenshu.file.client.fileSyncSettings
import org.tenshu.file.client.setFileSyncDefaultPolicy
import org.tenshu.file.client.setFileSyncOverrides
import org.tenshu.preference.client.preferenceService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
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
 * End-to-end proof of the first-class `fileSyncSettings` relation
 * (`docs/proposals/drive-offline-online.md` §"Follow-up — a first-class
 * serviceDataRelation"): the host writes its file-sync settings (the two
 * `file.sync.*` `DeviceSetting` rows), and the joiner reads them back as a single
 * typed [FileSyncSetting] projection through the SDK chain
 * (`preferenceService.fileSyncSettings`).
 *
 * This exercises all three slices together over a real client→relay→client path:
 *  - the settings ride the regular `Syncable(Eager)` `DeviceSetting` propagation;
 *  - the joiner's proxy-side `FileSyncSettingsRelationMaintainer` **re-derives** the
 *    projection from the sync-arriving rows (membership is never on the wire);
 *  - the cross-config client accessor resolves the relation hung off
 *    `PreferenceService.ID`.
 *
 * Mirrors [PreferenceSyncSystemTest] structurally.
 */
class FileSyncSettingsRelationSyncSystemTest {

    @Test fun joinerSeesHostFileSyncSettingsAsATypedProjection() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)

            // The settings are DEVICE-scoped; anchor them to the host's real device
            // so the joiner sees the row keyed by the host's deviceId (the §3.9
            // "every device sees every other device's rows" guarantee).
            val hostDeviceId = host.syncManager.currentDeviceId
            val overrides = listOf(PathSyncOverride("/Photos", SyncPolicy.ONLINE_ONLY))

            assertTrue(
                host.tenshuClient.preferenceService
                    .setFileSyncDefaultPolicy(hostDeviceId, SyncPolicy.KEEP_OFFLINE) is TResult.Success<*>,
                "host should write its default policy",
            )
            assertTrue(
                host.tenshuClient.preferenceService
                    .setFileSyncOverrides(hostDeviceId, overrides) is TResult.Success<*>,
                "host should write its overrides",
            )

            // Wait until the joiner's re-derived projection carries BOTH source
            // values (the 2->1 fold completing after both rows have synced).
            system.advanceUntil(timeout = 15.seconds) {
                val ruleSet = runCatching {
                    joiner.tenshuClient.preferenceService.fileSyncSettingFor(hostDeviceId)?.ruleSet()
                }.getOrNull()
                ruleSet?.deviceDefault == SyncPolicy.KEEP_OFFLINE && ruleSet.overrides == overrides
            }

            // Final assertions through the chain accessor + the typed convenience.
            val rows = (joiner.tenshuClient.preferenceService.fileSyncSettings
                .search<FileSyncSetting> { this.deviceId equalTo hostDeviceId }
                .resolve() as TResult.Success<*>).value
            assertNotNull(rows, "joiner should find the host's file-sync projection row")

            val ruleSet = joiner.tenshuClient.preferenceService
                .fileSyncSettingFor(hostDeviceId)
                ?.ruleSet()
            assertNotNull(ruleSet, "the projection decodes to a typed rule set")
            assertEquals(SyncPolicy.KEEP_OFFLINE, ruleSet.deviceDefault, "default folded into the projection")
            assertEquals(overrides, ruleSet.overrides, "overrides folded into the same projection")

            // The per-preference row accessor (Control's §4.6 read path) over the
            // same synced rows — guards the `search<DeviceSetting>` extraction
            // against an `Entity<T>` checkcast on the bare composite the chain
            // hands back (the Control Settings ClassCastException crash).
            assertEquals(
                SyncPolicy.KEEP_OFFLINE,
                joiner.tenshuClient.preferenceService.fileSyncDefaultPolicyOrNull(hostDeviceId),
                "explicit row read back through fileSyncDefaultPolicyOrNull",
            )
            assertEquals(
                null,
                joiner.tenshuClient.preferenceService.fileSyncDefaultPolicyOrNull("no-such-device"),
                "a device with no policy row reads as null, not a default",
            )
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
