package app.tenshu.vectortile.overlay

import app.tenshu.vectortile.TransitLineIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§33 L2 — the overlay build step.**
 *
 * ⚠⚠ The case that matters is [aStationsPlatformNodesAreMergedSoItsLineSetIsWhole]: OSM gives each
 * platform its own node, so no single node carries a station's real line set. That defect is
 * **invisible on the motivating journey** — München Ost → Odeonsplatz still resolves, because the
 * intersection only needs U5 to appear at both ends — and shows up as a *silently lost label* the
 * moment the nearest platform happens to be the wrong one.
 *
 * Coordinates are synthetic (this repository is public); the line sets are the real ones from §17.6.
 */
class TransitOverlayBuildTest {

    // 1 E7 of latitude ≈ 1.11 cm, so 1 000 E7 ≈ 11 m and 60 000 E7 ≈ 668 m.
    private val base = 48_130_000

    private fun row(ref: String, latOffsetE7: Int = 0, lngOffsetE7: Int = 0) =
        StopRow(ref, base + latOffsetE7, 11_500_000 + lngOffsetE7)

    // ── the whole point of the step ────────────────────────────────────────────

    @Test
    fun aStationsPlatformNodesAreMergedSoItsLineSetIsWhole() {
        // Odeonsplatz as OSM actually models it: the U3/U6 platform and the U4/U5 platform are
        // separate nodes ~44 m apart, and NEITHER carries the station's four lines.
        val rows = listOf(
            row("U3"), row("U6"),
            row("U4", latOffsetE7 = 4_000), row("U5", latOffsetE7 = 4_000),
        )
        val stations = stationsFrom(rows)

        assertEquals(1, stations.size, "one station, not one row per platform")
        assertEquals(setOf("U3", "U4", "U5", "U6"), stations.single().lines)
    }

    @Test
    fun withoutMergingTheLineIsSilentlyLostRatherThanWrong() {
        // ⚠⚠ Why the bug is easy to miss: the leg still *resolves*, it just resolves to nothing.
        // Un-merged, the nearest node to the leg's endpoint decides, and half the platforms give an
        // empty intersection — no label, no error, no way to tell it apart from "no coverage".
        val unmerged = TransitLineIndex.Overlay(
            listOf(
                TransitLineIndex.Stop(base, 11_500_000, setOf("U3", "U6")),
                TransitLineIndex.Stop(base + 4_000, 11_500_000, setOf("U4", "U5")),
            ),
        )
        val muenchenOst = TransitLineIndex.Stop(base - 60_000, 11_500_000, setOf("U5"))
        val withMuenchenOst = TransitLineIndex.Overlay(unmerged.stops + muenchenOst)

        // Endpoint nearest the U3/U6 platform ⇒ the U5 ride names nothing.
        assertTrue(
            withMuenchenOst.linesServing(
                muenchenOst.latE7, muenchenOst.lngE7,
                base, 11_500_000,
                300.0,
            ).isEmpty(),
            "precondition: this is the failure merging exists to prevent",
        )

        // Merged, the same endpoint answers correctly wherever on the station it landed.
        val merged = TransitLineIndex.Overlay(
            stationsFrom(listOf(row("U3"), row("U6"), row("U4", 4_000), row("U5", 4_000))) + muenchenOst,
        )
        assertEquals(
            setOf("U5"),
            merged.linesServing(muenchenOst.latE7, muenchenOst.lngE7, base, 11_500_000, 300.0),
        )
    }

    @Test
    fun twoDistinctStationsAreNotMerged() {
        // ⚠⚠ The adversary. Merging here would invent a line set no station serves — the exact bug
        // the lookup's "nearest, not union" rule refuses — and the intersection would then answer
        // confidently and wrongly. Odeonsplatz to Marienplatz is ~600 m; the bar is 150 m.
        // ⚠ The lines are deliberately DISJOINT so a merge is unmistakable. An earlier version of
        // this test gave both stations U3/U6 and then asserted no station carried all three — but the
        // far station legitimately serves all three, so the assertion was nonsense and failed on
        // correct output. The station count was doing the real work all along.
        val rows = listOf(row("U3"), row("U6"), row("S8", latOffsetE7 = 60_000))
        val stations = stationsFrom(rows)

        assertEquals(2, stations.size, "~668 m apart, and the bar is 150 m")
        assertEquals(setOf("U3", "U6"), stations.first().lines)
        assertEquals(setOf("S8"), stations.last().lines)

        // …and the lookup at the near station must not see the far one's line.
        val overlay = TransitLineIndex.Overlay(stations)
        assertEquals(setOf("U3", "U6"), overlay.linesAt(base, 11_500_000, 300.0))
    }

    @Test
    fun aChainOfPlatformsDoesNotWalkAcrossTheGap() {
        // Single-link clustering can chain: A–B–C where each hop is under the bar but A to C is not.
        // Pinned because a long platform corridor is exactly that shape — and it must still stop at
        // the gap to the next station rather than walking there one platform at a time.
        val rows = listOf(row("U1"), row("U2", 1_000), row("U3", 2_000), row("S1", 60_000))
        val stations = stationsFrom(rows)
        assertEquals(2, stations.size)
        assertEquals(setOf("U1", "U2", "U3"), stations.first().lines)
        assertEquals(setOf("S1"), stations.last().lines)
    }

