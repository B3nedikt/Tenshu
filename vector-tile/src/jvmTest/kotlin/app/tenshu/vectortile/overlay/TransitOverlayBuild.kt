package app.tenshu.vectortile.overlay

import app.tenshu.vectortile.TransitLineIndex
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * **L2 — the transit-line overlay's build step** (`timeline-movement-capture.md` §33).
 *
 * Turns the TSV that `scripts/transit-overlay-build.sh` reduces OSM to into the `.tlne` artefact
 * `TransitLineIndex` reads.
 *
 * ### ⚠⚠ Why the encoder is not in the script
 *
 * `TransitLineIndex.encode` already exists. A second implementation in bash or Python would be two
 * encoders for one format, free to drift — the failure `PlaceProximity`'s "one rule, in the module
 * both sides depend on" exists to prevent, and the same shape as the accuracy rule that drifted in
 * §32.16. So the split is: **jq does the JSON**, and Kotlin owns the transformation *and* the bytes.
 *
 * ### ⚠⚠ The problem this step actually solves: a platform node is not a station
 *
 * OSM models a station as many nodes — one per platform, plus entrances — and a `route=subway`
 * relation lists the *platform* nodes it stops at. So at Odeonsplatz the U5 platform node carries
 * `{U5}` and the U6 platform node carries `{U6}`; **no node carries the station's real line set.**
 *
 * That breaks the lookup in a way that is easy to miss, because the motivating journey still works:
 * `linesAt` answers with the *nearest* node, so München Ost → Odeonsplatz intersects `{U5}` with
 * whichever platform happened to be nearest. Get the U5 platform and the answer is right by luck;
 * get the U6 platform and the intersection is **empty** and the leg silently loses its line. And for
 * Odeonsplatz → Marienplatz the true answer `{U3, U6}` is only reachable if both ends report both.
 *
 * ⇒ **Platform nodes of one station must be merged into one stop, with the union of their lines.**
 *
 * ⚠ And that union is safe here for exactly the reason the *lookup's* union was not: these nodes are
 * one station, so every line on them genuinely serves it. Merging two *different* stations would
 * reintroduce the invented-line-set bug, which is why [STATION_CLUSTER_M] is tight and why
 * `twoDistinctStationsAreNotMerged` exists.
 */

/**
 * Platform nodes within this distance of each other are taken to be one station.
 *
 * ⚠ Deliberately tight. A large interchange's platforms span ~100–200 m (Odeonsplatz's U3/U6 and
 * U4/U5 platforms are on different levels but nearly co-located in plan), while distinct city-centre
 * stations are ~500 m apart — Odeonsplatz to Marienplatz is ~600 m. 150 m sits well inside that gap.
 *
 * ⚠⚠ The failure modes are not symmetric, which is what sets the value. Clustering **too little**
 * under-reports a station's lines and the intersection quietly comes back empty — a lost label.
 * Clustering **too much** invents a line set no station serves, and the intersection then answers
 * confidently and wrongly. The second is the one this track refuses, so when in doubt this is small.
 */
const val STATION_CLUSTER_M = 150.0

/** One `(line, platform node)` row as the script emits it. */
data class StopRow(val ref: String, val latE7: Int, val lngE7: Int, val service: String = "")

/**
 * OSM `service` values that mark a relation as an **individual train service** rather than a line.
 *
 * ### ⚠⚠ Why this exists: §17.6 settled "the line, never the service"
 *
 * Measured on a real `de-bayern` build before this filter existed, the artefact carried `EC 112`,
 * `NJ 40295`, `EN 50463`, `WESTbahn` and a bare `12888` alongside `U5` and `RB 40`. Those are trains,
 * not lines — naming *which* EC someone was on is the GTFS-shaped claim the scope decision excluded,
 * and it is the kind of claim that is confidently wrong rather than merely absent.
 *
 * ⚠ The intersection often hides this: on the reported journey both legs still resolved correctly
 * (`U5`, and `RB 40/RE 4`), because the service refs did not survive intersecting the two ends. A leg
 * between two stations that share long-distance services would have rendered `EC 112/EC 113/EC 114`.
 * So "it works on my journey" is exactly the evidence that would have let this ship.
 *
 * ### ⚠⚠ A deny-list, not an allow-list — and that direction is load-bearing
 *
 * Measured over the same build: **260 of 1 177** relations carry *no* `service` tag at all, and that
 * untagged group is where the **U-Bahn and the trams live** — U5 included. An allow-list of known-good
 * values would therefore delete the very lines this whole track exists to name, and it would do it
 * silently, the first time OSM introduced a value nobody had listed.
 *
 * So an unrecognised or absent `service` is **kept**. The failure mode is a stray service ref
 * surviving, which is visible; the alternative is a missing line, which looks identical to no
 * coverage.
 */
val SERVICE_LEVEL_ROUTES: Set<String> = setOf(
    "high_speed",       // ICE 20, ICE 41
    "long_distance",    // IC 62, ICE 47
    "national",         // ICE 41
    "international",    // EC 99
    "night",            // NJ 421, EN 40462
    "motorail",
    "industrial",       // a bare `5918` — not a passenger line at all
    "tourism",          // heritage operations; `21`, `BWB`
    "event",            // one-off S11 workings
)

