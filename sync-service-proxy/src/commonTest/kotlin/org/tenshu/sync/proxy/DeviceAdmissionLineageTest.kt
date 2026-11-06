package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `Device.admittedByDeviceId` (`delegated-device-admission-authority.md` §5) —
 * the admission-lineage field the P5 subtree revoke walks. New rows carry it;
 * omitting it (every pre-P1 caller) yields the empty "root / unknown" marker.
 */
class DeviceAdmissionLineageTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
    }

    private fun saveAndReload(device: Device): Device {
        testProxy.executionContext.saveData(device)
        val dataId = DataId.StringDataId.create(testProxy.tContext, Device.ID, device.deviceId.get())
        return assertNotNull(testProxy.database.getData(dataId) as? Device)
    }

    @Test
    fun admittedByDeviceId_roundTripsThroughStore() {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = "device-y",
            name = "Tablet",
            publicKey = byteArrayOf(0x01),
            admittedByDeviceId = "device-x",
        )
        assertEquals("device-x", saveAndReload(device).admittedByDeviceId?.get())
    }

    @Test
    fun omitted_defaultsToEmptyRootMarker() {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = "device-genesis",
            name = "Laptop",
            publicKey = byteArrayOf(0x02),
        )
        assertEquals("", saveAndReload(device).admittedByDeviceId?.get())
    }
}
