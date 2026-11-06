package app.tenshu.vectortile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **The per-region transit-line overlay** (`timeline-movement-capture.md` §17.6) — the one thing that
 * lets a metro leg read "**U5** from München Ost to Odeonsplatz" instead of "Transit · 2.9 km".
 *
 * ### ⚠⚠ Why an overlay at all, and why it is keyed on **stops**
 *
 * §24.2 surveyed a real production `de-bayern` extract: `network` and `route` are **zero on every rail
 * feature**, and subway `ref` is present on **0 of 93**. Nothing we ship today can name a line, and no
 * amount of tuning changes that — the shipped Protomaps basemap simply does not carry line identity.
 *
 * And the identifying evidence cannot be the *path*. §27 measured a real covered U5 hop's stored trace
 * at **8 fixes decimated to 2, with a travelling span of 0 m**: there is nothing to map-match. What
 * survives is the **stop pair**, and intersecting the lines serving each end names the line with no
 * geometry at all. Proven against live OSM before any of this was built:
 *
 * | stop | `route=subway` relations |
 * |---|---|
 * | München Ost | {U5} |
 * | Odeonsplatz | {U3, U4, U5, U6} |
 * | **intersection** | ⭐ **{U5}** |
 *
 * ### ⚠⚠ Join by COORDINATE, never by name
 *
 * The shipped basemap POI at that station is **"München Ost"**; the OSM route relation calls the same
 * station **"Ostbahnhof"**. A name-based join fails on the very first station of the journey that
 * motivated this — and it is not an OSM quirk to paper over: that same disagreement already produced
 * *two separate `Place` rows* in a real store (§32.17).
 *
 * So this index is keyed on **position**. The basemap POI supplies the display name, the overlay
 * supplies the line, and neither has to agree with the other about spelling.
 *
 * ### Scope, settled (maintainer, 2026-08-12): the **line**, never the service
 *
 * The overlay produces `U5` and stops. ⚠ That is the relation's **`ref`**, not its `name` — measured,
 * one relation carries `ref=U5` alongside `name=U-Bahn U5: Laimer Platz => Neuperlach Süd`, which is a
 * *direction* and unreadable on a feed row. ⚠ GTFS stays out of scope: naming *which* U5 (11:02, a
 * delay, a formation) is a different kind of claim about the user's day.
 */
object TransitLineIndex {

    /** One stop: where it is, and the lines that serve it. */
    data class Stop(val latE7: Int, val lngE7: Int, val lines: Set<String>)

    /**
     * A decoded region overlay.
     *
     * ⚠ Deliberately a flat array rather than a spatial tree. A region's whole subway/tram/rail stop
     * set is thousands of rows, not millions — one U5 direction lists 18 stops — and a linear scan
     * with a cheap latitude reject is both faster and simpler than an index at that size. If a region
     * ever reaches a scale where this matters, the fix is a cell index, not a different format.
     */
    class Overlay(val stops: List<Stop>) {

        /**
         * The lines serving the stop nearest [latE7]/[lngE7] within [withinM], or empty.
         *
         * ⚠ **Nearest, not all-within.** Two different stations can sit inside one coarse radius —
         * Odeonsplatz and Marienplatz are ~600 m apart in reality — and unioning them would invent a
         * line set that **no single station serves**, which the intersection downstream would then
         * answer with confidently. The nearest stop is the one the leg actually ended at.
         */
        fun linesAt(latE7: Int, lngE7: Int, withinM: Double): Set<String> {
            var best: Stop? = null
            var bestD = Double.MAX_VALUE
            // A degree of latitude is ~111 km everywhere, so this reject is exact and cheap.
            val latWindowE7 = (withinM / 111_320.0 * 1e7).toInt() + 1
            for (stop in stops) {
                if (abs(stop.latE7 - latE7) > latWindowE7) continue
                val d = distanceM(latE7, lngE7, stop.latE7, stop.lngE7)
                if (d <= withinM && d < bestD) {
                    bestD = d
                    best = stop
                }
            }
            return best?.lines ?: emptySet()
        }

        /**
         * The lines serving **both** ends of a leg — the answer that names the line.
         *
         * ⚠⚠ The result is a **set, and often not a singleton**. Munich's shared trunk means
         * Odeonsplatz → Marienplatz answers `{U3, U6}`, and §27 measured that the tie *cannot* be
         * broken by geometry for a covered hop. [label] owns what to show; this owns the fact.
         *
         * ⚠ Empty when either end is unknown to the overlay — which is "we cannot say", not "no line".
         * The caller must render the leg exactly as it does today rather than claiming anything.
         */
        fun linesServing(
            fromLatE7: Int, fromLngE7: Int,
            toLatE7: Int, toLngE7: Int,
            withinM: Double,
        ): Set<String> {
            val from = linesAt(fromLatE7, fromLngE7, withinM)
            if (from.isEmpty()) return emptySet()
            val to = linesAt(toLatE7, toLngE7, withinM)
            return from intersect to
        }
    }

