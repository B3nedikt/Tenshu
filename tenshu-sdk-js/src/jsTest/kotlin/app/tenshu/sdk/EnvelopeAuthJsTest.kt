package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.EnvelopeAuth
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.TimeSource

/**
 * `sync-envelope-authentication.md` on the **browser** runtime — the one target where
 * `:sync-service-common`'s own commonTest does not run (its JS test compilation is disabled).
 *
 * ⚠⚠ The vectors are copied from `EnvelopeAuthTest` (JVM/iOS) and were cross-checked against an
 * independent Python implementation: JS must produce the same bytes or a browser peer would reject
 * every tombstone a phone sends. The benchmark is the measurement §7 owed: bulk tombstone verification
 * in pure-JS HMAC/SHA-256, and a forged block's trial cost against retired keys (@noble/ciphers).
 */
class EnvelopeAuthJsTest {

    private suspend fun context() = TenshuSdk.awaitReady().await().let { JsRuntime.tContext }
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun vectorsMatchTheOtherPlatforms() = runTest {
        val ctx = context()
        val hlc = HybridLogicalClock.create(ctx, 1_700_000_000_000L, 7L, "device-a")
        assertEquals(
            "8bc94b1adbf824939c99c0d75c5acfc876245da041e209b7d4b3756dc0f6702e",
            EnvelopeAuth.syncBlockAad("owner-1", "block-1", hlc, "wire-id-1").hex(),
        )
        assertEquals(
            "579f8ddd52d11b1d4ebd76614924ce4492d3317d4c586bddb0ba07bc47e72eac",
            EnvelopeAuth.syncBlockAad("owner-1", "block-1", hlc, null).hex(),
        )
        val tomb = DeleteBlock.create(ctx, "block-1", "owner-1", hlc, "wire-id-1", "tenshu/entity")
        assertEquals(
            "aff1755618230ca2d2a0633537abb0efb82c218a1d2fe8ac0c5948845266138a",
            EnvelopeAuth.tombstoneTag(tomb, key).hex(),
        )
        val revoke = DeleteBlock.create(ctx, "block-1", "owner-1", hlc, "wire-id-1", "tenshu/identity/device", "device-b")
        assertEquals(
            "f36b571d9ad561ca39a32bc8f12b588eb9a156f1026581d160d4536e4ca28c6f",
            EnvelopeAuth.tombstoneTag(revoke, key).hex(),
        )
    }

    @Test
    fun boundBlockRoundTripsOnTheNobleBackend() = runTest {
        val ctx = context()
        val crypto = createDefaultSyncCryptoManager()
        val hlc = HybridLogicalClock.create(ctx, 1_700_000_000_000L, 0L, "device-a")
        val block = EnvelopeAuth.sealSyncBlock(crypto, ctx, "b", "o", hlc, "w", "payload".encodeToByteArray(), key)
        assertContentEquals("payload".encodeToByteArray(), EnvelopeAuth.openSyncBlock(crypto, block, key))
        val restamped = SyncBlock.create(
            ctx, "b", "o", HybridLogicalClock.create(ctx, 1_800_000_000_000L, 0L, "device-a"),
            block.encryptedPayload.get(), "w",
        )
        assertNull(EnvelopeAuth.openSyncBlock(crypto, restamped, key))
    }

    @Test
    fun benchmark() = runTest {
        val ctx = context()
        val tagged = (0 until 10_000).map { i ->
            EnvelopeAuth.tagTombstone(
                ctx,
                DeleteBlock.create(
                    ctx, "block-$i", "owner-0123456789",
                    HybridLogicalClock.create(ctx, 1_700_000_000_000L + i, 0L, "device-a"),
                    "0123456789abcdef0123456789abcdef", "tenshu/entity",
                ),
                key,
            )
        }
        val mark = TimeSource.Monotonic.markNow()
        val ok = tagged.count { EnvelopeAuth.verifyTombstone(it, key) }
        val elapsed = mark.elapsedNow()
        assertEquals(10_000, ok)
        println("BENCH envelope-auth[js]: verified 10000 tombstone tags in $elapsed (${elapsed / 10_000} each)")

        val crypto = createDefaultSyncCryptoManager()
        val hlc = HybridLogicalClock.create(ctx, 1_700_000_000_000L, 0L, "device-a")
        val genuine = EnvelopeAuth.sealSyncBlock(crypto, ctx, "b", "o", hlc, "w", ByteArray(5_000) { it.toByte() }, key)
        val forged = SyncBlock.create(
            ctx, "b", "o", HybridLogicalClock.create(ctx, 1_800_000_000_000L, 0L, "device-a"),
            genuine.encryptedPayload.get(), "w",
        )
        for (retired in listOf(0, 4, 16)) {
            val keys = listOf(key) + (1..retired).map { n -> ByteArray(32) { (it + n).toByte() } }
            val m = TimeSource.Monotonic.markNow()
            repeat(50) { keys.forEach { k -> EnvelopeAuth.openSyncBlock(crypto, forged, k) } }
            println("BENCH envelope-auth[js]: forged 5 KB block vs $retired retired key(s): ${m.elapsedNow() / 50} per block")
        }
    }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
