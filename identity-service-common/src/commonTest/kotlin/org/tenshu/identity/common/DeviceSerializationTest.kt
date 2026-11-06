package org.tenshu.identity.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import org.tenshu.identity.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(identityServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun device_roundTripsAdmittedAtEpoch() {
        val original = Device.create(
            context = context,
            deviceId = "device-1",
            name = "Test Device",
            publicKey = byteArrayOf(1, 2, 3, 4),
            isAuthorized = true,
            admittedAtEpoch = 7L
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as Device

        assertEquals(original.deviceId.get(), parsed.deviceId.get())
        assertEquals(original.name.get(), parsed.name.get())
        assertEquals(original.publicKey.get().toList(), parsed.publicKey.get().toList())
        assertEquals(original.isAuthorized.get(), parsed.isAuthorized.get())
        assertEquals(7L, parsed.admittedAtEpoch.get())
    }

    @Test
    fun device_schemaCarriesSyncableTrait() {
        // Phase 1 makes Device a Syncable entity so the platform's sync machinery picks it up.
        assertTrue(
            Device.Schema.hasTrait(Syncable.ID),
            "Device.Schema should declare the Syncable trait after Phase 1"
        )
    }
}
