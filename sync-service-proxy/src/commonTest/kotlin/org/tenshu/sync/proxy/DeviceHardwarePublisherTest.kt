package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceHardware
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DeviceHardwarePublisher` — this device's hardware model, published by the one writer that knows
 * it (`DeviceHardware`'s KDoc). Pins the two guards that keep a synced row from becoming noise or a
 * claim made by a non-member: write-if-changed, and no row without this device's own `Device` row.
 */
class DeviceHardwarePublisherTest {

    private val deviceId = "device-under-test"

    private fun proxy(scope: kotlinx.coroutines.CoroutineScope) =
        TestProxy(createSyntheticTestRegistry(), coroutineScope = scope).also {
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }

    private fun TestProxy.publisher(hardware: String = "Google Pixel 8") =
        DeviceHardwarePublisher(executionContext) { hardware }

    private fun TestProxy.row(): DeviceHardware? = executionContext.database.getData(
        DataId.StringDataId.create(tContext, DeviceHardware.ID, deviceId)
    ) as? DeviceHardware

    private fun TestProxy.admitThisDevice() {
        executionContext.saveData(
            Device.create(tContext, deviceId = deviceId, name = "Work phone", publicKey = byteArrayOf(1))
        )
    }

    @Test
    fun aMemberPublishesItsHardwareModel() = runTest {
        val proxy = proxy(this).apply { admitThisDevice() }

        assertTrue(proxy.publisher().publishIfChanged(deviceId))

        val row = assertNotNull(proxy.row())
        assertEquals(deviceId, row.deviceId.get())
        assertEquals("Google Pixel 8", row.hardwareName.get())
    }

    @Test
    fun anUnchangedModelWritesNothing() = runTest {
        val proxy = proxy(this).apply { admitThisDevice() }
        assertTrue(proxy.publisher().publishIfChanged(deviceId))

        // ⚠ The row is Syncable and this runs on every start and every unlock.
        assertFalse(proxy.publisher().publishIfChanged(deviceId))
    }

    @Test
    fun aChangedModelIsRepublished() = runTest {
        val proxy = proxy(this).apply { admitThisDevice() }
        assertTrue(proxy.publisher("Mac OS X (x86_64)").publishIfChanged(deviceId))

        assertTrue(proxy.publisher("Mac OS X (aarch64)").publishIfChanged(deviceId))
        assertEquals("Mac OS X (aarch64)", proxy.row()?.hardwareName?.get())
    }

    /**
     * ⚠⚠ The membership guard. Without this device's own `Device` row the device is unpaired,
     * revoked, or locked out of its realm, and must not put a row of its own into the realm.
     */
    @Test
    fun aDeviceWithoutItsOwnDeviceRowPublishesNothing() = runTest {
        val proxy = proxy(this)

        assertFalse(proxy.publisher().publishIfChanged(deviceId))
        assertNull(proxy.row())
    }

    @Test
    fun anEmptyDeviceIdOrModelPublishesNothing() = runTest {
        val proxy = proxy(this).apply { admitThisDevice() }

        assertFalse(proxy.publisher().publishIfChanged(""))
        assertFalse(proxy.publisher("  ").publishIfChanged(deviceId))
        assertNull(proxy.row())
    }
}
