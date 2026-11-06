package org.tenshu.file.test

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A JPEG carrying nothing but a GPS coordinate — the fixture for anything that needs *a photo
 * taken somewhere* (`mia-query-layer.md` Track A).
 *
 * ⚠ **Real bytes on purpose.** The coordinate could be planted on the row directly, but then the
 * test would prove the query and skip the half that actually breaks: an upload only knows where a
 * photo was taken by parsing it. Seeding through `uploadFile` exercises `readExifLocation` too.
 *
 * ⚠ Distinct from `ExifGpsTest`'s own hand-built builder, which stays where it is: that one spells
 * every byte out so the *parser* can be read against it, while this one takes decimal degrees
 * because its callers think in coordinates, not in degree/minute/second rationals.
 */
fun exifJpegAt(
    latitude: Double,
    longitude: Double,
    /** Optional frame-header size, for a caller that wants the file to state one (PH-7). */
    pixelWidth: Int? = null,
    pixelHeight: Int? = null,
): ByteArray {
    val tiff = mutableListOf<Byte>()
    fun u16(v: Int) {
        tiff += ((v shr 8) and 0xFF).toByte(); tiff += (v and 0xFF).toByte()
    }

    fun u32(v: Long) {
        tiff += ((v shr 24) and 0xFF).toByte(); tiff += ((v shr 16) and 0xFF).toByte()
        tiff += ((v shr 8) and 0xFF).toByte(); tiff += (v and 0xFF).toByte()
    }

    // TIFF header, big-endian, IFD0 immediately after it.
    tiff += 0x4D; tiff += 0x4D
    u16(42)
    u32(8)

    val ifd0At = 8
    val gpsIfdAt = ifd0At + 2 + 12 + 4          // count + one entry + next-IFD pointer
    val latValueAt = gpsIfdAt + 2 + 4 * 12 + 4  // count + four entries + next-IFD pointer
    val lngValueAt = latValueAt + 24

    // IFD0 — one entry, the GPS IFD pointer.
    u16(1)
    u16(0x8825); u16(4); u32(1); u32(gpsIfdAt.toLong())
    u32(0)

    // GPS IFD — the two refs inline, the two degree triplets at an offset.
    u16(4)
    u16(0x0001); u16(2); u32(2)
    tiff += (if (latitude < 0) 'S' else 'N').code.toByte(); tiff += 0; tiff += 0; tiff += 0
    u16(0x0002); u16(5); u32(3); u32(latValueAt.toLong())
    u16(0x0003); u16(2); u32(2)
    tiff += (if (longitude < 0) 'W' else 'E').code.toByte(); tiff += 0; tiff += 0; tiff += 0
    u16(0x0004); u16(5); u32(3); u32(lngValueAt.toLong())
    u32(0)

    // ⚠ The whole coordinate rides in the degrees rational (a millionth of a degree ≈ 10 cm) with
    // minutes and seconds zero. EXIF allows any denominator, so this is a legal file — and it keeps
    // the fixture free of the rounding a real d/m/s split would introduce.
    fun degrees(value: Double) {
        u32((abs(value) * 1_000_000).roundToLong()); u32(1_000_000)
        u32(0); u32(1)
        u32(0); u32(1)
    }
    degrees(latitude)
    degrees(longitude)

    val exif = listOf<Byte>(
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
    ) + tiff
    val segmentLength = exif.size + 2
    return byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),                       // SOI
        0xFF.toByte(), 0xE1.toByte(),                       // APP1
        ((segmentLength shr 8) and 0xFF).toByte(), (segmentLength and 0xFF).toByte(),
    ) + exif.toByteArray() + frameHeader(pixelWidth, pixelHeight) +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())           // EOI
}

/**
 * A `SOF0` frame header — where a JPEG states its true pixel size (`photos-app.md` PH-7).
 *
 * ⚠ Empty when either dimension is absent, so a caller that does not care about size gets exactly
 * the file this fixture produced before PH-7 and no test's expectations move underneath it.
 */
private fun frameHeader(pixelWidth: Int?, pixelHeight: Int?): ByteArray {
    if (pixelWidth == null || pixelHeight == null) return ByteArray(0)
    return byteArrayOf(
        0xFF.toByte(), 0xC0.toByte(),
        0, 8,                                               // segment length
        8,                                                  // sample precision
        ((pixelHeight shr 8) and 0xFF).toByte(), (pixelHeight and 0xFF).toByte(),
        ((pixelWidth shr 8) and 0xFF).toByte(), (pixelWidth and 0xFF).toByte(),
        3,                                                  // component count
    )
}
