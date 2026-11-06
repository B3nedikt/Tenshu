package org.tenshu.sync.common

import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wire + registration coverage for the compaction receipt (§8.3, E4). Same
 * reason as its two siblings: a row that fails to materialise on the receiving
 * side is invisible, and here invisibility means *every* device believes nobody
 * has compacted — so they all do, repeatedly. The dedup signal failing open is
 * the one way this row can misbehave.
 */
class CompactionReceiptSerializationTest {

    private val registry = createSyntheticTestRegistry().also {
        it.registerConfig(syncServiceCommonModule)
    }
    private val context = createTestContext(registry)

    @Test
    fun compactionReceipt_roundTripsThroughTheWire() {
        val original = CompactionReceipt.create(
            context = context,
            serviceId = "tenshu/file/service",
            compactedToVersion = 5,
            compactedBy = "device-a",
        )

        val parsed = BinaryParser(registry, registry)
            .parse(BinarySerializer().serialize(original)) as CompactionReceipt

        assertEquals("tenshu/file/service", parsed.receiptId.get(), "keyed by service, not device")
        assertEquals("tenshu/file/service", parsed.serviceId.get())
        assertEquals(5, parsed.compactedToVersion.get().toInt())
        assertEquals("device-a", parsed.compactedBy.get())
    }
}
