package org.tenshu.core.file

/**
 * The TIFF/EXIF tag walk, shared by every container that embeds one.
 *
 * ⚠⚠ **One walk, not one per container.** JPEG carries its EXIF in an APP1 segment and HEIC in an
 * ISO-BMFF item, but what they carry is the *same* TIFF block — so only the way in differs. Two
 * walks would drift, and the drift would be invisible: a tag read correctly from a JPEG and wrongly
 * from a HEIC looks exactly like an iPhone that never recorded it. `PlatformContentHash` was split
 * out for the same reason one slice earlier.
 *
 * ⚠ Nothing here throws. It runs on whatever the user uploaded, and a malformed file must yield
 * absent fields rather than fail an upload — every read is bounds-checked and returns `null`.
 */
internal data class ExifTags(
    val location: ExifLocation? = null,
    /** EXIF orientation, 1–8. */
    val orientation: Int? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    /** The Exif sub-IFD's own idea of the image size, when it recorded one. */
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    // ── the photographic settings (PH-11) ────────────────────────────────────────────────────
    //
    // ⚠ All of these live in the **Exif sub-IFD**, except `software` which is IFD0's. Reading one
    // from the wrong directory finds nothing, which is indistinguishable from a camera that did
    // not record it — so the two are kept visibly apart below.
    /** `DateTimeOriginal`, in EXIF's own `YYYY:MM:DD HH:MM:SS` form — **not** parsed to an instant
     * here, because it carries no zone and inventing one is a lie the row cannot support. */
    val dateTimeOriginal: String? = null,
    /** Shutter time in seconds, e.g. `0.004` for 1/250. */
    val exposureTimeSeconds: Double? = null,
    /** The f-number, e.g. `1.8`. */
    val fNumber: Double? = null,
    val isoSpeed: Int? = null,
    /** Focal length in millimetres, as the lens actually is. */
    val focalLengthMm: Double? = null,
    /** Focal length normalised to 35 mm — the number people compare between phones. */
    val focalLength35mm: Int? = null,
    val lensModel: String? = null,
    /** Exposure compensation in EV. ⚠ Signed: −2 EV is a real and common value. */
    val exposureBiasEv: Double? = null,
    /** Raw EXIF enum values. ⚠ Deliberately not folded into words here — presentation belongs to
     * the consumer, the `RealmData` marker-trait precedent (`core-realm-data.md`). */
    val flash: Int? = null,
    val meteringMode: Int? = null,
    val whiteBalance: Int? = null,
    /** IFD0's `Software` — what wrote the file, which is how an edited photo admits it was edited. */
    val software: String? = null,
)

/**
 * Walk the TIFF block starting at [tiff] — the byte-order mark itself, not the `Exif` prefix that
 * precedes it in a JPEG APP1 segment.
 *
 * Returns `null` only when the block is not a TIFF header at all; a header with nothing useful in
 * it yields an all-absent [ExifTags], which is a different statement and the one the caller needs.
 */
