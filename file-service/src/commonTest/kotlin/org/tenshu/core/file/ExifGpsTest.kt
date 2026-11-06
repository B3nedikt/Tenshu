package org.tenshu.core.file

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a photograph's coordinate out of its EXIF (`timeline-photos.md` T-P4).
 *
 * Built by hand rather than from a fixture file, so every byte the parser walks is visible here —
 * a JPEG with a real APP1/TIFF/GPS-IFD chain, in both byte orders. A parser that silently returns
 * `null` is indistinguishable from a photo with no GPS, which is exactly the failure this feature
 * is most likely to have, so these tests assert the coordinate rather than merely "not null".
 */
class ExifGpsTest {

    /**
     * A minimal JPEG carrying one GPS IFD.
     *
     * Layout after the TIFF header: IFD0 (one entry, pointing at the GPS IFD) then the GPS IFD
     * itself (four entries) then the rational triplets the degree tags point at.
     */
    private fun jpegWithGps(
        latDeg: Int, latMin: Int, latSec: Int, latRef: Char,
        lngDeg: Int, lngMin: Int, lngSec: Int, lngRef: Char,
        littleEndian: Boolean = false,
    ): ByteArray {
        val tiff = mutableListOf<Byte>()
        fun u16(v: Int) {
            if (littleEndian) { tiff += (v and 0xFF).toByte(); tiff += ((v shr 8) and 0xFF).toByte() }
            else { tiff += ((v shr 8) and 0xFF).toByte(); tiff += (v and 0xFF).toByte() }
        }
        fun u32(v: Int) {
            if (littleEndian) {
                tiff += (v and 0xFF).toByte(); tiff += ((v shr 8) and 0xFF).toByte()
                tiff += ((v shr 16) and 0xFF).toByte(); tiff += ((v shr 24) and 0xFF).toByte()
            } else {
                tiff += ((v shr 24) and 0xFF).toByte(); tiff += ((v shr 16) and 0xFF).toByte()
                tiff += ((v shr 8) and 0xFF).toByte(); tiff += (v and 0xFF).toByte()
            }
        }
        // TIFF header: byte order, 42, offset of IFD0 (8, i.e. immediately after).
        if (littleEndian) { tiff += 0x49; tiff += 0x49 } else { tiff += 0x4D; tiff += 0x4D }
        u16(42)
        u32(8)

        val ifd0At = 8
        val gpsIfdAt = ifd0At + 2 + 12 + 4          // IFD0: count + one entry + next-IFD pointer
        val latValueAt = gpsIfdAt + 2 + 4 * 12 + 4  // GPS IFD: count + four entries + next pointer
        val lngValueAt = latValueAt + 24

        // IFD0 — one entry: the GPS IFD pointer.
        u16(1)
        u16(0x8825); u16(4); u32(1); u32(gpsIfdAt)
        u32(0)

        // GPS IFD — refs inline, degrees at an offset.
        u16(4)
        u16(0x0001); u16(2); u32(2)
        // A 1-byte ASCII value sits in the high byte of the value field on big-endian, low on little.
        if (littleEndian) { tiff += latRef.code.toByte(); tiff += 0; tiff += 0; tiff += 0 }
        else { tiff += latRef.code.toByte(); tiff += 0; tiff += 0; tiff += 0 }
        u16(0x0002); u16(5); u32(3); u32(latValueAt)
        u16(0x0003); u16(2); u32(2)
        if (littleEndian) { tiff += lngRef.code.toByte(); tiff += 0; tiff += 0; tiff += 0 }
        else { tiff += lngRef.code.toByte(); tiff += 0; tiff += 0; tiff += 0 }
        u16(0x0004); u16(5); u32(3); u32(lngValueAt)
        u32(0)

        // The rationals themselves, degrees/minutes/seconds over 1.
        u32(latDeg); u32(1); u32(latMin); u32(1); u32(latSec); u32(1)
        u32(lngDeg); u32(1); u32(lngMin); u32(1); u32(lngSec); u32(1)

        val exif = listOf<Byte>('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0) + tiff
        val segmentLength = exif.size + 2
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                       // SOI
            0xFF.toByte(), 0xE1.toByte(),                       // APP1
            ((segmentLength shr 8) and 0xFF).toByte(), (segmentLength and 0xFF).toByte(),
        ) + exif.toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte()) // EOI
    }

    private fun assertCloseTo(expected: Double, actualE7: Long, label: String) {
        val actual = actualE7 / 1e7
        assertTrue(abs(expected - actual) < 1e-5, "$label: expected ~$expected, got $actual")
    }

    @Test
    fun readsANorthEastCoordinate() {
        // Firenze, 43°46'12"N 11°15'18"E — the extract the bundled basemap covers.
        val location = readExifLocation(jpegWithGps(43, 46, 12, 'N', 11, 15, 18, 'E'))
        assertTrue(location != null, "a JPEG with a GPS IFD must yield a coordinate")
        assertCloseTo(43.77, location.latE7, "latitude")
        assertCloseTo(11.255, location.lngE7, "longitude")
    }

    @Test
    fun southAndWestAreNegative() {
        // The reference tags are the entire sign, and getting them backwards puts the user on the
        // wrong side of the planet — a failure no "is it null" assertion would catch.
        val location = readExifLocation(jpegWithGps(33, 51, 54, 'S', 151, 12, 36, 'W'))
        assertTrue(location != null)
        assertCloseTo(-33.865, location.latE7, "latitude")
        assertCloseTo(-151.21, location.lngE7, "longitude")
    }

    @Test
    fun readsLittleEndianExifToo() {
        // Both byte orders occur in the wild; a parser that assumed one would work on half of all
        // cameras and fail silently on the other half.
        val location = readExifLocation(
            jpegWithGps(43, 46, 12, 'N', 11, 15, 18, 'E', littleEndian = true),
        )
        assertTrue(location != null)
        assertCloseTo(43.77, location.latE7, "latitude")
        assertCloseTo(11.255, location.lngE7, "longitude")
    }

    @Test
    fun aJpegWithoutGpsYieldsNothing() {
        val noGps = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(),
        )
        assertNull(readExifLocation(noGps))
    }

    @Test
    fun nullIslandIsReadAsNoCoordinate() {
        // (0,0) is the Atlantic and is also what a camera writes with no fix. The rest of the
        // codebase already treats it as absent; agreeing here keeps one meaning for one value.
        assertNull(readExifLocation(jpegWithGps(0, 0, 0, 'N', 0, 0, 0, 'E')))
    }

    @Test
    fun garbageNeverThrows() {
        // This runs on whatever the user uploaded. A parse failure must not fail an upload.
        assertNull(readExifLocation(ByteArray(0)))
        assertNull(readExifLocation(ByteArray(64) { it.toByte() }))
        assertNull(readExifLocation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 2)))
    }

    @Test
    fun aTruncatedExifSegmentYieldsNothingRatherThanACrash() {
        val full = jpegWithGps(43, 46, 12, 'N', 11, 15, 18, 'E')
        assertNull(readExifLocation(full.copyOfRange(0, full.size / 2)))
    }

    @Test
    fun aPngIsNotAJpeg() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertNull(readExifLocation(png))
    }
}
