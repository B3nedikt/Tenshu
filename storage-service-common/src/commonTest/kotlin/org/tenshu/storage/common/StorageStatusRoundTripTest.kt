package org.tenshu.storage.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.storage.StorageStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the wire-registration invariant ([wire-registration-gap]): a
 * [StorageStatus] crosses the Drive↔Control IPC boundary in Phase 2, so it must
 * survive a binary serialize→parse round-trip with its fields intact — including
 * a `limitBytes` beyond the 32-bit range (a 50 GiB limit overflows `Int`).
 */
class StorageStatusRoundTripTest {

    @Test
    fun storageStatus_survivesWireRoundTrip() {
        val registry = createCoreTestRegistry().apply { registerConfig(storageServiceCommonModule) }
        val tContext = createTestContext(registry)
        val serializer = BinarySerializer(registry)
        val parser = BinaryParser(registry, registry)

        val usedBytes = 1_500_000_000L
        val limitBytes = 53_687_091_200L // 50 GiB — beyond Int.MAX_VALUE

        val status = StorageStatus.create(
            context = tContext,
            hasStorage = true,
            usedBytes = usedBytes,
            limitBytes = limitBytes,
        )

        val bytes = serializer.serialize(status)
        val parsed = parser.parse<StorageStatus>(bytes)

        assertEquals(true, parsed.hasStorage.get(), "hasStorage must survive the round-trip")
        assertEquals(usedBytes, parsed.usedBytes.get(), "usedBytes must survive unchanged")
        assertEquals(limitBytes, parsed.limitBytes.get(), "limitBytes must survive (Long, beyond Int range)")
        assertEquals(StorageStatus.SINGLETON_KEY, parsed.key.get(), "key must survive the round-trip")
    }
}
