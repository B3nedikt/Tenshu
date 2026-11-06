package org.tenshu.storage.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.id
import org.tenshu.storage.InsufficientStorageError
import org.tenshu.storage.NoStorageError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Tier-2 typed errors cross the proxy↔client wire (Drive surfaces them), so
 * they must survive a binary round-trip with fields intact ([wire-registration-gap]).
 */
class StorageErrorRoundTripTest {

    private fun registry() =
        createCoreTestRegistry().apply { registerConfig(storageServiceCommonModule) }

    @Test
    fun noStorageError_survivesWireRoundTrip() {
        val registry = registry()
        val tContext = createTestContext(registry)
        val serializer = BinarySerializer(registry)
        val parser = BinaryParser(registry, registry)

        val error = NoStorageError.create(tContext)
        val parsed = parser.parse<NoStorageError>(serializer.serialize(error))

        assertEquals(NoStorageError.ID, parsed.id, "the typed code must survive the round-trip")
    }

    @Test
    fun insufficientStorageError_survivesWireRoundTrip() {
        val registry = registry()
        val tContext = createTestContext(registry)
        val serializer = BinarySerializer(registry)
        val parser = BinaryParser(registry, registry)

        val error = InsufficientStorageError.create(
            context = tContext,
            requiredBytes = 5_000_000_000L, // > Int.MAX
            availableBytes = 1_234L,
        )
        val parsed = parser.parse<InsufficientStorageError>(serializer.serialize(error))

        assertEquals(5_000_000_000L, parsed.requiredBytes.get())
        assertEquals(1_234L, parsed.availableBytes.get())
    }
}
