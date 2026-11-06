package org.tenshu.file.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Locks in the wire-unit invariant for the `lastModifiedAt` value property:
 * the stored value is epoch-millis, round-trips unchanged, and reads back via the
 * [File.LastModifiedAt] handle as exactly the input (no `/100` / `*100` rescale).
 */
class FileTimestampRoundTripTest {

    @Test
    fun `lastModifiedAt survives wire round-trip and matches input`() {
        val registry = createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }
        val tContext = createTestContext(registry)
        val serializer = BinarySerializer(registry)
        val parser = BinaryParser(registry, registry)

        val fixedNow = 1_704_067_200_000L  // 2024-01-01T00:00:00Z

        val file = Entity.create<File>(
            context = tContext,
            composite = File.create(tContext, "/test.txt"),
        ) {
            set(File.Size, 0L)
            set(File.LastModifiedAt, fixedNow)
        }

        val bytes = serializer.serialize(file)

        @Suppress("UNCHECKED_CAST")
        val parsed = parser.parse<Entity<File>>(bytes)

        val lastModifiedAt =
            assertNotNull(parsed.get(File.LastModifiedAt)?.get(), "lastModifiedAt must survive the round-trip")
        assertEquals(
            fixedNow, lastModifiedAt,
            "Stored value must be epoch-millis, unchanged across the round-trip."
        )
    }

    @Test
    fun `createdAt survives wire round-trip and matches input`() {
        val registry = createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }
        val tContext = createTestContext(registry)
        val serializer = BinarySerializer(registry)
        val parser = BinaryParser(registry, registry)

        val fixedNow = 1_704_067_200_000L  // 2024-01-01T00:00:00Z

        val file = Entity.create<File>(
            context = tContext,
            composite = File.create(tContext, "/created.txt"),
        ) {
            set(File.Size, 0L)
            set(File.CreatedAt, fixedNow)
        }

        val bytes = serializer.serialize(file)

        @Suppress("UNCHECKED_CAST")
        val parsed = parser.parse<Entity<File>>(bytes)

        val createdAt =
            assertNotNull(parsed.get(File.CreatedAt)?.get(), "createdAt must survive the round-trip")
        assertEquals(
            fixedNow, createdAt,
            "Stored value must be epoch-millis, unchanged across the round-trip."
        )
    }
}