/**
 * Is this row a **line** the user would recognise, rather than one train?
 *
 * ⚠ `service` may be a `;`-separated list (`long_distance;night`, `night;motorail`), so **any**
 * service-level component disqualifies it — one real example in Bavaria carries both.
 */
fun isLine(service: String): Boolean =
    service.split(';').map { it.trim().lowercase() }.none { it in SERVICE_LEVEL_ROUTES }

/**
 * Parse the builder's input: `ref \t latE7 \t lngE7`, one row per (line, platform node).
 *
 * ⚠ Blank lines, comments and malformed rows are skipped rather than fatal. The input is machine-made
 * but comes over a network from Overpass; one odd row must not cost a whole region its overlay.
 */
fun parseRows(tsv: String): List<StopRow> = tsv.lineSequence().mapNotNull { line ->
    val t = line.trim()
    if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
    val parts = t.split('\t')
    if (parts.size < 3) return@mapNotNull null
    val ref = parts[0].trim()
    val lat = parts[1].trim().toIntOrNull()
    val lng = parts[2].trim().toIntOrNull()
    // ⚠ Optional 4th column: a TSV written before the filter existed still parses, and every one of
    // its rows is kept (blank service ⇒ a line), which is the same fail-open direction as [isLine].
    val service = parts.getOrNull(3)?.trim().orEmpty()
    if (ref.isEmpty() || lat == null || lng == null) null else StopRow(ref, lat, lng, service)
}.toList()

/**
 * Collapse platform nodes into stations — see the file KDoc for why this is the whole job.
 *
 * Single-link clustering: a node joins a cluster it is within [STATION_CLUSTER_M] of. ⚠ Rows are
 * sorted first so the output is **deterministic** — two builds of the same region must produce
 * byte-identical artefacts, or every rebuild churns the catalogue's hashes for nothing.
 *
 * The cluster's position is the mean of its nodes, which for a set of platforms is the station.
 */
fun stationsFrom(rows: List<StopRow>, clusterM: Double = STATION_CLUSTER_M): List<TransitLineIndex.Stop> {
    // §17.6 scope: drop individual train services before clustering, so a station's line set is lines.
    val sorted = rows.filter { isLine(it.service) }
        .sortedWith(compareBy({ it.latE7 }, { it.lngE7 }, { it.ref }))
    val clusters = ArrayList<MutableList<StopRow>>()
    for (row in sorted) {
        val hit = clusters.firstOrNull { cluster ->
            cluster.any { distanceM(it.latE7, it.lngE7, row.latE7, row.lngE7) <= clusterM }
        }
        if (hit != null) hit.add(row) else clusters.add(mutableListOf(row))
    }
    return clusters.map { cluster ->
        TransitLineIndex.Stop(
            latE7 = cluster.sumOf { it.latE7.toLong() }.let { (it / cluster.size).toInt() },
            lngE7 = cluster.sumOf { it.lngE7.toLong() }.let { (it / cluster.size).toInt() },
            lines = cluster.map { it.ref }.toSortedSet(),
        )
    }.sortedWith(compareBy({ it.latE7 }, { it.lngE7 }))
}

/** Great-circle distance in metres between two E7 coordinates. */
internal fun distanceM(aLatE7: Int, aLngE7: Int, bLatE7: Int, bLngE7: Int): Double {
    val r = 6_371_000.0
    val lat1 = aLatE7 / 1e7 * PI / 180.0
    val lat2 = bLatE7 / 1e7 * PI / 180.0
    val dLat = lat2 - lat1
    val dLon = (bLngE7 - aLngE7) / 1e7 * PI / 180.0
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(min(1.0, sqrt(h)))
}

/**
 * `buildTransitOverlay --args="<in.tsv> <out.tlne>"`.
 *
 * Prints what it produced, because the numbers are the review surface: a region whose stop count
 * collapses between builds is the signal that an Overpass query changed under us.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: buildTransitOverlay <input.tsv> <output.tlne>")
        return
    }
    val input = File(args[0])
    val output = File(args[1])
    val rows = parseRows(input.readText())
    val dropped = rows.count { !isLine(it.service) }
    val stations = stationsFrom(rows)
    val bytes = TransitLineIndex.encode(stations)
    output.parentFile?.mkdirs()
    output.writeBytes(bytes)

    val lines = stations.flatMap { it.lines }.distinct().sorted()
    println("overlay: ${input.name} -> ${output.name}")
    println("  rows (line × platform node) : ${rows.size}")
    // ⚠ Printed even when zero: a build that silently stopped filtering is what this number exposes.
    println("  dropped as train SERVICES    : $dropped")
    println("  stations after clustering   : ${stations.size}")
    println("  distinct lines              : ${lines.size} ${lines.take(12)}${if (lines.size > 12) " …" else ""}")
    println("  artefact                    : ${bytes.size} bytes")
    // ⚠ A decode of what we just wrote, so a build cannot publish bytes the reader refuses.
    val readBack = TransitLineIndex.decode(bytes)
    check(readBack != null && readBack.stops.size == stations.size) {
        "the artefact just written does not decode — refusing to publish it"
    }
}
