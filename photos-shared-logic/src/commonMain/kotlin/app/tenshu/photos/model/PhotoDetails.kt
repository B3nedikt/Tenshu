package app.tenshu.photos.model

import org.tenshu.core.file.MediaMetadata

/**
 * What the viewer's info sheet shows about one photo (`photos-app.md` S3 / PH-7).
 *
 * ⚠ Read for **one** photo, when the sheet is opened — never projected into the feed. Six extra
 * relations on every page would be paid by every scroll and read by nobody, and the info sheet is
 * the only screen that wants them.
 *
 * ⚠⚠ Every field is nullable and `null` means *"the file did not say"*. The sheet omits a row
 * rather than showing "Unknown": a photo whose EXIF a messaging app stripped genuinely has no
 * camera, and a line reading "Camera: Unknown" states something the row does not know.
 */
data class PhotoDetails(
    val path: String,
    val capturedAtMs: Long?,
    val sizeBytes: Long?,
    /**
     * The file's own metadata. ⚠ Held whole rather than flattened so [MediaMetadata.displayWidth]
     * stays the single place the orientation fold lives — a second copy of that rule is how a
     * portrait photo comes to be reported as landscape on one screen and not another.
     */
    val media: MediaMetadata = MediaMetadata(),
) {
    val name: String get() = path.substringAfterLast('/')

    /** Width and height as they are *shown*, with a quarter-turn orientation already applied. */
    val displayWidth: Int? get() = media.displayWidth
    val displayHeight: Int? get() = media.displayHeight

    /**
     * The camera, as one line.
     *
     * ⚠ The presentation-side fold `File.CameraMake`'s KDoc reserves for here: a model very often
     * already carries its make ("Canon EOS 5D" beside make "Canon"), and printing both gives
     * "Canon Canon EOS 5D". The stored values stay verbatim; only this line is tidied.
     */
    val camera: String?
        get() {
            val make = media.cameraMake?.trim()?.takeIf { it.isNotEmpty() }
            val model = media.cameraModel?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                make != null && model != null ->
                    if (model.startsWith(make, ignoreCase = true)) model else "$make $model"
                else -> model ?: make
            }
        }

    /** `"4032 × 3024"`, or `null` when the file did not state a size. */
    val dimensions: String?
        get() {
            val w = displayWidth ?: return null
            val h = displayHeight ?: return null
            return "$w × $h"
        }

    /**
     * Megapixels to one decimal, e.g. `"12.2 MP"`.
     *
     * ⚠ Suppressed below 0.1 MP rather than shown as "0.0 MP" — a thumbnail-sized image saying
     * "0.0 MP" reads as a bug, and the exact pixel count is already on the line above.
     */
    val megapixels: String?
        get() {
            val w = displayWidth ?: return null
            val h = displayHeight ?: return null
            val mp = (w.toLong() * h) / 1_000_000.0
            if (mp < 0.05) return null
            val tenths = ((mp * 10) + 0.5).toLong()
            return "${tenths / 10}.${tenths % 10} MP"
        }

    /** `"1:23"`, or `"1:02:03"` past an hour. `null` for a still. */
    val duration: String?
        get() {
            val ms = media.durationMs ?: return null
            if (ms <= 0) return null
            val total = ms / 1000
            val seconds = total % 60
            val minutes = (total / 60) % 60
            val hours = total / 3600
            val ss = if (seconds < 10) "0$seconds" else "$seconds"
            if (hours <= 0) return "$minutes:$ss"
            val mm = if (minutes < 10) "0$minutes" else "$minutes"
            return "$hours:$mm:$ss"
        }

    /**
     * A human size — `"2.4 MB"`.
     *
     * ⚠ Powers of 1000, not 1024, because that is what a phone's own storage screen shows and a
     * file that reads 2.4 MB in one place and 2.3 MiB in another looks like two files.
     */
    val size: String?
        get() {
            val bytes = sizeBytes ?: return null
            if (bytes < 1000) return "$bytes B"
            var value = bytes.toDouble()
            for (unit in listOf("kB", "MB", "GB", "TB")) {
                value /= 1000.0
                if (value < 1000 || unit == "TB") {
                    val tenths = ((value * 10) + 0.5).toLong()
                    return "${tenths / 10}.${tenths % 10} $unit"
                }
            }
            return "$bytes B"
        }
}

// ── how the photograph was taken, as lines (PH-11b) ──────────────────────────────────────────
//
// ⚠⚠ Presentation lives **here**, in the consumer, and never on `MediaMetadata` — the rule those
// fields' own KDoc states and the `core-realm-data.md` marker-trait precedent. `:file-service`
// records what the file said; how it reads in a sentence is this app's business, and Mia or
// Timeline may well want it phrased differently.
//
// ⚠ Every one returns `null` for absent rather than a placeholder, so the sheet omits a row instead
// of printing "Unknown" — the rule every other line in this file already follows.

/**
 * Shutter time — `"1/250 s"` below a second, `"2.5 s"` above it.
 *
 * ⚠ Photographers read short exposures as fractions and long ones as decimals, and a panel that
 * printed `"0.004 s"` would be technically right and unreadable to the only people who look.
 */
val MediaMetadata.exposureLabel: String?
    get() {
        val seconds = exposureTimeSeconds ?: return null
        if (seconds <= 0.0) return null
        if (seconds >= 1.0) {
            val tenths = ((seconds * 10) + 0.5).toLong()
            return if (tenths % 10 == 0L) "${tenths / 10} s" else "${tenths / 10}.${tenths % 10} s"
        }
        return "1/${((1.0 / seconds) + 0.5).toLong()} s"
    }

/** Aperture — `"f/1.8"`, and `"f/2"` rather than `"f/2.0"`. */
val MediaMetadata.apertureLabel: String?
    get() {
        val f = fNumber ?: return null
        if (f <= 0.0) return null
        val tenths = ((f * 10) + 0.5).toLong()
        return if (tenths % 10 == 0L) "f/${tenths / 10}" else "f/${tenths / 10}.${tenths % 10}"
    }

/**
 * Focal length — `"26 mm"`, or `"26 mm (24 mm equivalent)"` when the file also states the 35 mm
 * figure.
 *
 * ⚠ Both, never just the equivalent: the true focal length is what the lens is, and on a phone the
 * two differ by a factor of five. Printing only one of them silently answers a different question.
 */
val MediaMetadata.focalLengthLabel: String?
    get() {
        val mm = focalLengthMm ?: return null
        if (mm <= 0.0) return null
        val tenths = ((mm * 10) + 0.5).toLong()
        val exact = if (tenths % 10 == 0L) "${tenths / 10} mm" else "${tenths / 10}.${tenths % 10} mm"
        return focalLength35mm?.let { "$exact ($it mm equivalent)" } ?: exact
    }

/**
 * Exposure compensation — `"+0.3 EV"`, `"−1 EV"`, `"0 EV"`.
 *
 * ⚠⚠ `0 EV` is rendered, not suppressed. It is the *default* and by far the commonest reading, and
 * a field that vanishes precisely when it holds its usual value looks broken rather than absent.
 * ⚠ A true minus sign, not a hyphen.
 */
val MediaMetadata.exposureBiasLabel: String?
    get() {
        val ev = exposureBiasEv ?: return null
        val tenths = ((if (ev < 0) -ev else ev) * 10 + 0.5).toLong()
        val magnitude = if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
        val sign = when {
            tenths == 0L -> ""
            ev < 0 -> "−"
            else -> "+"
        }
        return "$sign$magnitude EV"
    }
