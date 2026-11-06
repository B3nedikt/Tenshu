package app.tenshu.fit.data

import app.tenshu.fit.data.xml.XmlNode
import app.tenshu.fit.data.xml.XmlReader
import org.tenshu.health.common.HrSample
import org.tenshu.health.common.RouteSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The **file-import parser** (P5.3, `docs/proposals/fit-app.md` §9.6 +
 * `timeline-services.md` §4.2): sniffs an [ActivityFile] as **GPX**, **TCX**, or binary **FIT** and
 * normalises it into the neutral [ImportedHealth] (a workout per track/activity/session), which the
 * shared [HealthImportWriter] persists.
 *
 * GPX/TCX are pure over bytes and live in `commonMain` — they read the DOM through the [XmlReader]
 * seam (JVM = `javax.xml`; web = the browser's `DOMParser`), so no platform XML API leaks into this
 * logic. Binary **FIT** decoding needs the Garmin FIT Java SDK and is therefore JVM-only; it rides
 * the optional [FitBinaryDecoder] seam, absent on platforms that can't decode it. All three fill the
 * workout **summary** (type / start / end / duration / distance / energy / avg HR) **and the dense
 * route + HR streams** (`Workout.Streams` via [ImportedWorkout.route]/`heartRate`). A file that
 * parses to nothing yields an empty result, never throws.
 */
class ActivityFileParser(
    private val xml: XmlReader,
    private val fit: FitBinaryDecoder? = null,
) {

    /** Sniff + parse one file. Unknown/garbage → empty (never throws). */
    fun parse(file: ActivityFile): ImportedHealth = runCatching {
        when (detectFormat(file)) {
            Format.Gpx -> ImportedHealth(workouts = parseGpx(file.bytes))
            Format.Tcx -> ImportedHealth(workouts = parseTcx(file.bytes))
            Format.Fit -> ImportedHealth(workouts = fit?.decode(file.bytes).orEmpty())
            Format.Unknown -> ImportedHealth()
        }
    }.getOrDefault(ImportedHealth())

    /** Parse many, merged. */
    fun parseAll(files: List<ActivityFile>): ImportedHealth =
        files.map(::parse).fold(ImportedHealth()) { acc, h -> acc.copy(workouts = acc.workouts + h.workouts) }

    enum class Format { Gpx, Tcx, Fit, Unknown }

    fun detectFormat(file: ActivityFile): Format {
        when (file.name.substringAfterLast('.', "").lowercase()) {
            "gpx" -> return Format.Gpx
            "tcx" -> return Format.Tcx
            "fit" -> return Format.Fit
        }
        // Fall back to content sniffing: FIT files carry the ASCII marker ".FIT" at bytes 8..11;
        // GPX/TCX are XML with a recognisable root.
        if (isFit(file.bytes)) return Format.Fit
        val head = file.bytes.decodeToString().take(512)
        return when {
            head.contains("<gpx") -> Format.Gpx
            head.contains("TrainingCenterDatabase") -> Format.Tcx
            else -> Format.Unknown
        }
    }

    private fun isFit(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()

    // ── GPX ──────────────────────────────────────────────────────────────────

    private fun parseGpx(bytes: ByteArray): List<ImportedWorkout> {
        val doc = xml.parse(bytes) ?: return emptyList()
        return doc.descendants("trk").mapNotNull { trk ->
            val points = trk.descendants("trkseg").flatMap { it.descendants("trkpt") }
            val times = points.mapNotNull { it.text("time")?.let(::parseInstant) }
            if (times.isEmpty()) return@mapNotNull null
            val startMs = times.first()
            val endMs = times.last()
            val distanceM = points
                .mapNotNull { p -> p.attr("lat")?.toDoubleOrNull()?.let { lat -> p.attr("lon")?.toDoubleOrNull()?.let { lon -> lat to lon } } }
                .zipWithNext { a, b -> haversineMeters(a.first, a.second, b.first, b.second) }
                .sum()

            // Dense streams: one RouteSample per fix (E7 lat/lng, altitude cm from <ele>), and an
            // HrSample where a trackpoint carries a heart-rate extension (<gpxtpx:hr> / <hr>). tSec
            // is whole seconds from the track's first fix.
            val route = ArrayList<RouteSample>(points.size)
            val hr = ArrayList<HrSample>()
            for (p in points) {
                val lat = p.attr("lat")?.toDoubleOrNull() ?: continue
                val lon = p.attr("lon")?.toDoubleOrNull() ?: continue
                val t = p.text("time")?.let(::parseInstant) ?: continue
                val tSec = ((t - startMs) / 1000).toInt()
                val altCm = p.text("ele")?.toDoubleOrNull()?.let { (it * 100).roundToInt() } ?: RouteSample.NO_ALT
                route += RouteSample(tSec = tSec, latE7 = (lat * 1e7).roundToInt(), lngE7 = (lon * 1e7).roundToInt(), altCm = altCm)
                p.text("hr")?.toIntOrNull()?.let { hr += HrSample(tSec = tSec, bpm = it) }
            }
            val avgHr = if (hr.isEmpty()) 0L else (hr.sumOf { it.bpm }.toDouble() / hr.size).roundToLong()

            ImportedWorkout(
                workoutId = "gpx-$startMs",
                activityType = activityTypeFromText(trk.text("type") ?: trk.text("name") ?: ""),
                source = SOURCE_FILE,
                startMs = startMs,
                endMs = endMs,
                durationSec = (endMs - startMs) / 1000,
                title = trk.text("name") ?: "",
                distanceM = distanceM.roundToLong(),
                avgHr = avgHr,
                route = route,
                heartRate = hr,
            )
        }
    }

    // ── TCX ──────────────────────────────────────────────────────────────────

    private fun parseTcx(bytes: ByteArray): List<ImportedWorkout> {
        val doc = xml.parse(bytes) ?: return emptyList()
        val segments = doc.descendants("Activity").mapNotNull(::parseTcxSegment)
        // ⚠ One file is one *session*, but Google Fit splits that session into a run of `<Activity>`
        // elements — one per continuous stretch of movement. Taken literally, a single 15-minute walk
        // imported as **eight** workouts of 30 s–7 min each, and its handful of GPS fixes scattered
        // one-or-none per fragment, so none of them cleared the two-point floor the route view needs:
        // the walk lost its map by being cut up. Segments of the same sport are therefore stitched
        // back into the one session they came from. Distinct sports stay distinct (a multi-sport TCX
        // is genuinely several workouts), and a file with a single `<Activity>` is unaffected.
        return segments.groupBy { it.sport }.values.map(::mergeTcxSegments)
    }

    /** One `<Activity>`: its summary totals plus its samples, timestamped **absolutely** (see [mergeTcxSegments]). */
    private fun parseTcxSegment(activity: XmlNode): TcxSegment? {
        val laps = activity.descendants("Lap")
        if (laps.isEmpty()) return null
        val startText = activity.text("Id") ?: laps.first().attr("StartTime") ?: return null
        val startMs = parseInstant(startText) ?: return null
        // ⚠ `childText`, not `text`: a lap's summary <DistanceMeters> sits *after* its <Track> in a
        // Google Fit export, so a descendant read returns the first trackpoint's running total — 0.0
        // — and every walk imported as zero metres. See XmlNode.childText.
        val durationSec = laps.sumOf { it.childText("TotalTimeSeconds")?.toDoubleOrNull() ?: 0.0 }
        val distanceM = laps.sumOf { it.childText("DistanceMeters")?.toDoubleOrNull() ?: 0.0 }
        val calories = laps.sumOf { it.childText("Calories")?.toDoubleOrNull() ?: 0.0 }
        val hrs = laps.mapNotNull { it.descendants("AverageHeartRateBpm").firstOrNull()?.text("Value")?.toDoubleOrNull() }

        // Dense streams: a fix per Trackpoint that carries a Position, plus its HR. Held as absolute
        // epoch millis — a segment's samples are re-based onto the merged session's start below.
        val route = mutableListOf<TcxRouteFix>()
        val hrSeries = mutableListOf<TcxHrFix>()
        for (tp in activity.descendants("Trackpoint")) {
            val t = tp.text("Time")?.let(::parseInstant) ?: continue
            val lat = tp.text("LatitudeDegrees")?.toDoubleOrNull()
            val lng = tp.text("LongitudeDegrees")?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val altCm = tp.text("AltitudeMeters")?.toDoubleOrNull()?.let { (it * 100).roundToInt() } ?: RouteSample.NO_ALT
                route += TcxRouteFix(tMs = t, latE7 = (lat * 1e7).roundToInt(), lngE7 = (lng * 1e7).roundToInt(), altCm = altCm)
            }
            // A Trackpoint's <HeartRateBpm><Value> is its only <Value>.
            tp.text("Value")?.toIntOrNull()?.let { hrSeries += TcxHrFix(tMs = t, bpm = it) }
        }

        return TcxSegment(
            sport = activity.attr("Sport") ?: "",
            startMs = startMs,
            durationSec = durationSec,
            distanceM = distanceM,
            calories = calories,
            avgHrs = hrs,
            route = route,
            heartRate = hrSeries,
        )
    }

    /**
     * Stitches one sport's segments back into the session they were exported from: totals add up,
     * samples concatenate in time order and are re-based onto the session start (a segment's `tSec`
     * was relative to *its own* start, so folding them without re-basing would stack every segment
     * on top of the first).
     *
     * `durationSec` stays the **sum of the segments' active time**, not the wall-clock span: the gaps
     * between segments are exactly the standing-still the exporter cut out, and counting them would
     * inflate every walk.
     */
    private fun mergeTcxSegments(sameSport: List<TcxSegment>): ImportedWorkout {
        val ordered = sameSport.sortedBy { it.startMs }
        val startMs = ordered.first().startMs
        val durationSec = ordered.sumOf { it.durationSec }
        val avgHrs = ordered.flatMap { it.avgHrs }
        return ImportedWorkout(
            workoutId = "tcx-$startMs",
            activityType = activityTypeFromText(ordered.first().sport),
            source = SOURCE_FILE,
            startMs = startMs,
            endMs = startMs + (durationSec * 1000).toLong(),
            durationSec = durationSec.toLong(),
            title = ordered.first().sport,
            distanceM = ordered.sumOf { it.distanceM }.roundToLong(),
            activeEnergyKj = (ordered.sumOf { it.calories } * KCAL_TO_KJ).roundToLong(),
            avgHr = if (avgHrs.isEmpty()) 0 else avgHrs.average().roundToLong(),
            route = ordered.flatMap { it.route }
                .sortedBy { it.tMs }
                .map { RouteSample(tSec = it.tMs.toTSec(startMs), latE7 = it.latE7, lngE7 = it.lngE7, altCm = it.altCm) },
            heartRate = ordered.flatMap { it.heartRate }
                .sortedBy { it.tMs }
                .map { HrSample(tSec = it.tMs.toTSec(startMs), bpm = it.bpm) },
        )
    }

    private fun Long.toTSec(startMs: Long): Int = ((this - startMs) / 1000).toInt()

    /** One `<Activity>`'s parse, before same-sport segments are merged. */
    private data class TcxSegment(
        val sport: String,
        val startMs: Long,
        val durationSec: Double,
        val distanceM: Double,
        val calories: Double,
        val avgHrs: List<Double>,
        val route: List<TcxRouteFix>,
        val heartRate: List<TcxHrFix>,
    )

    /** A fix still carrying its absolute timestamp — [RouteSample] once re-based on the session start. */
    private data class TcxRouteFix(val tMs: Long, val latE7: Int, val lngE7: Int, val altCm: Int)

    private data class TcxHrFix(val tMs: Long, val bpm: Int)

    // ── neutral activity-type mapping (→ the strings FitProjection.activityType understands) ──

    private fun activityTypeFromText(raw: String): String {
        val t = raw.lowercase()
        return when {
            "run" in t -> "Running"
            "walk" in t || "hik" in t -> "Walking"
            "bik" in t || "cycl" in t || "ride" in t -> "Cycling"
            "strength" in t || "weight" in t -> "Strength"
            else -> "Other"
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Parse an ISO-8601 timestamp (with 'Z' or an offset) to epoch millis, or null. */
    private fun parseInstant(s: String): Long? =
        runCatching { kotlin.time.Instant.parse(s).toEpochMilliseconds() }.getOrNull()

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val SOURCE_FILE = "File"
        const val KCAL_TO_KJ = 4.184
    }
}

/**
 * Decodes a **binary FIT** activity into neutral [ImportedWorkout]s. A `commonMain` seam so
 * [ActivityFileParser] stays platform-agnostic; the only implementation is the JVM `FitDecoder`
 * (Garmin FIT Java SDK). Platforms that can't decode FIT bind no decoder — the FIT branch then
 * yields empty rather than failing.
 */
interface FitBinaryDecoder {
    fun decode(bytes: ByteArray): List<ImportedWorkout>
}
