package org.tenshu.core.file

/**
 * What a photograph or a video says about itself (`photos-app.md` PH-7 / PH-G4).
 *
 * Every field is optional and absent means **"the file did not say"**, never a default. A photo
 * with no camera name and a photo whose EXIF was stripped are the same statement here, and both
 * are different from a photo that is 4032 × 3024.
 */
data class MediaMetadata(
    val location: ExifLocation? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    /**
     * EXIF orientation, 1–8, describing how the stored pixels must be transformed to be shown
     * upright. ⚠ A viewer that ignores it shows every portrait iPhone photo on its side.
     */
    val orientation: Int? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    /** Playback length of a video, in milliseconds. */
    val durationMs: Long? = null,
    // ── how the photograph was taken (PH-11) ─────────────────────────────────────────────────
    //
    // ⚠⚠ These are **read but never stored**. The upload writes six scalars to the `File` row and
    // that set is unchanged, so nothing here is a schema reshape; they exist for a reader that has
    // the bytes in hand. `photos-viewer-about-screen.md` D1 records why enriching the stored row
    // instead would have shown a blank screen for every photograph already in a realm — the values
    // are read at upload, and there is no backfill (`photos-app.md` §17.4).
    /** `DateTimeOriginal` verbatim, `YYYY:MM:DD HH:MM:SS`. ⚠ Not an instant: EXIF records no zone. */
    val dateTimeOriginal: String? = null,
    /** Shutter time in seconds — `0.004` for 1/250. */
    val exposureTimeSeconds: Double? = null,
    val fNumber: Double? = null,
    val isoSpeed: Int? = null,
    val focalLengthMm: Double? = null,
    val focalLength35mm: Int? = null,
    val lensModel: String? = null,
    /** Exposure compensation in EV. ⚠ Signed, and `0.0` is both real and the common case. */
    val exposureBiasEv: Double? = null,
    /** Raw EXIF enum values — presentation belongs to the consumer, not to this row. */
    val flash: Int? = null,
    val meteringMode: Int? = null,
    val whiteBalance: Int? = null,
    /** What wrote the file — how an edited photograph admits it was edited. */
    val software: String? = null,
) {
    /**
     * ⚠⚠ Compared against a default instance rather than listing the fields.
     *
     * The hand-written conjunction this replaces named seven fields; PH-11 added twelve more, and
     * every one of them would have had to be remembered here. A forgotten field makes `isEmpty`
     * answer `true` for a file that *did* say something — silently, and only for the tag nobody
     * added. Data-class equality cannot drift from the constructor.
     */
    val isEmpty: Boolean get() = this == MediaMetadata()

    /**
     * Width and height as a viewer would show them — swapped when [orientation] says the stored
     * pixels are rotated a quarter turn.
     *
     * ⚠ EXIF 5–8 are the quarter turns (5 and 7 are transposed/transverse, which are also quarter
     * turns plus a flip). 1–4 are upright or mirrored, and leave the axes alone.
     */
    val displayWidth: Int? get() = if (isQuarterTurned) pixelHeight else pixelWidth
    val displayHeight: Int? get() = if (isQuarterTurned) pixelWidth else pixelHeight

    private val isQuarterTurned: Boolean get() = orientation != null && orientation in 5..8
}

/**
 * Read what [bytes] says about itself, without a platform API.
 *
 * **Hand-rolled, for the reason [readExifLocation] already gives**: the parse happens in
 * `commonMain` so the same bytes give the same answer on every platform, with no dependency added
 * to a service module.
 *
 * Formats, and where each keeps its metadata:
 *
 * | container | dimensions | EXIF | duration |
 * |---|---|---|---|
 * | JPEG | `SOF` marker | `APP1` segment | — |
 * | HEIC / HEIF / AVIF | `ispe` box | `Exif` item, via `iinf` + `iloc` | — |
 * | MP4 / MOV | `tkhd` box | — | `mvhd` box |
 * | PNG | `IHDR` chunk | — | — |
 *
 * ⚠⚠ **HEIC is the case this exists for.** `ExifGps.kt` used to say JPEG-only cost nothing because
 * "iOS has no gallery backup" — which stopped being true when `IosMediaSourceProvider` shipped with
 * `ios-drive-parity.md` P6b. Every iPhone photo in the realm was landing with no coordinate, which
 * silently emptied the Places section for iOS users: not an error anywhere, just a tab that is
 * always blank.
 *
 * ⚠ Never throws. A truncated, malformed, encrypted or simply unknown file yields an empty
 * [MediaMetadata]: this runs on whatever the user uploaded, and a parse failure must not fail an
 * upload.
 */
