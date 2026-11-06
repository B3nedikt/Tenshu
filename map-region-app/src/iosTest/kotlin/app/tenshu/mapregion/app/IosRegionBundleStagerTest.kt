@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.tenshu.mapregion.app

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Darwin twin of [RegionBundleStagingTest] — same drain loop, but over the *real* iOS
 * filesystem stager rather than an in-memory fake, so this is what proves the render hop actually
 * lands bytes on disk on iOS (the C3 gap: before this there was no `RegionBundleStager` actual for
 * the platform at all, so `renderableRegionPathFor` could only ever return null there).
 */
class IosRegionBundleStagerTest {

    private val dir = NSTemporaryDirectory() + "region-stager-test-" + Random.nextLong().toString(16)
    private val stager = IosRegionBundleStager(dir)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }

    @Test
    fun drainsAWholeBundleToDiskAndReturnsItsPath() = runTest {
        // Deliberately not a multiple of the window: the final short read is where an off-by-one
        // in the positional writes would show up.
        val bundle = ByteArray(70_000) { (it * 31 % 251).toByte() }

        val path = stageRegionBundle(
            stager = stager,
            regionId = "it-firenze",
            byteSize = bundle.size.toLong(),
            window = 4096,
        ) { offset, length ->
            bundle.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, bundle.size))
        }

        assertNotNull(path, "a complete drain resolves to a staged path")
        assertEquals("$dir/it-firenze.pmtiles", path)
        assertContentEquals(bundle, readFile(path), "the staged file is the bundle, byte for byte")
        assertTrue(stager.isStaged("it-firenze", bundle.size.toLong()))
        assertNull(readFile("$dir/it-firenze.pmtiles.part"), "the temp is promoted, not left behind")
    }

    @Test
    fun aSecondDrainShortCircuitsOnTheAlreadyStagedBundle() = runTest {
        val bundle = ByteArray(8192) { it.toByte() }
        val read: suspend (Long, Int) -> ByteArray? = { offset, length ->
            bundle.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, bundle.size))
        }
        stageRegionBundle(stager, "de-berlin", bundle.size.toLong(), 4096, read)

        var reads = 0
        val path = stageRegionBundle(stager, "de-berlin", bundle.size.toLong(), 4096) { o, l ->
            reads++
            read(o, l)
        }

        assertEquals("$dir/de-berlin.pmtiles", path)
        assertEquals(0, reads, "an already-complete bundle is not re-fetched")
    }

    @Test
    fun aFailedReadDiscardsThePartialAndStagesNothing() = runTest {
        val path = stageRegionBundle(stager, "pt-lisboa", byteSize = 20_000, window = 4096) { offset, _ ->
            if (offset == 0L) ByteArray(4096) else null
        }

        assertNull(path, "a read failure leaves nothing staged")
        assertNull(readFile("$dir/pt-lisboa.pmtiles.part"), "the partial temp is discarded")
        assertFalse(stager.isStaged("pt-lisboa", 20_000))
    }

    @Test
    fun isStagedRejectsAShortFileEvenThoughItExists() = runTest {
        stager.writeChunk("es-madrid", offset = 0, bytes = ByteArray(100))
        stager.commit("es-madrid")

        assertTrue(stager.isStaged("es-madrid", 100))
        assertFalse(stager.isStaged("es-madrid", 200), "size, not mere existence, gates the drain")
    }

    private fun readFile(path: String): ByteArray? =
        NSData.dataWithContentsOfFile(path)?.let { data ->
            val size = data.length.toInt()
            if (size == 0) return@let ByteArray(0)
            ByteArray(size).also { out ->
                out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
            }
        }
}
