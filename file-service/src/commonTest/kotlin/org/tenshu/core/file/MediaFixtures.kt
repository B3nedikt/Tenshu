package org.tenshu.core.file

/**
 * Hand-built JPEG / HEIC / MP4 / PNG fixtures for the metadata parser (`photos-app.md` PH-7).
 *
 * ⚠⚠ **Built byte by byte rather than checked in as sample files, and that is the point.** A
 * metadata parser that silently returns nothing is indistinguishable from a file that carries
 * nothing — the exact failure this feature is most likely to have, and the one that shipped
 * unnoticed for HEIC. A fixture whose every offset is written out here is a fixture that fails
 * loudly when the walk is wrong; a binary blob is one that can only be debugged in a hex editor.
 */

/** Mutable big-/little-endian byte builder, so an offset can be computed and then patched. */
internal class Bytes(private val littleEndian: Boolean = false) {
    private val out = mutableListOf<Byte>()

    val size: Int get() = out.size

    fun u8(v: Int) = apply { out += (v and 0xFF).toByte() }

    fun u16(v: Int) = apply {
        if (littleEndian) {
            out += (v and 0xFF).toByte(); out += ((v shr 8) and 0xFF).toByte()
        } else {
            out += ((v shr 8) and 0xFF).toByte(); out += (v and 0xFF).toByte()
        }
    }

    fun u32(v: Int) = apply {
        if (littleEndian) {
            out += (v and 0xFF).toByte(); out += ((v shr 8) and 0xFF).toByte()
            out += ((v shr 16) and 0xFF).toByte(); out += ((v ushr 24) and 0xFF).toByte()
        } else {
            out += ((v ushr 24) and 0xFF).toByte(); out += ((v shr 16) and 0xFF).toByte()
            out += ((v shr 8) and 0xFF).toByte(); out += (v and 0xFF).toByte()
        }
    }

    /** Always big-endian — ISO-BMFF and JPEG marker lengths have no byte-order mark. */
    fun be32(v: Int) = apply {
        out += ((v ushr 24) and 0xFF).toByte(); out += ((v shr 16) and 0xFF).toByte()
        out += ((v shr 8) and 0xFF).toByte(); out += (v and 0xFF).toByte()
    }

    fun be16(v: Int) = apply {
        out += ((v shr 8) and 0xFF).toByte(); out += (v and 0xFF).toByte()
    }

    fun ascii(s: String) = apply { for (c in s) out += c.code.toByte() }

    fun bytes(b: ByteArray) = apply { for (x in b) out += x }

    fun zeros(n: Int) = apply { repeat(n) { out += 0 } }

    fun build(): ByteArray = out.toByteArray()
}

internal data class GpsDms(
    val latDeg: Int, val latMin: Int, val latSec: Int, val latRef: Char,
    val lngDeg: Int, val lngMin: Int, val lngSec: Int, val lngRef: Char,
)

/**
 * A TIFF/EXIF block: header, IFD0, an optional Exif sub-IFD, an optional GPS IFD, then the values
 * the entries point at.
 *
 * Offsets are computed up front from the entry counts, so the layout below is the layout the
 * parser walks — not an approximation of it.
 */
