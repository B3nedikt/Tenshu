package org.tenshu.inference.proxy.catalog

import org.tenshu.core.common.proxy.InMemoryFileDatabase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader a Core ML tower's archive goes through on its way to `MLModel.compileModel`
 * (`ios-on-device-inference.md` §3.4).
 *
 * ⚠ The archives here are built by [testTar], byte by byte, rather than by a tar library: the
 * hostile cases are exactly the ones a library refuses to write.
 */
class TarReaderTest {

    private val files = InMemoryFileDatabase()

    @Test
    fun aPackageRoundTrips() {
        // Larger than one copy window, and not a whole number of blocks.
        val weights = ByteArray(TarReader.CHUNK * 2 + 777) { (it * 31).toByte() }
        val manifest: ByteArray = "{\"fileFormatVersion\":\"1.0.0\"}".encodeToByteArray()
        files.writeRawByteArray(
            "in.tar",
            testTar(
                TarTestEntry("vision.mlpackage", type = '5'),
                TarTestEntry("vision.mlpackage/Data", type = '5'),
                TarTestEntry("vision.mlpackage/Data/com.apple.CoreML/weights/weight.bin", weights),
                TarTestEntry("vision.mlpackage/Data/com.apple.CoreML/empty", ByteArray(0)),
                TarTestEntry("vision.mlpackage/Manifest.json", manifest),
            ),
        )

        val written: List<TarEntry> = TarReader.unpack(files, "in.tar", "out/")

        assertEquals(
            listOf(
                TarEntry("vision.mlpackage/Data/com.apple.CoreML/weights/weight.bin", weights.size.toLong()),
                TarEntry("vision.mlpackage/Data/com.apple.CoreML/empty", 0L),
                TarEntry("vision.mlpackage/Manifest.json", manifest.size.toLong()),
            ),
            written,
        )
        assertContentEquals(weights, read("out/vision.mlpackage/Data/com.apple.CoreML/weights/weight.bin"))
        assertContentEquals(ByteArray(0), read("out/vision.mlpackage/Data/com.apple.CoreML/empty"))
        assertContentEquals(manifest, read("out/vision.mlpackage/Manifest.json"))
    }

    @Test
    fun aUstarPrefixIsPartOfTheName() {
        files.writeRawByteArray("in.tar", testTar(TarTestEntry("weight.bin", byteArrayOf(1, 2, 3), prefix = "a.mlpackage/Data")))

        TarReader.unpack(files, "in.tar", "out")

        assertContentEquals(byteArrayOf(1, 2, 3), read("out/a.mlpackage/Data/weight.bin"))
    }

    @Test
    fun anEntryReplacesALongerFileLeftByAnEarlierUnpack() {
        files.writeRawByteArray("out/a/file", ByteArray(4096) { 9 })
        files.writeRawByteArray("in.tar", testTar(TarTestEntry("a/file", byteArrayOf(1, 2, 3))))

        TarReader.unpack(files, "in.tar", "out")

        assertContentEquals(byteArrayOf(1, 2, 3), read("out/a/file"))
    }

    @Test
    fun aParentSegmentIsRefused() {
        files.writeRawByteArray("in.tar", testTar(TarTestEntry("a.mlpackage/../../escaped", byteArrayOf(1))))

        val failure = assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }

        assertTrue("'..'" in failure.message.orEmpty(), failure.message)
        assertNull(files.readBytes("escaped"))
        assertNull(files.readBytes("out/a.mlpackage/../../escaped"))
    }

    @Test
    fun aParentSegmentInTheUstarPrefixIsRefused() {
        files.writeRawByteArray("in.tar", testTar(TarTestEntry("escaped", byteArrayOf(1), prefix = "..")))

        assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }
    }

    @Test
    fun anAbsolutePathIsRefused() {
        files.writeRawByteArray("in.tar", testTar(TarTestEntry("/etc/escaped", byteArrayOf(1))))

        val failure = assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }

        assertTrue("absolute" in failure.message.orEmpty(), failure.message)
        assertNull(files.readBytes("/etc/escaped"))
        assertNull(files.readBytes("out/etc/escaped"))
    }

    @Test
    fun anArchiveCutInsideAnEntryIsRefused() {
        val whole: ByteArray = testTar(TarTestEntry("a/file", ByteArray(3000) { 7 }))
        files.writeRawByteArray("in.tar", whole.copyOf(TarReader.BLOCK + 1500))

        val failure = assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }

        assertTrue("truncated" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun anArchiveWithNoEndMarkerIsRefused() {
        // Every entry is whole; only the closing zero blocks are missing.
        val whole: ByteArray = testTar(TarTestEntry("a/file", ByteArray(512) { 7 }))
        files.writeRawByteArray("in.tar", whole.copyOf(TarReader.BLOCK * 2))

        val failure = assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }

        assertTrue("truncated" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun anArchiveCutInsideAHeaderIsRefused() {
        val whole: ByteArray = testTar(TarTestEntry("a/file", byteArrayOf(1)))
        files.writeRawByteArray("in.tar", whole.copyOf(200))

        assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }
    }

    @Test
    fun aLinkIsRefused() {
        files.writeRawByteArray("in.tar", testTar(TarTestEntry("a/link", type = '2')))

        val failure = assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }

        assertTrue("only regular files and directories" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aHeaderThatFailsItsChecksumIsRefused() {
        val archive: ByteArray = testTar(TarTestEntry("a/file", byteArrayOf(1)))
        archive[0] = 'b'.code.toByte()
        files.writeRawByteArray("in.tar", archive)

        val failure = assertFailsWith<TarFormatException> { TarReader.unpack(files, "in.tar", "out") }

        assertTrue("checksum" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aMissingArchiveIsRefused() {
        assertFailsWith<TarFormatException> { TarReader.unpack(files, "absent.tar", "out") }
    }

    private fun read(path: String): ByteArray? = files.readBytes(path)?.get()
}
