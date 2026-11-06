package app.tenshu.mapregion.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the render-hop drain loop ([stageRegionBundle]) over an in-memory [RegionBundleStager]:
 * a multi-window bundle assembles + commits, a re-drain short-circuits, and a mid-stream failure /
 * premature EOF leaves nothing committed.
 */
class RegionBundleStagingTest {

    @Test
    fun drainsAcrossWindowsThenCommits() = runTest {
        val bundle = ByteArray(700) { (it % 251).toByte() }
        val stager = FakeStager()
        var reads = 0

        val path = stageRegionBundle(stager, "de", bundle.size.toLong(), window = 256) { off, len ->
            reads++
            windowOf(bundle, off, len)
        }

        assertEquals(stager.stagedPath("de"), path)
        assertContentEquals(bundle, stager.committed["de"])
        assertEquals(3, reads) // 256 + 256 + 188
    }

    @Test
    fun reDrainShortCircuitsAnAlreadyStagedBundle() = runTest {
        val bundle = ByteArray(300) { it.toByte() }
        val stager = FakeStager()
        stageRegionBundle(stager, "de", bundle.size.toLong(), window = 256) { off, len ->
            windowOf(bundle, off, len)
        }

        var reads = 0
        val path = stageRegionBundle(stager, "de", bundle.size.toLong(), window = 256) { off, len ->
            reads++; windowOf(bundle, off, len)
        }

        assertEquals(stager.stagedPath("de"), path)
        assertEquals(0, reads) // isStaged short-circuit — no bytes re-read
    }

    @Test
    fun aReadFailureMidStreamDiscardsAndReturnsNull() = runTest {
        val stager = FakeStager()
        val path = stageRegionBundle(stager, "de", byteSize = 700, window = 256) { off, _ ->
            if (off == 0L) ByteArray(256) else null // second window fails
        }

        assertNull(path)
        assertNull(stager.committed["de"])
        assertTrue(stager.discarded.contains("de"))
        assertFalse(stager.isStaged("de", 700))
    }

    @Test
    fun aPrematureEofDiscardsAndReturnsNull() = runTest {
        val stager = FakeStager()
        val path = stageRegionBundle(stager, "de", byteSize = 700, window = 256) { off, len ->
            if (off >= 300) ByteArray(0) else windowOf(ByteArray(300), off, len) // short bundle
        }

        assertNull(path)
        assertNull(stager.committed["de"])
    }

    @Test
    fun zeroSizeIsNull() = runTest {
        val path = stageRegionBundle(FakeStager(), "de", byteSize = 0) { _, _ -> ByteArray(0) }
        assertNull(path)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun windowOf(all: ByteArray, offset: Long, length: Int): ByteArray {
        if (offset >= all.size) return ByteArray(0)
        val end = minOf(all.size.toLong(), offset + length).toInt()
        return all.copyOfRange(offset.toInt(), end)
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray?) {
        assertTrue(actual != null && expected.contentEquals(actual), "staged bytes differ")
    }

    private class FakeStager : RegionBundleStager {
        private val temp = mutableMapOf<String, MutableList<Byte>>()
        val committed = mutableMapOf<String, ByteArray>()
        val discarded = mutableListOf<String>()

        override fun stagedPath(regionId: String) = "/staged/$regionId.pmtiles"
        override fun isStaged(regionId: String, expectedSize: Long) =
            committed[regionId]?.size?.toLong() == expectedSize

        override fun writeChunk(regionId: String, offset: Long, bytes: ByteArray) {
            val buf = temp.getOrPut(regionId) { mutableListOf() }
            if (offset == 0L) buf.clear()
            bytes.forEach { buf.add(it) }
        }

        override fun commit(regionId: String) {
            committed[regionId] = temp[regionId]?.toByteArray() ?: ByteArray(0)
            temp.remove(regionId)
        }

        override fun discard(regionId: String) {
            temp.remove(regionId)
            discarded.add(regionId)
        }
    }
}
