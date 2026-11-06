package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The first proper SystemTest: a host bootstraps a realm against the **real**
 * `:sync-relay-server` running in-process. This proves the framework's
 * end-to-end path works for a single client:
 *
 * - app side: `TenshuClient.syncService.createRealm(...)`
 * - through `ProxyGateway` (real)
 * - to `CreateRealmImplementation` (real)
 * - via `RestSyncRelay` over the testApplication client (real RestSyncRelay,
 *   no socket)
 * - to `:sync-relay-server`'s `POST /sync/realm` (real routing, real
 *   `RealmStore`, real master-delegation verification)
 *
 * Two-client tests (e.g. `PairingSystemTest`) are blocked on the Koin
 * module-state-sharing finding documented there.
 *
 * Uses `runBlocking` rather than `runTest`: `ProxyGateway.call` uses
 * `runBlocking` internally, which deadlocks against `runTest`'s test
 * dispatcher. Wall-clock waits are fine — this completes in a few hundred ms.
 */
class BootstrapSystemTest {

    @Test fun host_createRealm_registersWithRelay() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)

            val result = host.bootstrapAsHost(deviceName = "Host Tablet")
            assertTrue(
                result is TResult.Success,
                "createRealm should succeed; got $result"
            )

            // After bootstrap, syncManager has a current device and owner identity
            // — these only get set inside CreateRealmImplementation after the relay
            // round-trip, so their presence proves the wire layer worked end-to-end.
            assertNotNull(host.syncManager.currentDeviceId)
            assertNotNull(host.syncManager.currentOwnerId)

            // The relay registered the host's device public key in
            // KnownDevicesStore, so subsequent JWT-authed calls from this device
            // will verify.
            val ownerId = host.syncManager.currentOwnerId!!
            val deviceId = host.syncManager.currentDeviceId
            assertTrue(
                deviceId in system.knownDevices.deviceIds(ownerId),
                "Host's deviceId=$deviceId should be in relay's KnownDevicesStore for owner=$ownerId"
            )
        } finally {
            system.shutdown()
        }
    }
}