internal fun readExifTags(bytes: ByteArray, tiff: Int): ExifTags? {
    if (tiff < 0 || tiff + 8 > bytes.size) return null
    val littleEndian = when {
        bytes[tiff] == 0x49.toByte() && bytes[tiff + 1] == 0x49.toByte() -> true
        bytes[tiff] == 0x4D.toByte() && bytes[tiff + 1] == 0x4D.toByte() -> false
        else -> return null
    }
    if (bytes.u16(tiff + 2, littleEndian) != 42) return null

    val ifd0 = tiff + bytes.u32(tiff + 4, littleEndian).toInt()

    val exifIfd = tagValue(bytes, ifd0, littleEndian, TAG_EXIF_IFD)?.toInt()?.let { tiff + it }
    val gpsIfd = tagValue(bytes, ifd0, littleEndian, TAG_GPS_IFD)?.toInt()?.let { tiff + it }

    return ExifTags(
        location = gpsIfd?.let { gpsLocation(bytes, it, tiff, littleEndian) },
        // ⚠ Only 1–8 is meaningful. A camera that wrote 0 (or garbage) is saying nothing, and
        // storing that would make a viewer rotate by a value it cannot interpret.
        orientation = tagValue(bytes, ifd0, littleEndian, TAG_ORIENTATION)
            ?.toInt()?.takeIf { it in 1..8 },
        cameraMake = tagAscii(bytes, ifd0, tiff, littleEndian, TAG_MAKE),
        cameraModel = tagAscii(bytes, ifd0, tiff, littleEndian, TAG_MODEL),
        pixelWidth = exifIfd
            ?.let { tagValue(bytes, it, littleEndian, TAG_PIXEL_X)?.toInt() }
            ?.takeIf { it > 0 },
        pixelHeight = exifIfd
            ?.let { tagValue(bytes, it, littleEndian, TAG_PIXEL_Y)?.toInt() }
            ?.takeIf { it > 0 },
        // ⚠ IFD0's, not the sub-IFD's — `Software` is the one photographic-ish tag that lives up here.
        software = tagAscii(bytes, ifd0, tiff, littleEndian, TAG_SOFTWARE),
        dateTimeOriginal = exifIfd?.let { tagAscii(bytes, it, tiff, littleEndian, TAG_DATE_TIME_ORIGINAL) },
        // ⚠ `> 0` throughout: a camera that wrote a zero exposure or a zero focal length is saying
        // nothing, and a zero rendered as "0 s · f/0" asserts something no lens ever did.
        exposureTimeSeconds = exifIfd
            ?.let { tagRational(bytes, it, tiff, littleEndian, TAG_EXPOSURE_TIME) }
            ?.takeIf { it > 0.0 },
        fNumber = exifIfd
            ?.let { tagRational(bytes, it, tiff, littleEndian, TAG_F_NUMBER) }
            ?.takeIf { it > 0.0 },
        isoSpeed = exifIfd
            ?.let { tagValue(bytes, it, littleEndian, TAG_ISO)?.toInt() }
            ?.takeIf { it > 0 },
        focalLengthMm = exifIfd
            ?.let { tagRational(bytes, it, tiff, littleEndian, TAG_FOCAL_LENGTH) }
            ?.takeIf { it > 0.0 },
        focalLength35mm = exifIfd
            ?.let { tagValue(bytes, it, littleEndian, TAG_FOCAL_LENGTH_35MM)?.toInt() }
            ?.takeIf { it > 0 },
        lensModel = exifIfd?.let { tagAscii(bytes, it, tiff, littleEndian, TAG_LENS_MODEL) },
        // ⚠ No `> 0` here — zero EV is a real reading, and it is the *default* one. Filtering it
        // would silently drop the most common value of the field.
        exposureBiasEv = exifIfd
            ?.let { tagRational(bytes, it, tiff, littleEndian, TAG_EXPOSURE_BIAS, signed = true) },
        // ⚠ Nor here: `0` is a meaningful enum member in all three (flash did not fire; average
        // metering; auto white balance), so `takeIf { it > 0 }` would read "no" as "did not say".
        flash = exifIfd?.let { tagValue(bytes, it, littleEndian, TAG_FLASH)?.toInt() },
        meteringMode = exifIfd?.let { tagValue(bytes, it, littleEndian, TAG_METERING_MODE)?.toInt() },
        whiteBalance = exifIfd?.let { tagValue(bytes, it, littleEndian, TAG_WHITE_BALANCE)?.toInt() },
    )
}

/**
 * A `RATIONAL` (or `SRATIONAL`) tag, as a `Double`.
 *
 * ⚠⚠ **A rational is always at an offset, never inline.** Two 32-bit words is eight bytes and the
 * entry's value field holds four, so unlike every other reader here there is no inline case to
 * handle — and a reader that tried to handle one would be reading the offset as the numerator.
 *
 * ⚠ [signed] reinterprets both words as two's-complement 32-bit. `toInt()` is what does that; the
 * unsigned path must NOT go through it, or an exposure denominator above 2³¹ flips negative.
 */
private fun tagRational(
    bytes: ByteArray,
    ifd: Int,
    tiff: Int,
    littleEndian: Boolean,
    tag: Int,
    signed: Boolean = false,
): Double? {
    val entry = entryOf(bytes, ifd, littleEndian, tag) ?: return null
    val type = bytes.u16(entry + 2, littleEndian)
    if (type != (if (signed) TYPE_SRATIONAL else TYPE_RATIONAL)) return null
    if (bytes.u32(entry + 4, littleEndian) < 1) return null
    val at = tiff + bytes.u32(entry + 8, littleEndian).toInt()
    if (at < 0 || at + 8 > bytes.size) return null
    val numerator = bytes.u32(at, littleEndian)
    val denominator = bytes.u32(at + 4, littleEndian)
    return if (signed) {
        val d = denominator.toInt()
        if (d == 0) null else numerator.toInt().toDouble() / d.toDouble()
    } else {
        if (denominator == 0L) null else numerator.toDouble() / denominator.toDouble()
    }
}

