package app.tenshu.takeout

/**
 * What a Google Photos export knows about one photo, from the JSON written **beside** it (B5,
 * `takeout-import-and-recall.md` TB-D4).
 *
 * ⚠⚠ **The sidecar is the authority, not the image's EXIF.** A Takeout export frequently strips or
 * reduces the image's own metadata, so `readExifLocation` finds nothing on files that plainly have
 * a location in Google Photos. Anything read here is passed to the upload explicitly.
 *
 * `null` fields are ordinary: a photo with no location has none, and a photo with no time is
 * unknown rather than 1970.
 */
data class PhotoSidecar(
    val takenAtMs: Long? = null,
    val latE7: Long? = null,
    val lngE7: Long? = null,
) {
    val hasCoordinate: Boolean get() = latE7 != null && lngE7 != null
}

/**
 * Reads a photo sidecar. Never throws — this runs over whatever the user exported (§6).
 *
 * ⚠ **Hand-parsed, deliberately.** The file is a small object with two or three fields we want out
 * of a dozen we do not, the shapes differ between export vintages (`photoTakenTime` vs
 * `creationTime`, `geoData` vs `geoDataExif`), and this module is dependency-free by design. A
 * serialization dependency here would buy nothing but a schema to keep in step with Google's.
 *
 * ⚠⚠ `(0, 0)` is **no coordinate**, not the Atlantic — the same rule `ExifGps` already applies, and
 * Takeout writes exactly that for every photo it has no location for. Reading it literally would
 * put a decade of a user's photos off the coast of Africa.
 */
fun readPhotoSidecar(bytes: ByteArray): PhotoSidecar? = runCatching {
    val text = bytes.decodeToString()

    // Prefer the photo's own time; fall back to when Google says it was created.
    val takenAt = longIn(text, "photoTakenTime") ?: longIn(text, "creationTime")

    // `geoData` is the authored location; `geoDataExif` is what the image carried. Prefer the
    // former — a user who corrected a photo's place in Google Photos meant the correction.
    val lat = doubleAfter(text, "geoData", "latitude") ?: doubleAfter(text, "geoDataExif", "latitude")
    val lng = doubleAfter(text, "geoData", "longitude") ?: doubleAfter(text, "geoDataExif", "longitude")

    val hasCoordinate = lat != null && lng != null && !(lat == 0.0 && lng == 0.0)
    PhotoSidecar(
        takenAtMs = takenAt,
        latE7 = if (hasCoordinate) (lat!! * 1e7).toLong() else null,
        lngE7 = if (hasCoordinate) (lng!! * 1e7).toLong() else null,
    ).takeIf { it.takenAtMs != null || it.hasCoordinate }
}.getOrNull()

/**
 * The `"timestamp": "1612345678"` inside the object named [key].
 *
 * ⚠ Seconds, as a **string** — Takeout writes both `"timestamp"` (string seconds) and `"formatted"`
 * (a localised, unparseable date). Reading the formatted one is the obvious mistake.
 */
private fun longIn(text: String, key: String): Long? {
    val at = text.indexOf("\"$key\"").takeIf { it >= 0 } ?: return null
    val timestampAt = text.indexOf("\"timestamp\"", at).takeIf { it >= 0 } ?: return null
    val seconds = quotedValueAfter(text, timestampAt)?.toLongOrNull() ?: return null
    return seconds * 1_000
}

/** A numeric field inside the object named [container] — `"geoData": { "latitude": 48.13 }`. */
private fun doubleAfter(text: String, container: String, field: String): Double? {
    val at = text.indexOf("\"$container\"").takeIf { it >= 0 } ?: return null
    val end = text.indexOf('}', at).takeIf { it >= 0 } ?: text.length
    val fieldAt = text.indexOf("\"$field\"", at).takeIf { it in 0 until end } ?: return null
    val colon = text.indexOf(':', fieldAt).takeIf { it >= 0 } ?: return null
    val number = text.substring(colon + 1, end)
        .takeWhile { it.isDigit() || it == '-' || it == '.' || it == 'e' || it == 'E' || it == '+' || it.isWhitespace() }
        .trim()
    return number.toDoubleOrNull()
}

/** The string value after the key at [keyAt] — `"timestamp": "1612345678"`. */
private fun quotedValueAfter(text: String, keyAt: Int): String? {
    val colon = text.indexOf(':', keyAt).takeIf { it >= 0 } ?: return null
    val open = text.indexOf('"', colon).takeIf { it >= 0 } ?: return null
    val close = text.indexOf('"', open + 1).takeIf { it >= 0 } ?: return null
    return text.substring(open + 1, close)
}