fun readMediaMetadata(bytes: ByteArray): MediaMetadata = try {
    when {
        isJpeg(bytes) -> readJpeg(bytes)
        isPng(bytes) -> readPng(bytes)
        isIsoBmff(bytes) -> readIsoBmff(bytes)
        else -> MediaMetadata()
    }
} catch (e: Exception) {
    MediaMetadata()
}

// ── format detection ─────────────────────────────────────────────────────────────────────────

private fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

private fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= 24 && bytes[0] == 0x89.toByte() &&
        bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

/** ⚠ Detected by the `ftyp` box at offset 4, not by extension — the router decides by content. */
private fun isIsoBmff(bytes: ByteArray): Boolean =
    bytes.size >= 12 && fourCc(bytes, 4) == "ftyp"

// ── JPEG ─────────────────────────────────────────────────────────────────────────────────────

private fun readJpeg(bytes: ByteArray): MediaMetadata {
    var tags: ExifTags? = null
    var sofWidth: Int? = null
    var sofHeight: Int? = null

    var offset = 2
    while (offset + 4 <= bytes.size) {
        if (bytes[offset] != 0xFF.toByte()) break
        val marker = bytes[offset + 1].toInt() and 0xFF
        // Start of scan — the image data begins and no more metadata segments follow.
        if (marker == MARKER_SOS) break
        val length = bytes.u16(offset + 2, littleEndian = false)
        if (length < 2) break
        val payload = offset + 4

        if (marker == MARKER_APP1 && payload + 6 <= bytes.size && isExifPrefix(bytes, payload)) {
            // "Exif" + two NULs, then the TIFF header every offset in the block is relative to.
            tags = readExifTags(bytes, payload + 6)
        }
        // ⚠ Any SOF_n except the four that are not frame headers. The frame header is the only
        // place a JPEG states its true pixel size — the Exif sub-IFD's `PixelXDimension` is
        // advisory and a re-encoder routinely leaves it stale.
        if (marker in SOF_MARKERS && payload + 5 <= bytes.size) {
            sofHeight = bytes.u16(payload + 1, littleEndian = false)
            sofWidth = bytes.u16(payload + 3, littleEndian = false)
        }

        offset += 2 + length
    }

    return MediaMetadata(
        location = tags?.location,
        // ⚠ SOF first: it is the encoded truth, and the Exif values are the fallback for a file
        // whose scan header we never reached.
        pixelWidth = (sofWidth ?: tags?.pixelWidth)?.takeIf { it > 0 },
        pixelHeight = (sofHeight ?: tags?.pixelHeight)?.takeIf { it > 0 },
        orientation = tags?.orientation,
        cameraMake = tags?.cameraMake,
        cameraModel = tags?.cameraModel,
    ).withPhotographic(tags)
}

/**
 * Fold the photographic settings on, from the one walk both containers share.
 *
 * ⚠⚠ A single function rather than the same twelve assignments in `readJpeg` and `readIsoBmff`.
 * This file's own KDoc makes the argument for one walk — *"two walks would drift, and the drift
 * would be invisible"* — and two copies of the fold is the same hazard one layer up: a tag added to
 * the JPEG path and forgotten in the HEIC one looks exactly like an iPhone that never recorded it.
 */
private fun MediaMetadata.withPhotographic(tags: ExifTags?): MediaMetadata {
    if (tags == null) return this
    return copy(
        dateTimeOriginal = tags.dateTimeOriginal,
        exposureTimeSeconds = tags.exposureTimeSeconds,
        fNumber = tags.fNumber,
        isoSpeed = tags.isoSpeed,
        focalLengthMm = tags.focalLengthMm,
        focalLength35mm = tags.focalLength35mm,
        lensModel = tags.lensModel,
        exposureBiasEv = tags.exposureBiasEv,
        flash = tags.flash,
        meteringMode = tags.meteringMode,
        whiteBalance = tags.whiteBalance,
        software = tags.software,
    )
}

private fun isExifPrefix(bytes: ByteArray, at: Int): Boolean =
    bytes[at] == 'E'.code.toByte() && bytes[at + 1] == 'x'.code.toByte() &&
        bytes[at + 2] == 'i'.code.toByte() && bytes[at + 3] == 'f'.code.toByte()

// ── PNG ──────────────────────────────────────────────────────────────────────────────────────

/**
 * ⚠ Worth having for one reason: a screenshot is a PNG, and a phone's screenshot bucket is one of
 * the folders people back up most.
 */
