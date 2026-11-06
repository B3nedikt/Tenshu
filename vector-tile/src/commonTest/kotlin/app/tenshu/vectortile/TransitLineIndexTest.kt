package app.tenshu.vectortile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **§17.6 L1 — the transit-line overlay's format and lookup.**
 *
 * The line sets below are the ones measured against live OSM before any of this was built (station
 * names and their `route=subway` relations are public infrastructure data, quoted in §17.6). ⚠ The
 * *coordinates* are synthetic: a real grid would add nothing the logic needs and this repository is
 * public.
 */
class TransitLineIndexTest {

    // A synthetic grid: 1 E7 of latitude ≈ 1.11 cm, so 30 000 E7 ≈ 334 m between adjacent stops.
    private fun stop(name: String, latE7: Int, vararg lines: String) =
        TransitLineIndex.Stop(latE7 = latE7, lngE7 = 11_500_000, lines = lines.toSet())

    private val muenchenOst = stop("München Ost", 48_100_000, "U5")
    private val odeonsplatz = stop("Odeonsplatz", 48_130_000, "U3", "U4", "U5", "U6")
    private val marienplatz = stop("Marienplatz", 48_160_000, "U3", "U6", "S1", "S8")

    private val overlay = TransitLineIndex.Overlay(listOf(muenchenOst, odeonsplatz, marienplatz))

    private val within = 300.0

    // ── the answer the whole overlay exists for ────────────────────────────────

    @Test
    fun aStopPairNamesTheLineWithNoGeometryAtAll() {
        // ⭐ The reported U5 ride. §27 measured its stored trace at 8 fixes decimated to 2 with a
        // travelling span of 0 m — there is nothing to map-match, and this needs nothing to.
        val lines = overlay.linesServing(
            muenchenOst.latE7, muenchenOst.lngE7,
            odeonsplatz.latE7, odeonsplatz.lngE7,
            within,
        )
        assertEquals(setOf("U5"), lines)
        assertEquals("U5", TransitLineIndex.label(lines))
    }

    @Test
    fun aSharedTrunkAnswersWithASetAndIsShownAsOne() {
        // ⚠⚠ The case that flatters no one: U3 and U6 run together across the centre, so any leg
        // between two trunk stops answers with two lines and the tie CANNOT be broken by geometry.
        // Showing the set is the decision (§17.6) — picking one is the only option that can be wrong.
        val lines = overlay.linesServing(
            odeonsplatz.latE7, odeonsplatz.lngE7,
            marienplatz.latE7, marienplatz.lngE7,
            within,
        )
        assertEquals(setOf("U3", "U6"), lines)
        assertEquals("U3/U6", TransitLineIndex.label(lines))
    }

    @Test
    fun theLabelIsStableWhateverOrderTheSetArrivedIn() {
        // Two devices must render the same string, so the join is sorted rather than insertion-ordered.
        assertEquals("U3/U6", TransitLineIndex.label(linkedSetOf("U6", "U3")))
        assertEquals("U3/U6", TransitLineIndex.label(linkedSetOf("U3", "U6")))
    }

    // ── what it must refuse to say ─────────────────────────────────────────────

    @Test
    fun anEndTheOverlayDoesNotKnowYieldsNothingRatherThanAGuess() {
        // ⚠ "We cannot say" is not "no line". The caller must render the leg exactly as it does today.
        val lines = overlay.linesServing(
            muenchenOst.latE7, muenchenOst.lngE7,
            49_000_000, 11_500_000, // nowhere near any stop in the overlay
            within,
        )
        assertTrue(lines.isEmpty())
        assertNull(TransitLineIndex.label(lines))
    }

