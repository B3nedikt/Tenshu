package app.tenshu.takeout.zip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ZIP reader (B1). Every case here is one the export a user actually picks can present.
 */
class ZipArchiveTest {

    private fun open(bytes: ByteArray, inflate: Inflate = neverInflate): ZipArchive? =
        ZipArchive.open(ByteArrayZipSource(bytes), inflate)

    @Test
    fun readsTheDirectoryAndThenOneEntrysBytes() {
        val zip = ZipBuilder()
            .add("Takeout/archive_browser.html", "<html>")
            .add("Takeout/Location History/Records.json", "{\"locations\":[]}")
            .build()

        val archive = open(zip)!!

        assertEquals(
            listOf("Takeout/archive_browser.html", "Takeout/Location History/Records.json"),
            archive.entries.map { it.name },
        )
        val records = archive.entries.last()
        assertEquals("{\"locations\":[]}", archive.read(records)!!.decodeToString())
    }

    @Test
    fun aLocalHeaderWithZeroedSizesStillReads() {
        // ⚠⚠ The case that decides the whole design: a streaming writer defers both sizes to a
        // data descriptor *after* the payload and writes zeros in the local header. Reading sizes
        // from there yields empty files, silently — so they come from the central directory.
        val zip = ZipBuilder()
            .add("Takeout/Timeline.json", "{\"semanticSegments\":[]}", zeroLocalSizes = true)
            .build()

        val archive = open(zip)!!

        assertEquals("{\"semanticSegments\":[]}", archive.read(archive.entries.single())!!.decodeToString())
    }

    @Test
    fun zip64SentinelsAreFollowedToTheRealRecords() {
        // ⚠ A photo export passes 65 535 entries long before it passes 4 GB, and both write the
        // same sentinels. Treating `0xFFFF` as a count reads 65 535 of 200 000 photos and calls
        // it success.
        val zip = ZipBuilder()
            .add("Takeout/Google Photos/a.jpg", "not really a jpeg")
            .add("Takeout/Google Photos/b.jpg", "nor this")
            .build(zip64 = true)

        val archive = open(zip)!!

        assertEquals(2, archive.entries.size)
        assertEquals("nor this", archive.read(archive.entries.last())!!.decodeToString())
    }

    @Test
    fun directoriesAreNotEntries() {
        val zip = ZipBuilder()
            .addDirectory("Takeout/")
            .add("Takeout/x.json", "{}")
            .build()

        assertEquals(listOf("Takeout/x.json"), open(zip)!!.entries.map { it.name })
    }

    @Test
    fun aFileThatIsNotAZipIsNullRatherThanACrash() {
        // ⚠ Untrusted input: the user picked this off a phone. A wrong pick is an answer, not a
        // stack trace.
        assertNull(open("just some text, definitely not a zip".encodeToByteArray()))
        assertNull(open(ByteArray(0)))
        assertNull(open(ByteArray(4) { 0xFF.toByte() }))
    }

    @Test
    fun aTruncatedArchiveKeepsWhatItCanRead() {
        val zip = ZipBuilder()
            .add("Takeout/a.json", "{}")
            .add("Takeout/b.json", "{}")
            .build()
        // Chop the tail of the last entry's bytes but keep the directory: the archive still opens.
        val archive = open(zip)!!
        val truncated = ZipArchive.open(
            object : ZipSource {
                override val size: Long get() = zip.size.toLong()
                override fun read(offset: Long, length: Int): ByteArray {
                    val full = ByteArrayZipSource(zip).read(offset, length)
                    // Simulate a source that cannot deliver one entry's payload in full.
                    return if (offset == archive.entries.first().localHeaderOffset + 30 + 15) {
                        full.copyOf(full.size / 2)
                    } else {
                        full
                    }
                }
            },
            neverInflate,
        )!!

        // The directory read fine, so the *other* entry is still recoverable — a partial export is
        // better than none (TB-D8 makes re-running it safe).
        assertEquals(2, truncated.entries.size)
        assertEquals("{}", truncated.read(truncated.entries.last())!!.decodeToString())
    }

    @Test
    fun anUnknownCompressionMethodReadsAsNullRatherThanGarbage() {
        val zip = ZipBuilder().add("Takeout/a.json", "{}").build()
        val archive = open(zip)!!
        val bogus = archive.entries.single().copy(method = 99)

        assertNull(archive.read(bogus))
    }

    @Test
    fun openingCostsTheDirectoryOnly_neverTheEntriesBytes() {
        // ⚠ The property that makes a 40 GB export openable at all: what `open` reads is bounded
        // by the tail window plus the directory, NOT by what the archive holds.
        val payload = 200_000
        val zip = ZipBuilder().add("Takeout/big.jpg", "x".repeat(payload)).build()
        var bytesRead = 0L
        val counting = object : ZipSource {
            private val inner = ByteArrayZipSource(zip)
            override val size: Long get() = inner.size
            override fun read(offset: Long, length: Int): ByteArray =
                inner.read(offset, length).also { bytesRead += it.size }
        }

        ZipArchive.open(counting, neverInflate)!!

        assertTrue(
            bytesRead < payload / 2,
            "opening read $bytesRead bytes of an archive holding $payload",
        )
    }
}
