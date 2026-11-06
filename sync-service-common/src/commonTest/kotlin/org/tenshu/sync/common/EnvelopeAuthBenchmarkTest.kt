package org.tenshu.sync.common

import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * `sync-envelope-authentication.md` §7: the measurements the design owed before merge. Prints, and
 * asserts only correctness — a timing threshold would flake on a loaded gate. Runs on every target
 * (JVM, JS, iOS), which is the point: the JS number is the one that could have been a problem.
 *
 *  1. Bulk tombstone ingest: verify [TOMBSTONES] tags (a fresh device pulling its tombstone history).
 *  2. A forged block against N retired keys (the trial-open cost an attacker can impose, D-e).
 */
class EnvelopeAuthBenchmarkTest {

    private val context = createTestContext(
        createSyntheticTestRegistry().also { it.registerConfig(syncServiceCommonModule) }
    )
    private val key = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun bulkTombstoneVerification() {
        val tagged = (0 until TOMBSTONES).map { i ->
            EnvelopeAuth.tagTombstone(
                context,
                DeleteBlock.create(
                    context, "block-$i", "owner-0123456789", HybridLogicalClock.create(context, 1_700_000_000_000L + i, 0L, "device-a"),
                    "0123456789abcdef0123456789abcdef", "tenshu/entity",
                ),
                key,
            )
        }
        EnvelopeAuth.verifyTombstone(tagged.first(), key) // warm the K_tomb cache, as a real ingest would
        val mark = TimeSource.Monotonic.markNow()
        val ok = tagged.count { EnvelopeAuth.verifyTombstone(it, key) }
        val elapsed = mark.elapsedNow()
        assertEquals(TOMBSTONES, ok)
        println("BENCH envelope-auth: verified $TOMBSTONES tombstone tags in $elapsed (${elapsed / TOMBSTONES} each)")
    }

    @Test
    fun forgedBlockAgainstRetiredKeys() {
        val crypto = createDefaultSyncCryptoManager()
        val hlc = HybridLogicalClock.create(context, 1_700_000_000_000L, 0L, "device-a")
        val payload = ByteArray(5_000) { it.toByte() } // ~ the mean block envelope
        val genuine = EnvelopeAuth.sealSyncBlock(crypto, context, "b", "owner", hlc, "wire", payload, key)
        val forged = SyncBlock.create(
            context, "b", "owner", HybridLogicalClock.create(context, 1_800_000_000_000L, 0L, "device-a"),
            genuine.encryptedPayload.get(), "wire",
        )
        for (retired in listOf(0, 4, 16)) {
            val candidates = listOf(key) + (1..retired).map { n -> ByteArray(32) { (it + n).toByte() } }
            val mark = TimeSource.Monotonic.markNow()
            repeat(FORGED_BLOCKS) { candidates.forEach { k -> EnvelopeAuth.openSyncBlock(crypto, forged, k) } }
            val elapsed = mark.elapsedNow()
            println("BENCH envelope-auth: forged 5 KB block vs ${retired} retired key(s): ${elapsed / FORGED_BLOCKS} per block")
        }
        assertEquals(null, EnvelopeAuth.openSyncBlock(crypto, forged, key))
    }

    private companion object {
        const val TOMBSTONES = 10_000
        const val FORGED_BLOCKS = 200
    }
}