private fun readPng(bytes: ByteArray): MediaMetadata {
    // The IHDR chunk is mandatory and must come first: length(4) "IHDR" width(4) height(4).
    if (fourCc(bytes, 12) != "IHDR") return MediaMetadata()
    val width = bytes.u32(16, littleEndian = false).toInt()
    val height = bytes.u32(20, littleEndian = false).toInt()
    return MediaMetadata(
        pixelWidth = width.takeIf { it > 0 },
        pixelHeight = height.takeIf { it > 0 },
    )
}

// ── ISO base media (HEIC / HEIF / AVIF / MP4 / MOV) ───────────────────────────────────────────

private fun readIsoBmff(bytes: ByteArray): MediaMetadata {
    val meta = findBox(bytes, 0, bytes.size, "meta")
    val moov = findBox(bytes, 0, bytes.size, "moov")

    // A still image file (HEIC) carries `meta`; a movie carries `moov`. A few carry both — an
    // iPhone Live Photo's video half, for one — so both are read and each contributes what it
    // knows rather than one winning outright.
    val exif = meta?.let { readHeifExif(bytes, it) }
    val ispe = meta?.let { largestIspe(bytes, it) }
    val duration = moov?.let { readMovieDuration(bytes, it) }
    val tkhd = moov?.let { largestTrackSize(bytes, it) }

    return MediaMetadata(
        location = exif?.location,
        pixelWidth = (ispe?.first ?: tkhd?.first ?: exif?.pixelWidth)?.takeIf { it > 0 },
        pixelHeight = (ispe?.second ?: tkhd?.second ?: exif?.pixelHeight)?.takeIf { it > 0 },
        orientation = exif?.orientation,
        cameraMake = exif?.cameraMake,
        cameraModel = exif?.cameraModel,
        durationMs = duration,
    ).withPhotographic(exif)
}

/**
 * The Exif item's TIFF block inside a HEIF `meta` box.
 *
 * ⚠⚠ Two indirections, and both are needed: `iinf` says *which* item id is the Exif one, and
 * `iloc` says where that item's bytes are. Guessing either — taking the first item, or assuming
 * the payload follows `iinf` — works on one phone's output and on nothing else.
 */
private fun readHeifExif(bytes: ByteArray, meta: Box): ExifTags? {
    val itemId = exifItemId(bytes, meta) ?: return null
    val extent = itemExtent(bytes, meta, itemId) ?: return null
    val (start, length) = extent
    if (start < 0 || length <= 4 || start + length > bytes.size) return null
    // ISO/IEC 23008-12 ExifDataBlock: a u32 offset to the TIFF header, then the header. In
    // practice that offset is 6 and the six bytes are "Exif" + two NULs, but it is a field and is
    // read as one.
    val headerOffset = bytes.u32(start, littleEndian = false).toInt()
    if (headerOffset < 0 || headerOffset > length - 4) return null
    return readExifTags(bytes, start + 4 + headerOffset)
}

/** The item id whose `infe` declares `item_type == "Exif"`. */
private fun exifItemId(bytes: ByteArray, meta: Box): Long? {
    val iinf = findBox(bytes, meta.childrenStart, meta.end, "iinf") ?: return null
    val version = bytes[iinf.start].toInt() and 0xFF
    // FullBox: version(1) + flags(3), then a count whose width depends on the version.
    var at = iinf.start + 4
    val count = if (version == 0) {
        bytes.u16(at, littleEndian = false).toLong().also { at += 2 }
    } else {
        bytes.u32(at, littleEndian = false).also { at += 4 }
    }
    var seen = 0L
    while (seen < count && at < iinf.end) {
        val infe = boxAt(bytes, at, iinf.end) ?: return null
        if (infe.type == "infe") {
            val infeVersion = bytes[infe.start].toInt() and 0xFF
            var p = infe.start + 4
            val id = if (infeVersion >= 3) {
                bytes.u32(p, littleEndian = false).also { p += 4 }
            } else {
                bytes.u16(p, littleEndian = false).toLong().also { p += 2 }
            }
            p += 2 // item_protection_index
            if (fourCc(bytes, p) == "Exif") return id
        }
        at = infe.end
        seen++
    }
    return null
}

/**
 * Where item [itemId]'s bytes are, as (start, length).
 *
 * ⚠ `iloc`'s field widths are declared in the box itself — offsets and lengths can each be 0, 4
 * or 8 bytes wide — so nothing here can assume a size. A hardcoded 4 reads one iPhone generation
 * correctly and misreads the next.
 *
 * ⚠ Only `construction_method == 0` (a file offset) is handled. Method 1 (`idat`) and 2 (another
 * item) are legal and rare; an unhandled method yields no EXIF rather than a wrong offset.
 */