/** Offset of [tag]'s 12-byte entry within the IFD at [ifd], or `null` when it is not there. */
private fun entryOf(bytes: ByteArray, ifd: Int, littleEndian: Boolean, tag: Int): Int? {
    if (ifd < 0 || ifd + 2 > bytes.size) return null
    val count = bytes.u16(ifd, littleEndian)
    for (i in 0 until count) {
        val entry = ifd + 2 + i * 12
        if (entry + 12 > bytes.size) return null
        if (bytes.u16(entry, littleEndian) == tag) return entry
    }
    return null
}

/**
 * A numeric scalar tag, whatever width it was written at.
 *
 * ⚠⚠ Type-driven, not assumed. `PixelXDimension` is SHORT on some cameras and LONG on others —
 * reading it always as LONG picks up two bytes of the *next* field and yields a plausible,
 * enormous, wrong number. That is worse than absence, because a wrong width still renders.
 */
private fun tagValue(bytes: ByteArray, ifd: Int, littleEndian: Boolean, tag: Int): Long? {
    val entry = entryOf(bytes, ifd, littleEndian, tag) ?: return null
    val type = bytes.u16(entry + 2, littleEndian)
    if (bytes.u32(entry + 4, littleEndian) < 1) return null
    // A value of four bytes or fewer is stored in the value field itself, left-aligned; anything
    // larger is an offset from the TIFF header. Only the first element is ever wanted here, and
    // every type below fits inline.
    return when (type) {
        TYPE_BYTE -> (bytes[entry + 8].toInt() and 0xFF).toLong()
        TYPE_SHORT -> bytes.u16(entry + 8, littleEndian).toLong()
        TYPE_LONG -> bytes.u32(entry + 8, littleEndian)
        else -> null
    }
}

/** An ASCII tag — inline when it fits in four bytes, at an offset when it does not. */
private fun tagAscii(
    bytes: ByteArray,
    ifd: Int,
    tiff: Int,
    littleEndian: Boolean,
    tag: Int,
): String? {
    val entry = entryOf(bytes, ifd, littleEndian, tag) ?: return null
    if (bytes.u16(entry + 2, littleEndian) != TYPE_ASCII) return null
    val count = bytes.u32(entry + 4, littleEndian).toInt()
    if (count <= 1 || count > MAX_ASCII_TAG_BYTES) return null
    val at = if (count <= 4) entry + 8 else tiff + bytes.u32(entry + 8, littleEndian).toInt()
    if (at < 0 || at + count > bytes.size) return null
    // ⚠ The count includes the terminating NUL, and some cameras pad with more of them or with
    // spaces. Stop at the first NUL and trim, rather than trusting the count; refuse a value that
    // turns out to be only padding.
    val text = buildString {
        for (i in 0 until count) {
            val b = bytes[at + i].toInt() and 0xFF
            if (b == 0) break
            append(b.toChar())
        }
    }.trim()
    return text.ifEmpty { null }
}

/** The four GPS tags, folded to a coordinate. `null` unless all four are present and sane. */
private fun gpsLocation(
    bytes: ByteArray,
    gpsIfd: Int,
    tiff: Int,
    littleEndian: Boolean,
): ExifLocation? {
    val latRef = gpsRef(bytes, gpsIfd, littleEndian, TAG_GPS_LAT_REF) ?: return null
    val lat = gpsDegrees(bytes, gpsIfd, tiff, littleEndian, TAG_GPS_LAT) ?: return null
    val lngRef = gpsRef(bytes, gpsIfd, littleEndian, TAG_GPS_LNG_REF) ?: return null
    val lng = gpsDegrees(bytes, gpsIfd, tiff, littleEndian, TAG_GPS_LNG) ?: return null

    val signedLat = if (latRef == 'S') -lat else lat
    val signedLng = if (lngRef == 'W') -lng else lng
    if (signedLat !in -90.0..90.0 || signedLng !in -180.0..180.0) return null
    // ⚠ (0,0) is the Atlantic, and it is also what a camera writes when it has a fix of nothing.
    // The rest of this codebase already treats it as "no coordinate" (the place hero's
    // `hasCoordinate` gate); agreeing with that here keeps one meaning for one value.
    if (signedLat == 0.0 && signedLng == 0.0) return null
    return ExifLocation(
        latE7 = (signedLat * 1e7).toLong(),
        lngE7 = (signedLng * 1e7).toLong(),
    )
}