internal fun tiffBlock(
    littleEndian: Boolean = false,
    make: String? = null,
    model: String? = null,
    orientation: Int? = null,
    exifPixelWidth: Int? = null,
    exifPixelHeight: Int? = null,
    exifPixelsAsLong: Boolean = false,
    gps: GpsDms? = null,
    // ── PH-11: the photographic settings. Rationals are (numerator, denominator) so a fixture can
    // write 1/250 exactly as a camera does, rather than a rounded decimal the parser would have to
    // agree with by luck.
    exposureTime: Pair<Int, Int>? = null,
    fNumber: Pair<Int, Int>? = null,
    iso: Int? = null,
    focalLength: Pair<Int, Int>? = null,
    focalLength35mm: Int? = null,
    lensModel: String? = null,
    dateTimeOriginal: String? = null,
    exposureBias: Pair<Int, Int>? = null,
    flash: Int? = null,
    software: String? = null,
): ByteArray {
    // ⚠ Ascending tag order, as TIFF requires and as the reader's linear scan assumes.
    val exifAscii = listOfNotNull(
        dateTimeOriginal?.let { 0x9003 to it },
        lensModel?.let { 0xA434 to it },
    )
    val exifRationals = listOfNotNull(
        exposureTime?.let { Triple(0x829A, it, false) },
        fNumber?.let { Triple(0x829D, it, false) },
        exposureBias?.let { Triple(0x9204, it, true) },
        focalLength?.let { Triple(0x920A, it, false) },
    )
    val exifShorts = listOfNotNull(
        iso?.let { 0x8827 to it },
        flash?.let { 0x9209 to it },
        focalLength35mm?.let { 0xA405 to it },
    )
    val exifEntries = listOfNotNull(exifPixelWidth, exifPixelHeight).size +
        exifAscii.size + exifRationals.size + exifShorts.size
    val hasExifIfd = exifEntries > 0
    val ifd0Entries = listOfNotNull(make, model, orientation, software).size +
        (if (hasExifIfd) 1 else 0) + (if (gps != null) 1 else 0)

    val ifd0At = 8
    val ifd0Size = 2 + 12 * ifd0Entries + 4
    val exifIfdAt = ifd0At + ifd0Size
    val exifIfdSize = if (hasExifIfd) 2 + 12 * exifEntries + 4 else 0
    val gpsIfdAt = exifIfdAt + exifIfdSize
    val gpsIfdSize = if (gps != null) 2 + 12 * 4 + 4 else 0

    var valuesAt = gpsIfdAt + gpsIfdSize
    // ⚠ A value of four bytes or fewer lives in the entry itself; only longer ones consume an
    // offset. Getting this wrong in the *fixture* would hide a parser that got it wrong too.
    val makeBytes = make?.let { it.encodeToByteArray() + byteArrayOf(0) }
    val modelBytes = model?.let { it.encodeToByteArray() + byteArrayOf(0) }
    val softwareBytes = software?.let { it.encodeToByteArray() + byteArrayOf(0) }
    val makeAt = if (makeBytes != null && makeBytes.size > 4) valuesAt.also { valuesAt += makeBytes.size } else -1
    val modelAt = if (modelBytes != null && modelBytes.size > 4) valuesAt.also { valuesAt += modelBytes.size } else -1
    val softwareAt = if (softwareBytes != null && softwareBytes.size > 4) valuesAt.also { valuesAt += softwareBytes.size } else -1
    // ⚠⚠ A rational is EIGHT bytes, so it never fits the inline value field and always consumes an
    // offset — unlike the ASCII tags above, which do fit when they are short.
    val asciiAt = exifAscii.map { (_, text) ->
        val encoded = text.encodeToByteArray() + byteArrayOf(0)
        if (encoded.size > 4) valuesAt.also { valuesAt += encoded.size } else -1
    }
    val rationalAt = exifRationals.map { valuesAt.also { _ -> valuesAt += 8 } }
    val latAt = if (gps != null) valuesAt.also { valuesAt += 24 } else -1
    val lngAt = if (gps != null) valuesAt.also { valuesAt += 24 } else -1

    val b = Bytes(littleEndian)
    b.ascii(if (littleEndian) "II" else "MM").u16(42).u32(ifd0At)

    // ── IFD0, entries in ascending tag order as TIFF requires ─────────────────────────────────
    b.u16(ifd0Entries)
    fun asciiEntry(tag: Int, value: ByteArray, at: Int) {
        b.u16(tag).u16(2).u32(value.size)
        if (at >= 0) b.u32(at) else {
            for (i in 0 until 4) b.u8(if (i < value.size) value[i].toInt() else 0)
        }
    }
    makeBytes?.let { asciiEntry(0x010F, it, makeAt) }
    modelBytes?.let { asciiEntry(0x0110, it, modelAt) }
    orientation?.let { b.u16(0x0112).u16(3).u32(1).u16(it).u16(0) }
    softwareBytes?.let { asciiEntry(0x0131, it, softwareAt) }
    if (hasExifIfd) b.u16(0x8769).u16(4).u32(1).u32(exifIfdAt)
    if (gps != null) b.u16(0x8825).u16(4).u32(1).u32(gpsIfdAt)
    b.u32(0) // no IFD1

    // ── Exif sub-IFD ──────────────────────────────────────────────────────────────────────────
    if (hasExifIfd) {
        b.u16(exifEntries)
        // ⚠ SHORT on some cameras, LONG on others. Both are exercised: reading one as the other
        // yields a plausible, enormous, wrong number rather than a failure.
        fun dimension(tag: Int, value: Int) {
            if (exifPixelsAsLong) b.u16(tag).u16(4).u32(1).u32(value)
            else b.u16(tag).u16(3).u32(1).u16(value).u16(0)
        }
        // ⚠ Emitted in ascending tag order across ALL the kinds, not kind by kind: 0x829A
        // (exposure) must precede 0x8827 (ISO) must precede 0x9003 (date) regardless of which
        // list each came from.
        val entries = buildList<Pair<Int, () -> Unit>> {
            exifRationals.forEachIndexed { i, (tag, _, signed) ->
                add(tag to { b.u16(tag).u16(if (signed) 10 else 5).u32(1).u32(rationalAt[i]) })
            }
            exifShorts.forEach { (tag, value) ->
                add(tag to { b.u16(tag).u16(3).u32(1).u16(value).u16(0) })
            }
            exifAscii.forEachIndexed { i, (tag, text) ->
                val encoded = text.encodeToByteArray() + byteArrayOf(0)
                add(tag to { asciiEntry(tag, encoded, asciiAt[i]) })
            }
            exifPixelWidth?.let { add(0xA002 to { dimension(0xA002, it) }) }
            exifPixelHeight?.let { add(0xA003 to { dimension(0xA003, it) }) }
        }.sortedBy { it.first }
        entries.forEach { it.second() }
        b.u32(0)
    }

    // ── GPS IFD ───────────────────────────────────────────────────────────────────────────────
    if (gps != null) {
        b.u16(4)
        b.u16(0x0001).u16(2).u32(2).u8(gps.latRef.code).zeros(3)
        b.u16(0x0002).u16(5).u32(3).u32(latAt)
        b.u16(0x0003).u16(2).u32(2).u8(gps.lngRef.code).zeros(3)
        b.u16(0x0004).u16(5).u32(3).u32(lngAt)
        b.u32(0)
    }

    // ── values ────────────────────────────────────────────────────────────────────────────────
    if (makeAt >= 0) b.bytes(makeBytes!!)
    if (modelAt >= 0) b.bytes(modelBytes!!)
    if (softwareAt >= 0) b.bytes(softwareBytes!!)
    // ⚠⚠ Written in exactly the order the offsets were handed out above. Any other order silently
    // points every tag at the wrong bytes — and the parser would report values that are plausible.
    exifAscii.forEachIndexed { i, (_, text) ->
        if (asciiAt[i] >= 0) b.bytes(text.encodeToByteArray() + byteArrayOf(0))
    }
    exifRationals.forEach { (_, value, _) -> b.u32(value.first).u32(value.second) }
    if (gps != null) {
        b.u32(gps.latDeg).u32(1).u32(gps.latMin).u32(1).u32(gps.latSec).u32(1)
        b.u32(gps.lngDeg).u32(1).u32(gps.lngMin).u32(1).u32(gps.lngSec).u32(1)
    }
    return b.build()
}