private fun itemExtent(bytes: ByteArray, meta: Box, itemId: Long): Pair<Int, Int>? {
    val iloc = findBox(bytes, meta.childrenStart, meta.end, "iloc") ?: return null
    val version = bytes[iloc.start].toInt() and 0xFF
    var at = iloc.start + 4

    val sizes = bytes[at].toInt() and 0xFF
    val offsetSize = (sizes shr 4) and 0x0F
    val lengthSize = sizes and 0x0F
    val sizes2 = bytes[at + 1].toInt() and 0xFF
    val baseOffsetSize = (sizes2 shr 4) and 0x0F
    val indexSize = if (version >= 1) sizes2 and 0x0F else 0
    at += 2

    val itemCount = if (version < 2) {
        bytes.u16(at, littleEndian = false).toLong().also { at += 2 }
    } else {
        bytes.u32(at, littleEndian = false).also { at += 4 }
    }

    var i = 0L
    while (i < itemCount && at < iloc.end) {
        val id = if (version < 2) {
            bytes.u16(at, littleEndian = false).toLong().also { at += 2 }
        } else {
            bytes.u32(at, littleEndian = false).also { at += 4 }
        }
        var constructionMethod = 0
        if (version >= 1) {
            constructionMethod = bytes.u16(at, littleEndian = false) and 0x0F
            at += 2
        }
        at += 2 // data_reference_index
        val baseOffset = readSized(bytes, at, baseOffsetSize).also { at += baseOffsetSize }
        val extentCount = bytes.u16(at, littleEndian = false).also { at += 2 }

        for (e in 0 until extentCount) {
            if (indexSize > 0) at += indexSize
            val extentOffset = readSized(bytes, at, offsetSize).also { at += offsetSize }
            val extentLength = readSized(bytes, at, lengthSize).also { at += lengthSize }
            // The first extent is the one that carries the TIFF header; a fragmented Exif item is
            // legal and not something this reads back together.
            if (id == itemId && e == 0) {
                if (constructionMethod != 0) return null
                return (baseOffset + extentOffset).toInt() to extentLength.toInt()
            }
        }
        i++
    }
    return null
}

/**
 * The largest `ispe` under `meta` — the primary image's size.
 *
 * ⚠ A HEIC holds several: the photo, an embedded thumbnail, and on an iPhone the Live Photo's
 * cover. Resolving *properly* means following `pitm` to the primary item and `ipma` to its
 * property indices. Taking the largest is a heuristic, and it is right for every real file
 * because a thumbnail is by definition smaller — recorded here so the next reader knows it is a
 * choice rather than an oversight.
 */
private fun largestIspe(bytes: ByteArray, meta: Box): Pair<Int, Int>? {
    val iprp = findBox(bytes, meta.childrenStart, meta.end, "iprp") ?: return null
    val ipco = findBox(bytes, iprp.start, iprp.end, "ipco") ?: return null
    var best: Pair<Int, Int>? = null
    forEachBox(bytes, ipco.start, ipco.end) { box ->
        if (box.type == "ispe" && box.start + 12 <= box.end) {
            val w = bytes.u32(box.start + 4, littleEndian = false).toInt()
            val h = bytes.u32(box.start + 8, littleEndian = false).toInt()
            if (w > 0 && h > 0 && (best == null || w.toLong() * h > best!!.first.toLong() * best!!.second)) {
                best = w to h
            }
        }
    }
    return best
}

/** `mvhd`'s duration over its timescale, in milliseconds. */
private fun readMovieDuration(bytes: ByteArray, moov: Box): Long? {
    val mvhd = findBox(bytes, moov.start, moov.end, "mvhd") ?: return null
    val version = bytes[mvhd.start].toInt() and 0xFF
    val at = mvhd.start + 4
    val timescale: Long
    val duration: Long
    if (version == 1) {
        // creation(8) modification(8) timescale(4) duration(8)
        timescale = bytes.u32(at + 16, littleEndian = false)
        duration = (bytes.u32(at + 20, littleEndian = false) shl 32) or
            bytes.u32(at + 24, littleEndian = false)
    } else {
        // creation(4) modification(4) timescale(4) duration(4)
        timescale = bytes.u32(at + 8, littleEndian = false)
        duration = bytes.u32(at + 12, littleEndian = false)
    }
    if (timescale <= 0 || duration <= 0) return null
    // ⚠ `0xFFFFFFFF` is the "unknown duration" sentinel a v0 header writes for a live capture.
    if (version != 1 && duration == 0xFFFFFFFFL) return null
    return duration * 1000L / timescale
}