/** An `N`/`S`/`E`/`W` reference tag — a one-byte ASCII value stored inline. */
private fun gpsRef(bytes: ByteArray, ifd: Int, littleEndian: Boolean, tag: Int): Char? {
    val entry = entryOf(bytes, ifd, littleEndian, tag) ?: return null
    return bytes[entry + 8].toInt().toChar()
}

/** Degrees/minutes/seconds as three rationals at an offset, folded into decimal degrees. */
private fun gpsDegrees(
    bytes: ByteArray,
    ifd: Int,
    tiff: Int,
    littleEndian: Boolean,
    tag: Int,
): Double? {
    val entry = entryOf(bytes, ifd, littleEndian, tag) ?: return null
    val at = tiff + bytes.u32(entry + 8, littleEndian).toInt()
    if (at < 0 || at + 24 > bytes.size) return null
    var degrees = 0.0
    for (part in 0 until 3) {
        val numerator = bytes.u32(at + part * 8, littleEndian).toDouble()
        val denominator = bytes.u32(at + part * 8 + 4, littleEndian).toDouble()
        if (denominator == 0.0) return null
        degrees += (numerator / denominator) / DIVISORS[part]
    }
    return degrees
}

/** ⚠ Returns `0` rather than throwing when out of bounds — see the file KDoc. */
internal fun ByteArray.u16(at: Int, littleEndian: Boolean): Int {
    if (at < 0 || at + 2 > size) return 0
    val a = this[at].toInt() and 0xFF
    val b = this[at + 1].toInt() and 0xFF
    return if (littleEndian) (b shl 8) or a else (a shl 8) or b
}

/** ⚠ Returns `0` rather than throwing when out of bounds — see the file KDoc. */
internal fun ByteArray.u32(at: Int, littleEndian: Boolean): Long {
    if (at < 0 || at + 4 > size) return 0L
    val a = (this[at].toInt() and 0xFF).toLong()
    val b = (this[at + 1].toInt() and 0xFF).toLong()
    val c = (this[at + 2].toInt() and 0xFF).toLong()
    val d = (this[at + 3].toInt() and 0xFF).toLong()
    return if (littleEndian) (d shl 24) or (c shl 16) or (b shl 8) or a
    else (a shl 24) or (b shl 16) or (c shl 8) or d
}

/** Degrees, then minutes (÷60), then seconds (÷3600). */
private val DIVISORS = doubleArrayOf(1.0, 60.0, 3600.0)

/** ⚠ A camera name is short. A four-digit count is a malformed file, not a long make. */
private const val MAX_ASCII_TAG_BYTES = 256

private const val TYPE_BYTE = 1
private const val TYPE_ASCII = 2
private const val TYPE_SHORT = 3
private const val TYPE_LONG = 4
private const val TYPE_RATIONAL = 5
private const val TYPE_SRATIONAL = 10

private const val TAG_SOFTWARE = 0x0131
private const val TAG_MAKE = 0x010F
private const val TAG_MODEL = 0x0110
private const val TAG_ORIENTATION = 0x0112
private const val TAG_EXIF_IFD = 0x8769
private const val TAG_GPS_IFD = 0x8825
private const val TAG_PIXEL_X = 0xA002
private const val TAG_PIXEL_Y = 0xA003
// ⚠ Exif sub-IFD tags. Reading these against IFD0 finds nothing — which looks exactly like a
// camera that did not record them.
private const val TAG_EXPOSURE_TIME = 0x829A
private const val TAG_F_NUMBER = 0x829D
private const val TAG_ISO = 0x8827
private const val TAG_DATE_TIME_ORIGINAL = 0x9003
private const val TAG_EXPOSURE_BIAS = 0x9204
private const val TAG_METERING_MODE = 0x9207
private const val TAG_FLASH = 0x9209
private const val TAG_FOCAL_LENGTH = 0x920A
private const val TAG_WHITE_BALANCE = 0xA403
private const val TAG_FOCAL_LENGTH_35MM = 0xA405
private const val TAG_LENS_MODEL = 0xA434

private const val TAG_GPS_LAT_REF = 0x0001
private const val TAG_GPS_LAT = 0x0002
private const val TAG_GPS_LNG_REF = 0x0003
private const val TAG_GPS_LNG = 0x0004