/** A JPEG with an optional SOF frame header and an optional APP1/Exif segment. */
internal fun jpeg(
    sofWidth: Int? = null,
    sofHeight: Int? = null,
    tiff: ByteArray? = null,
    sofMarker: Int = 0xC0,
): ByteArray {
    val b = Bytes()
    b.u8(0xFF).u8(0xD8) // SOI
    if (tiff != null) {
        val payload = Bytes().ascii("Exif").zeros(2).bytes(tiff).build()
        b.u8(0xFF).u8(0xE1).be16(payload.size + 2).bytes(payload)
    }
    if (sofWidth != null && sofHeight != null) {
        // SOF: length(2) precision(1) height(2) width(2) components(1)
        b.u8(0xFF).u8(sofMarker).be16(8).u8(8).be16(sofHeight).be16(sofWidth).u8(3)
    }
    b.u8(0xFF).u8(0xD9) // EOI
    return b.build()
}

internal fun png(width: Int, height: Int): ByteArray = Bytes()
    .u8(0x89).ascii("PNG").u8(0x0D).u8(0x0A).u8(0x1A).u8(0x0A)
    .be32(13).ascii("IHDR").be32(width).be32(height)
    .u8(8).u8(6).u8(0).u8(0).u8(0)
    .be32(0) // a CRC nothing here checks
    .build()

/**
 * A HEIC: `ftyp`, then `mdat` holding the Exif payload, then `meta` pointing at it.
 *
 * ⚠ `mdat` is placed **before** `meta` so its file offset is known when `iloc` is written — the
 * order real encoders use varies and the format permits either.
 */