/**
 * The largest track's presentation size from `tkhd`.
 *
 * ⚠ Largest, not first: an audio track's `tkhd` carries 0 × 0, and a file may also hold a timed
 * metadata track. The video track is the one with pixels.
 */
private fun largestTrackSize(bytes: ByteArray, moov: Box): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    forEachBox(bytes, moov.start, moov.end) { trak ->
        if (trak.type != "trak") return@forEachBox
        val tkhd = findBox(bytes, trak.start, trak.end, "tkhd") ?: return@forEachBox
        val version = bytes[tkhd.start].toInt() and 0xFF
        // FullBox, then either 4/4/4/4/4 (v0) or 8/8/4/4/8 (v1), then reserved(8) layer(2)
        // alternate_group(2) volume(2) reserved(2) matrix(36), then width and height as 16.16.
        val afterTimes = tkhd.start + 4 + if (version == 1) 32 else 20
        val dims = afterTimes + 8 + 2 + 2 + 2 + 2 + 36
        if (dims + 8 > tkhd.end) return@forEachBox
        val w = (bytes.u32(dims, littleEndian = false) shr 16).toInt()
        val h = (bytes.u32(dims + 4, littleEndian = false) shr 16).toInt()
        if (w > 0 && h > 0 && (best == null || w.toLong() * h > best!!.first.toLong() * best!!.second)) {
            best = w to h
        }
    }
    return best
}

// ── the box walker ───────────────────────────────────────────────────────────────────────────

/**
 * One ISO-BMFF box. [start] is the first payload byte — for a FullBox that is its version byte,
 * which every reader above accounts for itself.
 */
private class Box(val type: String, val start: Int, val end: Int) {
    /** `meta` is a FullBox: its children begin after the version and flags. */
    val childrenStart: Int get() = start + 4
}

private fun boxAt(bytes: ByteArray, at: Int, limit: Int): Box? {
    if (at < 0 || at + 8 > limit || at + 8 > bytes.size) return null
    var size = bytes.u32(at, littleEndian = false)
    val type = fourCc(bytes, at + 4) ?: return null
    var header = 8
    if (size == 1L) {
        // 64-bit largesize. ⚠ Only the low half is used — a box above 2 GiB cannot be indexed by
        // an Int anyway, and this parser works on an in-memory array.
        if (at + 16 > limit) return null
        val high = bytes.u32(at + 8, littleEndian = false)
        if (high != 0L) return null
        size = bytes.u32(at + 12, littleEndian = false)
        header = 16
    } else if (size == 0L) {
        // Extends to the end of the enclosing box.
        size = (limit - at).toLong()
    }
    if (size < header) return null
    val end = at + size.toInt()
    if (end > limit || end < at) return null
    return Box(type, at + header, end)
}

private inline fun forEachBox(bytes: ByteArray, from: Int, to: Int, action: (Box) -> Unit) {
    var at = from
    var guard = 0
    while (at + 8 <= to && guard++ < MAX_BOXES) {
        val box = boxAt(bytes, at, to) ?: return
        action(box)
        if (box.end <= at) return
        at = box.end
    }
}

/** The first box of [type] directly within `[from, to)`. */
private fun findBox(bytes: ByteArray, from: Int, to: Int, type: String): Box? {
    var found: Box? = null
    forEachBox(bytes, from, to) { if (found == null && it.type == type) found = it }
    return found
}

private fun readSized(bytes: ByteArray, at: Int, size: Int): Long = when (size) {
    0 -> 0L
    4 -> bytes.u32(at, littleEndian = false)
    8 -> (bytes.u32(at, littleEndian = false) shl 32) or bytes.u32(at + 4, littleEndian = false)
    else -> 0L
}

private fun fourCc(bytes: ByteArray, at: Int): String? {
    if (at < 0 || at + 4 > bytes.size) return null
    return buildString(4) {
        for (i in 0 until 4) append((bytes[at + i].toInt() and 0xFF).toChar())
    }
}

/** ⚠ A loop bound, not a format limit: a malformed file must not spin, however it is malformed. */
private const val MAX_BOXES = 4096

private const val MARKER_APP1 = 0xE1
private const val MARKER_SOS = 0xDA

/**
 * `SOF0`–`SOF15`, minus `0xC4` (define Huffman table), `0xC8` (reserved) and `0xCC` (define
 * arithmetic coding) — which share the range but are not frame headers.
 */
private val SOF_MARKERS = setOf(
    0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
)
