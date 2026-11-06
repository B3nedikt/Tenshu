package org.tenshu.sync.common

import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wire + registration coverage for the E4 per-device version row
 * (`core-schema-evolution.md` §8.3), mirroring
 * [RealmSchemaEpochSerializationTest] for the same reason: in-process tests
 * happily pass around a row built by `create(...)` even when its DTO or trait
 * registrations are missing, and only a real serialize→parse surfaces that.
 *
 * Here the consequence would be specific and bad: a peer's statement of what it
 * runs would fail to materialise, the roster would read that device as *silent*,
 * and the floor would never advance — a stuck floor is the quiet failure mode,
 * since nothing breaks and history simply accumulates forever.
 */
class DeviceSchemaVersionSerializationTest {

    private val registry = createSyntheticTestRegistry().also {
        it.registerConfig(syncServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun deviceSchemaVersion_roundTripsThroughTheWire() {
        val original = DeviceSchemaVersion.create(
            context = context,
            deviceId = "device-a",
            versions = VersionVector.create(
                context = context,
                coreVersion = 18,
                services = listOf(
                    ServiceVersion.create(context, "tenshu/file/service", 5),
                    ServiceVersion.create(context, "tenshu/sync/service", 2),
                ),
            ),
        )

        val parsed = parser.parse(serializer.serialize(original)) as DeviceSchemaVersion

        assertEquals("device-a", parsed.deviceId.get())

        // The vector must survive as a vector, not merely as opaque bytes — the
        // roster compares versions, so a row that round-trips its bytes but not
        // its meaning is still useless.
        val decoded = parser.parse(parsed.versionsBytes.get()) as VersionVector
        assertEquals(18, decoded.coreVersion.get().toInt())
        assertEquals(
            mapOf("tenshu/file/service" to 5, "tenshu/sync/service" to 2),
            decoded.toMap(),
        )
    }
}