    @Test
    fun twoStopsWithNoLineInCommonNameNothing() {
        // A leg whose ends are both known but share no line is not a line — it is two rides, or a
        // wrong pair of endpoints. Either way, claiming a line here would be the confident lie.
        val onlyS = TransitLineIndex.Overlay(listOf(muenchenOst, stop("x", 48_130_000, "S8")))
        assertTrue(
            onlyS.linesServing(muenchenOst.latE7, muenchenOst.lngE7, 48_130_000, 11_500_000, within)
                .isEmpty(),
        )
    }

    @Test
    fun theNearestStopAnswersRatherThanEveryStopInRange() {
        // ⚠⚠ Odeonsplatz and Marienplatz are ~334 m apart here, so a generous radius covers both.
        // Unioning them would invent {U3,U4,U5,U6,S1,S8} — a line set no single station serves — and
        // the intersection downstream would then answer confidently and wrongly.
        val generous = 5_000.0
        assertEquals(
            odeonsplatz.lines,
            overlay.linesAt(odeonsplatz.latE7, odeonsplatz.lngE7, generous),
            "the stop the leg actually ended at, not everything nearby",
        )
    }

    @Test
    fun aStopJustOutsideTheRadiusDoesNotAnswer() {
        // ~334 m SOUTH of München Ost (1 E7 of latitude ≈ 1.11 cm, so 30 000 E7 ≈ 334 m).
        // ⚠ South, not north: the stops sit 30 000 E7 apart, so probing north lands exactly on
        // Odeonsplatz and the test would pass on the wrong stop answering rather than on the radius.
        val southOfIt = muenchenOst.latE7 - 30_000
        assertTrue(overlay.linesAt(southOfIt, muenchenOst.lngE7, 300.0).isEmpty())
        assertEquals(setOf("U5"), overlay.linesAt(southOfIt, muenchenOst.lngE7, 400.0))
    }

    // ── the artefact ───────────────────────────────────────────────────────────

    @Test
    fun theArtefactRoundTrips() {
        val decoded = TransitLineIndex.decode(TransitLineIndex.encode(overlay.stops))
        assertEquals(3, decoded?.stops?.size)
        assertEquals(
            setOf("U5"),
            decoded?.linesServing(
                muenchenOst.latE7, muenchenOst.lngE7,
                odeonsplatz.latE7, odeonsplatz.lngE7,
                within,
            ),
        )
    }

    @Test
    fun theStringTableIsSharedRatherThanRepeatedPerStop() {
        // The one size claim worth pinning: a region has thousands of stops and tens of lines, so the
        // refs must not be repeated per stop. 300 stops all serving "U5" must not cost 300 copies.
        val many = (0 until 300).map { TransitLineIndex.Stop(48_000_000 + it * 100, 11_500_000, setOf("U5")) }
        val bytes = TransitLineIndex.encode(many)
        assertTrue(bytes.size < 300 * 14, "shared table expected, was ${bytes.size} bytes for 300 stops")
        assertEquals(300, TransitLineIndex.decode(bytes)?.stops?.size)
    }

    @Test
    fun bytesWeDoNotUnderstandDecodeToNullRatherThanThrowing() {
        // ⚠⚠ A missing or corrupt overlay must leave the leg reading exactly as it does today.
        // Throwing here would turn "we cannot name the line" into "the feed failed".
        assertNull(TransitLineIndex.decode(byteArrayOf()))
        assertNull(TransitLineIndex.decode("not an overlay".encodeToByteArray()))
        val good = TransitLineIndex.encode(overlay.stops)
        assertNull(TransitLineIndex.decode(good.copyOf(good.size / 2)), "a truncated artefact")
        val wrongVersion = good.copyOf().also { it[4] = 99 }
        assertNull(TransitLineIndex.decode(wrongVersion), "a future format version")
    }

    @Test
    fun anEmptyOverlayIsValidAndSaysNothing() {
        val bytes = TransitLineIndex.encode(emptyList())
        val decoded = TransitLineIndex.decode(bytes)
        assertEquals(0, decoded?.stops?.size)
        assertTrue(decoded!!.linesAt(48_100_000, 11_500_000, within).isEmpty())
    }
}
