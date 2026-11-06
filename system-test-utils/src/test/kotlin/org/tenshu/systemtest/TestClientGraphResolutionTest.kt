package org.tenshu.systemtest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The Koin graph for one [TestClient] resolves end-to-end. This test catches
 * binding wiring regressions before any flow test runs — if `TenshuClient` or
 * `Database` can't be constructed, every SystemTest fails for the same reason
 * and the failure mode is opaque.
 *
 * `executionContext` resolution forces `platformProxyInitialization` to run,
 * which hits initFileService / initIdentityService / initSyncService.
 */
class TestClientGraphResolutionTest {

    @Test fun client_resolvesFullKoinGraph() = runTest {
        val system = TestSystem.builder().build()
        try {
            val device = system.client("device-1", TestClientRole.RealmHost)

            assertNotNull(device.tenshuClient)
            assertNotNull(device.database)
            assertNotNull(device.proxyGateway)
            assertNotNull(device.syncManager)
            assertNotNull(device.executionContext)
        } finally {
            system.shutdown()
        }
    }
}