    // ── §17.6 scope: the line, never the service ───────────────────────────────

    @Test
    fun anIndividualTrainServiceIsDroppedButItsStationKeepsItsLines() {
        // ⚠⚠ Measured on a real de-bayern build before this filter: the artefact carried `EC 112`,
        // `NJ 40295`, `EN 50463`, `WESTbahn` and a bare `12888` alongside U5 and RB 40. Those are
        // trains, not lines — the GTFS-shaped claim §17.6 excluded.
        val rows = listOf(
            StopRow("U5", base, 11_500_000, service = ""),
            StopRow("RB 40", base, 11_500_000, service = "regional"),
            StopRow("S1", base, 11_500_000, service = "commuter"),
            StopRow("EC 112", base, 11_500_000, service = "long_distance"),
            StopRow("ICE 41", base, 11_500_000, service = "national"),
            StopRow("NJ 295", base, 11_500_000, service = "night"),
            StopRow("12888", base, 11_500_000, service = "industrial"),
        )
        assertEquals(setOf("U5", "RB 40", "S1"), stationsFrom(rows).single().lines)
    }

    @Test
    fun anUntaggedRelationIsKept() {
        // ⚠⚠ The direction that matters. 260 of 1 177 relations in the real build carry NO `service`
        // tag, and that group is where the U-Bahn and trams live — U5 included. An allow-list would
        // delete exactly the lines this track exists to name, and do it silently.
        assertEquals(setOf("U5"), stationsFrom(listOf(StopRow("U5", base, 11_500_000))).single().lines)
    }

    @Test
    fun anUnrecognisedServiceValueIsKeptRatherThanDropped() {
        // Fail open: a value nobody listed must not delete a line. A stray service ref surviving is
        // visible; a missing line looks identical to no coverage.
        assertTrue(isLine("some_future_osm_value"))
        assertEquals(
            setOf("X1"),
            stationsFrom(listOf(StopRow("X1", base, 11_500_000, service = "some_future_osm_value")))
                .single().lines,
        )
    }

    @Test
    fun aCompoundServiceValueIsDroppedIfAnyPartIsServiceLevel() {
        // ⚠ Real Bavarian examples: `long_distance;night` and `night;motorail`. One service-level
        // component is enough to disqualify it.
        assertTrue(!isLine("long_distance;night"))
        assertTrue(!isLine("night;motorail"))
        assertTrue(isLine("regional;commuter"), "…but two line-level parts stay a line")
    }

    @Test
    fun aTsvWrittenBeforeTheFilterExistedStillParsesAndKeepsEveryRow() {
        // The 4th column is optional, so an older TSV yields blank services — every row a line, which
        // is the same fail-open direction as `isLine`.
        val parsed = parseRows("U5\t481300000\t115000000")
        assertEquals(1, parsed.size)
        assertEquals("", parsed.single().service)
        assertEquals(setOf("U5"), stationsFrom(parsed).single().lines)
    }

    // ── determinism, because the artefact is hashed and published ──────────────

    @Test
    fun theSameRegionBuildsToTheSameBytesWhateverOrderTheRowsArrivedIn() {
        // ⚠ Two builds of one region must be byte-identical, or every rebuild churns the catalogue's
        // hashes and re-downloads a region that did not change.
        val rows = listOf(row("U6"), row("U3"), row("U5", 4_000), row("U4", 4_000), row("S8", 60_000))
        val a = TransitLineIndex.encode(stationsFrom(rows))
        val b = TransitLineIndex.encode(stationsFrom(rows.reversed()))
        assertTrue(a.contentEquals(b), "clustering must not depend on input order")
    }

    // ── the input ──────────────────────────────────────────────────────────────

    @Test
    fun theTsvIsParsedAndJunkIsSkippedRatherThanFatal() {
        // ⚠ The input is machine-made but arrives over a network from Overpass; one odd row must not
        // cost a whole region its overlay.
        val parsed = parseRows(
            """
            # a comment
            U5	481300000	115000000

            U6	481300000	notanumber
            	481300000	115000000
            U4	481304000	115000000	extra-column-is-fine
            """.trimIndent(),
        )
        assertEquals(listOf("U5", "U4"), parsed.map { it.ref })
    }

    @Test
    fun anEmptyRegionProducesAValidEmptyOverlay() {
        val bytes = TransitLineIndex.encode(stationsFrom(parseRows("")))
        assertEquals(0, TransitLineIndex.decode(bytes)?.stops?.size)
    }

    @Test
    fun theBuiltArtefactIsWhatTheReaderAcceptsEndToEnd() {
        // The round trip the builder itself asserts before writing: rows in, artefact out, reader
        // agrees. A build must never be able to publish bytes the client refuses.
        val rows = listOf(row("U5", -60_000), row("U3"), row("U6"), row("U5", 4_000))
        val overlay = TransitLineIndex.decode(TransitLineIndex.encode(stationsFrom(rows)))!!
        assertEquals(
            setOf("U5"),
            overlay.linesServing(base - 60_000, 11_500_000, base, 11_500_000, 300.0),
        )
    }
}
