package app.tenshu.fit.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.Foundation.NSUUID
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.z_stream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips a real zip through [IosArchiveReader]. This is a hand-written binary parser standing
 * where `java.util.zip` stands on the JVM, so it gets a test that builds an actual archive —
 * container *and* DEFLATE payload — rather than asserting against a fixture nobody can regenerate.
 *
 * Both storage methods are covered because a Takeout export uses both: Google stores the tiny
 * already-compressed members and deflates the CSVs.
 */
class IosArchiveReaderTest {

    @Test
    fun readsStoredAndDeflatedMembersByteForByte() = runTest {
        // Highly compressible, and long enough that DEFLATE actually shrinks it — a short string can
        // deflate LARGER than stored, which would quietly test the wrong branch.
        val csv = ("Date,Steps,Distance (m)\n" + (1..400).joinToString("\n") { "2026-07-$it,8000,6000" })
            .encodeToByteArray()
        val gpx = "<gpx><trk><name>Morning run</name></trk></gpx>".encodeToByteArray()

        val zip = buildZip(
            listOf(
                Member("Takeout/Fit/Daily activity metrics.csv", csv, deflated = true),
                Member("Takeout/Fit/run.gpx", gpx, deflated = false),
            )
        )
        val path = writeTemp(zip)

        val source = IosArchiveReader.source(path, isRelevant = { true })
        assertTrue(source != null, "the archive should be readable")
        assertEquals(2, source.count)

        val read = mutableMapOf<String, ByteArray>()
        source.forEach { read[it.name] = it.bytes }

        assertEquals(2, read.size)
        assertContentEquals(csv, read["Takeout/Fit/Daily activity metrics.csv"], "deflated member")
        assertContentEquals(gpx, read["Takeout/Fit/run.gpx"], "stored member")
    }

    /**
     * The name filter runs off the central directory, *before* any member is inflated — that is what
     * keeps a mixed Takeout (photos, mail) from costing anything to skip.
     */
    @Test
    fun irrelevantMembersAreNeverRead() = runTest {
        val zip = buildZip(
            listOf(
                Member("Takeout/Fit/run.gpx", "keep".encodeToByteArray(), deflated = false),
                Member("Takeout/Photos/IMG_0001.jpg", ByteArray(4_096) { it.toByte() }, deflated = false),
            )
        )
        val source = IosArchiveReader.source(writeTemp(zip), isRelevant = { it.endsWith(".gpx") })
        assertTrue(source != null)
        assertEquals(1, source.count, "only the relevant member should be in the directory listing")

        val names = mutableListOf<String>()
        source.forEach { names += it.name }
        assertEquals(listOf("Takeout/Fit/run.gpx"), names)
    }

    /** Directory entries are structural, not files — they must not surface as empty activity files. */
    @Test
    fun directoryEntriesAreSkipped() = runTest {
        val zip = buildZip(
            listOf(
                Member("Takeout/Fit/", ByteArray(0), deflated = false),
                Member("Takeout/Fit/run.gpx", "keep".encodeToByteArray(), deflated = false),
            )
        )
        val source = IosArchiveReader.source(writeTemp(zip), isRelevant = { true })
        assertTrue(source != null)
        assertEquals(1, source.count)
    }

    /**
     * A pick that is not a zip at all (a bare `.gpx` the user exported by hand) must report itself as
     * unreadable so the caller can fall back to the single-file path — not throw the import away.
     */
    @Test
    fun aNonZipReportsItselfUnreadable() = runTest {
        val path = writeTemp("<gpx><trk/></gpx>".encodeToByteArray())
        assertNull(IosArchiveReader.source(path, isRelevant = { true }))
    }

    @Test
    fun aMissingFileReportsItselfUnreadable() = runTest {
        assertNull(IosArchiveReader.source(NSTemporaryDirectory() + "does-not-exist.zip", isRelevant = { true }))
    }

    // ── fixture building ────────────────────────────────────────────────────────

    private class Member(val name: String, val bytes: ByteArray, val deflated: Boolean)