    /**
     * How an ambiguous answer is shown: `U5`, or `U3/U6` when the trunk serves both.
     *
     * ⚠⚠ **The decision, taken deliberately before the pipeline existed** (§17.6): showing the set is
     * the honest default and is what a departure board does. Falling back to the bare mode throws away
     * a true fact, and picking one arbitrarily is the only option that can be *wrong* — a confident
     * lie about the user's day, which is the failure mode this whole track is built to avoid.
     *
     * ⚠ Sorted, so the same pair always renders the same way on every device.
     */
    fun label(lines: Set<String>): String? =
        lines.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("/")

    // ── the artefact ───────────────────────────────────────────────────────────

    private const val MAGIC_0 = 'T'.code.toByte()
    private const val MAGIC_1 = 'L'.code.toByte()
    private const val MAGIC_2 = 'N'.code.toByte()
    private const val MAGIC_3 = 'E'.code.toByte()

    /** Format version. ⚠ Bump on any layout change; [decode] refuses anything it does not know. */
    const val FORMAT_VERSION = 1

    /**
     * Decode a region overlay, or `null` when the bytes are not one we understand.
     *
     * ⚠⚠ `null` rather than an exception, and the distinction matters at the call site: a missing or
     * unreadable overlay must leave the leg reading exactly as it does today. A thrown error here
     * would turn "we cannot name the line" into "the feed failed", which is strictly worse than the
     * absence this replaces.
     *
     * Layout — a shared string table because a region has thousands of stops and tens of lines, so the
     * refs are the one thing worth not repeating:
     * ```
     * "TLNE"  u8 version
     * u16 lineCount   ( u8 refLen, refLen bytes UTF-8 ) * lineCount
     * u32 stopCount   ( i32 latE7, i32 lngE7, u8 n, u16 lineIndex * n ) * stopCount
     * ```
     */
    fun decode(bytes: ByteArray): Overlay? {
        if (bytes.size < 6) return null
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1 || bytes[2] != MAGIC_2 || bytes[3] != MAGIC_3) return null
        if (bytes[4].toInt() and 0xFF != FORMAT_VERSION) return null
        var o = 5
        fun u8(): Int {
            if (o >= bytes.size) return -1
            return bytes[o++].toInt() and 0xFF
        }
        fun u16(): Int {
            if (o + 1 >= bytes.size) return -1
            return (u8() shl 8) or u8()
        }
        fun i32(): Int {
            if (o + 3 >= bytes.size) return Int.MIN_VALUE
            return (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        }

        val lineCount = u16()
        if (lineCount < 0) return null
        val refs = ArrayList<String>(lineCount)
        repeat(lineCount) {
            val len = u8()
            if (len < 0 || o + len > bytes.size) return null
            refs += bytes.decodeToString(o, o + len)
            o += len
        }
        val stopCount = i32()
        if (stopCount < 0) return null
        val stops = ArrayList<Stop>(min(stopCount, 1 shl 16))
        repeat(stopCount) {
            val lat = i32()
            val lng = i32()
            val n = u8()
            if (lat == Int.MIN_VALUE || lng == Int.MIN_VALUE || n < 0) return null
            val lines = LinkedHashSet<String>(n)
            repeat(n) {
                val idx = u16()
                if (idx < 0 || idx >= refs.size) return null
                lines += refs[idx]
            }
            stops += Stop(lat, lng, lines)
        }
        return Overlay(stops)
    }

    /** The encoder the build step uses; kept beside [decode] so the two cannot drift. */
    fun encode(stops: List<Stop>): ByteArray {
        val refs = stops.flatMap { it.lines }.distinct().sorted()
        val indexOf = refs.withIndex().associate { (i, r) -> r to i }
        val out = ArrayList<Byte>(16 + refs.size * 4 + stops.size * 12)
        fun u8(v: Int) = out.add((v and 0xFF).toByte())
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun i32(v: Int) { u8(v shr 24); u8(v shr 16); u8(v shr 8); u8(v) }

        out.add(MAGIC_0); out.add(MAGIC_1); out.add(MAGIC_2); out.add(MAGIC_3)
        u8(FORMAT_VERSION)
        u16(refs.size)
        refs.forEach { ref ->
            val encoded = ref.encodeToByteArray()
            u8(encoded.size)
            encoded.forEach { out.add(it) }
        }
        i32(stops.size)
        stops.forEach { stop ->
            i32(stop.latE7)
            i32(stop.lngE7)
            u8(stop.lines.size)
            stop.lines.sorted().forEach { u16(indexOf.getValue(it)) }
        }
        return out.toByteArray()
    }

    /** Great-circle distance in metres between two E7 coordinates. */
    private fun distanceM(aLatE7: Int, aLngE7: Int, bLatE7: Int, bLngE7: Int): Double {
        val r = 6_371_000.0
        val lat1 = aLatE7 / 1e7 * PI / 180.0
        val lat2 = bLatE7 / 1e7 * PI / 180.0
        val dLat = lat2 - lat1
        val dLon = (bLngE7 - aLngE7) / 1e7 * PI / 180.0
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }
}
