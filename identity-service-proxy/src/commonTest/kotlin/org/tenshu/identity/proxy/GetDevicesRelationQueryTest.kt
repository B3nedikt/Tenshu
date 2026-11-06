package org.tenshu.identity.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.identity.Device
import org.tenshu.identity.GetDevices
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After the device-management migration (docs/adr/0007-sync-device-management.md Phase 2),
 * `GetDevices` is a local-DB relation query against
 * `IdentityService.Relations.DEVICES` — no relay call. The impl returns an
 * `ArrayDatabaseReference` that the client-side `getAll()` chain materializes lazily
 * (the same pattern as `NotificationService.notifications`).
 */
class GetDevicesRelationQueryTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = createIdentityProxy()
    }

    @Test
    fun resolvesToArrayDatabaseReference_forDevicesRelation() = runTest {
        val result = testProxy.executionContext.executeAsync<Data>(
            operationId = GetDevices.ID,
            caller = null,
            params = null
        )

        val arrayRef = result as? ArrayDatabaseReference
        assertTrue(
            arrayRef != null,
            "Result must be an ArrayDatabaseReference; got $result"
        )
        assertEquals(Device.ID, arrayRef.elementSchemaId)
    }
}