    /**
     * A minimal but structurally real zip: local header + payload per member, then the central
     * directory, then the EOCD. CRCs are left zero — [IosArchiveReader] does not verify them, and a
     * fixture that computed them would be testing zlib rather than the parser.
     */
    private fun buildZip(members: List<Member>): ByteArray {
        val out = ByteList()
        val offsets = mutableListOf<Int>()
        val payloads = mutableListOf<ByteArray>()

        members.forEach { member ->
            val payload = if (member.deflated) deflateRaw(member.bytes) else member.bytes
            payloads += payload
            offsets += out.size

            val name = member.name.encodeToByteArray()
            out.u32(0x04034b50)                                  // local file header signature
            out.u16(20)                                          // version needed
            out.u16(0)                                           // flags
            out.u16(if (member.deflated) 8 else 0)               // method
            out.u16(0); out.u16(0)                               // mod time / date
            out.u32(0)                                           // crc32 (unverified — see note)
            out.u32(payload.size)                                // compressed size
            out.u32(member.bytes.size)                           // uncompressed size
            out.u16(name.size); out.u16(0)                       // name / extra length
            out.bytes(name)
            out.bytes(payload)
        }

        val directoryAt = out.size
        members.forEachIndexed { i, member ->
            val name = member.name.encodeToByteArray()
            out.u32(0x02014b50)                                  // central directory signature
            out.u16(20); out.u16(20)                             // version made by / needed
            out.u16(0)                                           // flags
            out.u16(if (member.deflated) 8 else 0)               // method
            out.u16(0); out.u16(0)                               // mod time / date
            out.u32(0)                                           // crc32
            out.u32(payloads[i].size)                            // compressed size
            out.u32(member.bytes.size)                           // uncompressed size
            out.u16(name.size); out.u16(0); out.u16(0)           // name / extra / comment length
            out.u16(0); out.u16(0)                               // disk / internal attrs
            out.u32(0)                                           // external attrs
            out.u32(offsets[i])                                  // local header offset
            out.bytes(name)
        }
        val directorySize = out.size - directoryAt

        out.u32(0x06054b50)                                      // EOCD signature
        out.u16(0); out.u16(0)                                   // disk numbers
        out.u16(members.size); out.u16(members.size)             // entries on disk / total
        out.u32(directorySize)
        out.u32(directoryAt)
        out.u16(0)                                               // comment length
        return out.toByteArray()
    }

    /** Raw DEFLATE (no zlib header) — the form a zip member's payload takes. */
    @OptIn(ExperimentalForeignApi::class)
    private fun deflateRaw(input: ByteArray): ByteArray {
        // Generous ceiling: DEFLATE can expand incompressible input slightly.
        val out = ByteArray(input.size + 1_024)
        var produced = 0
        memScoped {
            val stream = alloc<z_stream>()
            val init = deflateInit2(
                stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY
            )
            check(init == Z_OK) { "deflateInit2 failed ($init)" }
            try {
                input.usePinned { pinnedIn ->
                    out.usePinned { pinnedOut ->
                        stream.next_in = pinnedIn.addressOf(0).reinterpret()
                        stream.avail_in = input.size.convert()
                        stream.next_out = pinnedOut.addressOf(0).reinterpret()
                        stream.avail_out = out.size.convert()
                        val result = deflate(stream.ptr, Z_FINISH)
                        check(result == Z_STREAM_END) { "deflate failed ($result)" }
                        produced = out.size - stream.avail_out.toInt()
                    }
                }
            } finally {
                deflateEnd(stream.ptr)
            }
        }
        check(produced < input.size) { "fixture must actually compress, else the stored path is tested twice" }
        return out.copyOf(produced)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeTemp(bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString() + ".zip"
        val wrote = bytes.usePinned { pinned ->
            val data = NSData.dataWithBytes(
                bytes = if (bytes.isEmpty()) null else pinned.addressOf(0),
                length = bytes.size.convert(),
            )
            data.writeToFile(path, atomically = true)
        }
        check(wrote) { "could not write the fixture to $path" }
        return path
    }

    /** A tiny growable little-endian writer — the fixture builder's only need. */
    private class ByteList {
        private var buffer = ByteArray(1_024)
        var size = 0
            private set

        fun bytes(value: ByteArray) {
            ensure(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }

        fun u16(value: Int) {
            ensure(2)
            buffer[size++] = (value and 0xFF).toByte()
            buffer[size++] = ((value ushr 8) and 0xFF).toByte()
        }

        fun u32(value: Int) {
            ensure(4)
            for (shift in 0..24 step 8) buffer[size++] = ((value ushr shift) and 0xFF).toByte()
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(more: Int) {
            if (size + more <= buffer.size) return
            var capacity = buffer.size
            while (capacity < size + more) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
    }
}
