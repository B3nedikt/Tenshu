package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E4-2 (`core-schema-evolution.md` §8.3): this device publishes what it runs, so
 * the roster can take the MIN.
 *
 * The behaviour worth pinning is **write-if-changed**. The row is
 * `Syncable(Eager)` and a device's vector is a compile-time constant for the
 * process, so an unconditional publish would emit one block per launch per
 * device, forever, about a fact that only moves at upgrade.
 */
class DeviceSchemaVersionPublisherTest {

    private val deviceId = "device-under-test"

    private fun TestProxy.publisher() = DeviceSchemaVersionPublisher(executionContext)

    private fun TestProxy.rowId() = DataId.StringDataId.create(
        context = tContext,
        type = DeviceSchemaVersion.ID,
        keyValue = deviceId,
    )

    private fun TestProxy.row(): DeviceSchemaVersion? =
        executionContext.database.getData(rowId()) as? DeviceSchemaVersion

    private fun TestProxy.recordedVector(row: DeviceSchemaVersion): VersionVector {
        val registry = schemaRegistry as SchemaRegistry
        return BinaryParser(registry, registry).parse(row.versionsBytes.get()) as VersionVector
    }

    @Test
    fun theFirstPublishWritesThisDevicesVector() = runTest {
        val proxy = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }

        assertNull(proxy.row(), "nothing recorded before the first publish")
        assertTrue(proxy.publisher().publishIfChanged(deviceId), "the first publish must write")

        val row = assertNotNull(proxy.row(), "the row must exist after publishing")
        assertEquals(deviceId, row.deviceId.get())
        assertEquals(
            TENSHU_STORAGE_FORMAT_VERSION,
            proxy.recordedVector(row).coreVersion.get().toInt(),
            "the published vector is what THIS build runs",
        )
    }

    @Test
    fun republishingAnUnchangedVectorWritesNothing() = runTest {
        val proxy = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }

        assertTrue(proxy.publisher().publishIfChanged(deviceId))

        // ⚠ The assertion that matters: a second start on the same build is a
        // no-op. Without it every launch emits a Syncable block per device.
        assertFalse(
            proxy.publisher().publishIfChanged(deviceId),
            "an unchanged vector must not write — the row is Syncable and this runs every start",
        )
    }

    @Test
    fun anEmptyDeviceIdPublishesNothing() = runTest {
        val proxy = TestProxy(createSyntheticTestRegistry(), coroutineScope = this).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }

        // Locked or unpaired: no realm membership ⇒ no device id. Such a device
        // must not self-assert a roster row (the `reconcileOwnDeviceRow` rule).
        assertFalse(proxy.publisher().publishIfChanged(""), "no membership ⇒ no roster row")
        assertNull(proxy.row())
    }
}
