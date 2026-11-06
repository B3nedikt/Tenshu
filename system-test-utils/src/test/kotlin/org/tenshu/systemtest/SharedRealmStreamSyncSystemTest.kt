package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.core.notifications.Notification
import org.tenshu.sync.createSharedRealm
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.startPairing
import org.tenshu.sync.initiatePairing
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * SR-3b.4c-verify — the headline cross-device proof. End-to-end over the dev relay:
 *
 *   1. host + joiner pair (joiner gets A13 via the pairing key-blob, 4a);
 *   2. host `createSharedRealm()` — bootstraps the realm, self-joins, and **admits the
 *      joiner's A4** to the shared `ownerId` (4b + 4c admit trigger);
 *   3. the `SharedRealmMembership` Eager-syncs to the joiner, which **auto-joins** (4a/4b
 *      observer: unwraps `Kr` with its A13, registers the shared realm with a per-realm
 *      relay from the 4c factory);
 *   4. host writes a `"$sharedOwnerId::…"`-keyed entity → it routes to the shared realm's
 *      queue → drains to the shared `ownerId` stream;
 *   5. the joiner's per-realm pull (its admitted JWT verifies) decrypts it under the shared
 *      keys and applies it.
 *
 * Asserting the joiner receives the namespaced entity exercises the *entire* SR-M + SR-3b
 * stack against a live relay with real crypto — the first real cross-device shared-realm sync.
 */
class SharedRealmStreamSyncSystemTest {

    @Test
    fun hostWrite_toSharedRealm_reachesJoiner(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)

            // The admit trigger enumerates the host's known devices — wait until the
            // joiner's Device entity has synced to the host so it gets admitted.
            system.advanceUntil(timeout = 15.seconds) { host.deviceCount() >= 2 }

            val createResult = host.tenshuClient.syncService.createSharedRealm()
            assertTrue(createResult is TResult.Success, "createSharedRealm should succeed")
            val sharedOwnerId = (createResult.value as TString).get()

            // Host writes a shared-realm-scoped entity (namespaced DataId → routes to the
            // shared realm's keys + queue). A Notification is the simplest registered
            // Syncable; its uuid IS the DataId key.
            val probeKey = "$sharedOwnerId::probe-greeting"
            val probe = host.executionContext.context.create<Notification>(
                type = Notification.ID,
                fields = listOf(probeKey.toTString(host.executionContext.context), UnixInstantDto(0L)),
            )
            host.executionContext.saveData(probe)

            // The joiner must auto-join the shared realm and pull the entity off the shared
            // stream — read it back by its namespaced DataId.
            val probeDataId = DataId.StringDataId.create(
                context = joiner.executionContext.context,
                type = Notification.ID,
                keyValue = probeKey,
            )
            system.advanceUntil(timeout = 20.seconds) {
                joiner.executionContext.database.getData(probeDataId) != null
            }

            val received = joiner.executionContext.database.getData(probeDataId)
            assertNotNull(
                received,
                "joiner should receive the host's shared-realm entity over the shared stream",
            )
        } finally {
            system.shutdown()
        }
    }

    private fun TestClient.deviceCount(): Int {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = IdentityService.Relations.DEVICES,
            ),
            isEntity = true,
            elementSchemaId = Device.ID,
        )
        return executionContext.database.getArrayData(ref)?.toList()?.size ?: 0
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
