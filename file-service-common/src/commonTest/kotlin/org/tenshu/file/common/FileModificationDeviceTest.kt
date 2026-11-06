package org.tenshu.file.common

import org.tenshu.core.common.TestProxy
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.FileModification
import org.tenshu.core.schema.propertyRef
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 2 of `docs/proposals/core-reference-property.md`: the `FileModification.Device`
 * reference value property resolves the authoring [Device] live from the row's plain
 * `deviceId` field, over the real `Device` + `FileModification` types and a real
 * `Database` (no synthetic fixtures — the proof the primitive works end to end).
 *
 * Uses both `fileServiceCommonModule` (registers the reference property) and
 * `identityServiceCommonModule` (registers `Device` so it can be saved + deserialised) —
 * the cross-service shape the feature exists for.
 */
class FileModificationDeviceTest {

    private val deviceProperty = propertyRef("${FileModification.ID}/device")

    private fun proxy() = TestProxy().apply {
        schemaRegistry.registerConfig(fileServiceCommonModule)
        schemaRegistry.registerConfig(identityServiceCommonModule)
    }

    @Test
    fun resolvesAuthoringDeviceByDeviceId() {
        val p = proxy()
        p.database.saveData(
            Device.create(
                context = p.tContext,
                deviceId = "device-1",
                name = "iPhone",
                publicKey = byteArrayOf(),
            )
        )
        val modification = CreatedFileModification.create(
            context = p.tContext,
            fileId = "/docs/a.txt",
            timestamp = 1000L,
            deviceId = "device-1",
        )

        val resolved = p.executionContext.resolveProperty(modification, deviceProperty)

        assertEquals(
            "iPhone",
            (resolved as Device).name.get(),
            "should dereference the authoring Device's name by deviceId",
        )
    }

    @Test
    fun resolvesToNullWhenDeviceUnknownLocally() {
        val p = proxy()
        // No Device saved for "ghost" — the UI falls back to the raw deviceId.
        val modification = CreatedFileModification.create(
            context = p.tContext,
            fileId = "/docs/a.txt",
            timestamp = 1000L,
            deviceId = "ghost",
        )

        assertNull(p.executionContext.resolveProperty(modification, deviceProperty))
    }
}