internal fun heic(
    tiff: ByteArray? = null,
    ispe: List<Pair<Int, Int>> = emptyList(),
    exifItemId: Int = 1,
    ilocVersion: Int = 1,
    brand: String = "heic",
): ByteArray {
    val ftyp = Bytes().be32(16).ascii("ftyp").ascii(brand).be32(0).build()

    // ExifDataBlock: a u32 offset to the TIFF header, then "Exif" + two NULs, then the header.
    val exifPayload = tiff?.let { Bytes().be32(6).ascii("Exif").zeros(2).bytes(it).build() }
    val mdat = exifPayload?.let { Bytes().be32(it.size + 8).ascii("mdat").bytes(it).build() }
    val exifAt = if (mdat != null) ftyp.size + 8 else -1

    val meta = Bytes().let { m ->
        val children = Bytes()

        if (exifPayload != null) {
            // iinf → one infe declaring item_type "Exif".
            val infe = Bytes()
                .u8(2).zeros(3)          // FullBox version 2
                .be16(exifItemId).be16(0).ascii("Exif").u8(0)
                .build()
            val infeBox = Bytes().be32(infe.size + 8).ascii("infe").bytes(infe).build()
            val iinf = Bytes().u8(0).zeros(3).be16(1).bytes(infeBox).build()
            children.be32(iinf.size + 8).ascii("iinf").bytes(iinf)

            // iloc: 4-byte offsets and lengths, no base offset, one item, one extent.
            val iloc = Bytes().apply {
                u8(ilocVersion).zeros(3)
                u8((4 shl 4) or 4)       // offset_size = 4, length_size = 4
                u8(0)                    // base_offset_size = 0, index_size = 0
                be16(1)                  // item_count
                be16(exifItemId)
                if (ilocVersion >= 1) be16(0) // construction_method = 0 (file offset)
                be16(0)                  // data_reference_index
                be16(1)                  // extent_count
                be32(exifAt)
                be32(exifPayload.size)
            }.build()
            children.be32(iloc.size + 8).ascii("iloc").bytes(iloc)
        }

        if (ispe.isNotEmpty()) {
            val ipco = Bytes()
            for ((w, h) in ispe) {
                val box = Bytes().u8(0).zeros(3).be32(w).be32(h).build()
                ipco.be32(box.size + 8).ascii("ispe").bytes(box)
            }
            val ipcoBox = Bytes().be32(ipco.size + 8).ascii("ipco").bytes(ipco.build()).build()
            children.be32(ipcoBox.size + 8).ascii("iprp").bytes(ipcoBox)
        }

        val payload = Bytes().u8(0).zeros(3).bytes(children.build()).build()
        m.be32(payload.size + 8).ascii("meta").bytes(payload).build()
    }

    return Bytes().bytes(ftyp).apply { mdat?.let { bytes(it) } }.bytes(meta).build()
}

/** An MP4: `ftyp` then `moov` holding `mvhd` and one `trak`/`tkhd` per track. */
internal fun mp4(
    durationUnits: Int,
    timescale: Int,
    tracks: List<Pair<Int, Int>> = emptyList(),
    mvhdVersion: Int = 0,
    tkhdVersion: Int = 0,
    brand: String = "isom",
): ByteArray {
    val ftyp = Bytes().be32(16).ascii("ftyp").ascii(brand).be32(0).build()

    val mvhd = Bytes().apply {
        u8(mvhdVersion).zeros(3)
        if (mvhdVersion == 1) {
            zeros(16)                    // creation + modification, 8 bytes each
            be32(timescale)
            be32(0).be32(durationUnits)  // 64-bit duration
        } else {
            zeros(8)                     // creation + modification, 4 bytes each
            be32(timescale)
            be32(durationUnits)
        }
        zeros(80)                        // rate, volume, matrix, predefined, next_track_ID
    }.build()

    val moovChildren = Bytes().be32(mvhd.size + 8).ascii("mvhd").bytes(mvhd)
    for ((w, h) in tracks) {
        val tkhd = Bytes().apply {
            u8(tkhdVersion).zeros(3)
            if (tkhdVersion == 1) zeros(32) else zeros(20)
            zeros(8)                     // reserved
            be16(0).be16(0).be16(0).be16(0) // layer, alternate_group, volume, reserved
            zeros(36)                    // matrix
            be32(w shl 16).be32(h shl 16) // 16.16 fixed point
        }.build()
        val tkhdBox = Bytes().be32(tkhd.size + 8).ascii("tkhd").bytes(tkhd).build()
        moovChildren.be32(tkhdBox.size + 8).ascii("trak").bytes(tkhdBox)
    }

    val moov = moovChildren.build()
    return Bytes().bytes(ftyp).be32(moov.size + 8).ascii("moov").bytes(moov).build()
}
