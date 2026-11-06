package app.tenshu.timeline.data.projection

import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION
import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.PlaceInput
import app.tenshu.timeline.data.projection.TimelineProjection.TraceInput
import app.tenshu.timeline.model.MovementModes
import org.tenshu.core.place.LabelSource
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TR-2 — **a stop the legs either side both name is a station, and may be held longer**
 * (`timeline-movement-capture.md` §38.4).
 *
 * ### What this is for
 *
 * `STATION_STOP_MAX_MS` is 12 minutes, priced against *"swallowing a real 20-minute errand between
 * two train legs"* — a risk assessed without knowing where the stop was. The reported Hörlkofen wait
 * was **10.0 minutes**: the fold worked, with two minutes to spare, and a slightly longer crossing
 * wait on a single-track line would have split the ride for a reason unrelated to the journey.
 *
 * ⭐ The evidence needed to price that risk properly was already flowing through the projection and
 * unread — `withTransitStops` resolves every transit leg's `originStop`/`destinationStop` from the
 * POI layer, so an arriving leg and a departing leg naming the *same* stop identify the platform
 * between them at no extra cost.
 *
 * ⚠⚠ **The blank case is the one that matters**, and it is most of them. `nearestTransitStop`
 * returns `""` for *both* "no stop nearby" and "could not tell" (§36.6a), and a leg ending mid-line
 * between two stations is legitimately nameless. [aStopNeitherLegCanNameKeepsTheOldCap] is what
 * pins that no evidence means today's behaviour rather than a wider door.
 *
 * ⚠⚠ **§39.8 split that blank case in two, and reversed half of it.** *Neither* end naming a stop is
 * still no evidence; *one* end naming it while the other is silent is now evidence, because blank is
 * silence rather than a competing claim — see [oneNamedEndAndOneSilentEndIsEvidence] for the
 * measurement that forced it, and [twoDifferentStationsAreNotAPassThrough] for where it stops — silence widens the cap, a
 * contradiction still refuses.
 */
class StationStopEvidenceTest {

    private val dayStart = 1_799_971_200_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val home = PlaceInput("p-home", "Home", latE7 = 481_500_000, lngE7 = 115_860_000)
    private val work = PlaceInput("p-work", "Work", latE7 = 482_000_000, lngE7 = 115_860_000)
    private val platform = PlaceInput("p-stn", "Hörlkofen")

    private fun visit(id: String, atMs: Long, durSec: Long, place: PlaceInput) = MomentInput(
        id, atMs, TimelineProjection.Kind.VISIT, place.label, durSec,
        subjectKind = TimelineProjection.Subject.PLACE, subjectId = place.id,
    )

    private fun leg(id: String, atMs: Long, durationMs: Long) = MomentInput(
        id, atMs, TimelineProjection.Kind.MOVEMENT, "Transit · 20.0 km", durationMs / 1000,
        subjectKind = TimelineProjection.Subject.TRACE, subjectId = "t-$id",
    )

    private fun projected(moments: List<MomentInput>, traces: List<TraceInput>) =
        TimelineProjection.days(
            moments, places = listOf(home, work, platform), traces = traces, zone = TimeZone.UTC,
        ).flatMap { it.moments }

    /**
     * Train, a [stopMinutes]-long wait at a platform, train — with the stop names the two legs
     * resolved. Blank names are the ordinary case and the default here.
     */
    private fun journey(
        stopMinutes: Long,
        arrivingNames: String = "",
        departingNames: String = "",
    ): Pair<List<MomentInput>, List<TraceInput>> {
        val aStart = dayStart + hour
        val aEnd = aStart + 40 * minute
        val bStart = aEnd + stopMinutes * minute
        val bEnd = bStart + 30 * minute
        return listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("a", aStart, aEnd - aStart),
            visit("stop", aEnd, stopMinutes * 60, platform),
            leg("b", bStart, bEnd - bStart),
            visit("v-work", bEnd, 3_600, work),
        ) to listOf(
            TraceInput(
                "t-a", MovementModes.TRANSIT, 44_290,
                classifierVersion = MOVEMENT_CLASSIFIER_VERSION, destinationStop = arrivingNames,
            ),
            TraceInput(
                "t-b", MovementModes.TRANSIT, 29_310,
                classifierVersion = MOVEMENT_CLASSIFIER_VERSION, originStop = departingNames,
            ),
        )
    }

    private fun idsFor(
        stopMinutes: Long,
        arriving: String = "",
        departing: String = "",
    ): List<String> {
        val (moments, traces) = journey(stopMinutes, arriving, departing)
        return projected(moments, traces).map { it.id }
    }

    // ── the case TR-2 shipped for ────────────────────────────────────────────

    @Test
    fun aConfirmedStationHoldsThroughALongerWait() {
        // 16 minutes — past the 12-minute cap, inside the 20-minute one. A single-track crossing
        // wait, which is scheduled rather than exceptional on this kind of line.
        assertEquals(
            listOf("v-home", "a", "v-work"),
            idsFor(16, arriving = "Hörlkofen", departing = "Hörlkofen"),
            "both legs name the same platform, so the wait is a station hold",
        )
    }

    // ── the guards ───────────────────────────────────────────────────────────

    @Test
    fun aStopNeitherLegCanNameKeepsTheOldCap() {
        // ⚠⚠ The ordinary case, and the one that must not change. A blank means "no stop" OR "could
        // not tell", and a leg ending mid-line is legitimately nameless — so no evidence has to mean
        // today's rule, never a wider door.
        assertEquals(listOf("v-home", "a", "stop", "b", "v-work"), idsFor(16))
        // …and the short wait still folds exactly as it always did.
        assertEquals(listOf("v-home", "a", "v-work"), idsFor(7))
    }

    @Test
    fun oneNamedEndAndOneSilentEndIsEvidence() {
        // ⚠⚠ REVERSES this file's own `oneNamedLegIsNotEvidence` (§39.8). That test read a blank end
        // as disagreement — "half a signal is not a signal" — and the type's KDoc already said why
        // that is wrong: blank means *no stop within TRANSIT_STOP_RADIUS_M*, i.e. silence, not a
        // competing claim. One name plus one silence is one confirmation and one unknown.
        //
        // Measured on the 2026-09-07 Mühldorf → München Ost ride, which the old rule split: the
        // arriving leg's end sat 217 m from Schwindegg (named it) and the departing leg's start sat
        // 531 m away (blank) — a fix whose OWN reported accuracy was ±300 m, kept as a terminal fix
        // by §32.6. The journey was cut by 231 m of coordinate error, not by anything about the stay.
        assertEquals(
            listOf("v-home", "a", "v-work"),
            idsFor(16, arriving = "Hörlkofen"),
            "the arriving leg named the platform and the departing end was merely silent",
        )
        // ⚠ Symmetric on purpose: which end happens to be the coarse fix is an accident of sampling.
        assertEquals(
            listOf("v-home", "a", "v-work"),
            idsFor(16, departing = "Hörlkofen"),
            "…and the mirror case, where the departing leg is the one that can name it",
        )
    }

    @Test
    fun twoDifferentStationsAreNotAPassThrough() {
        // Arriving at one station and departing from another means ground was covered during the
        // stop. However station-like both ends look, that is not a stay the rider sat through.
        // ⚠⚠ Since §39.8 this is also the BOUNDARY of the silence rule: a blank end widens the cap,
        // a contradicting one must not. Drop `arriving == departing` from stationStopCapFor and this
        // is the single test that goes red.
        assertEquals(
            listOf("v-home", "a", "stop", "b", "v-work"),
            idsFor(16, arriving = "Hörlkofen", departing = "Markt Schwaben"),
        )
    }

    @Test
    fun evenAConfirmedStationHasACeiling() {
        // The widened cap is a cap, not a licence. Half an hour at a platform is long enough that
        // "you were at a place" is the better description, and the row should say so.
        assertEquals(
            listOf("v-home", "a", "stop", "b", "v-work"),
            idsFor(30, arriving = "Hörlkofen", departing = "Hörlkofen"),
        )
    }

    @Test
    fun aUserLabelledStationStillBlocksTheFold() {
        // ⚠ TR-2 relaxes ONE guard. The others are untouched, and this is the one that makes the
        // whole feature safe: if the user named the place, it is meaningful whatever the tiles say.
        val (moments, traces) = journey(16, "Hörlkofen", "Hörlkofen")
        val named = PlaceInput("p-stn", "Hörlkofen", labelSource = LabelSource.USER)
        val rows = TimelineProjection.days(
            moments, places = listOf(home, work, named), traces = traces, zone = TimeZone.UTC,
        ).flatMap { it.moments }
        assertEquals(listOf("v-home", "a", "stop", "b", "v-work"), rows.map { it.id })
    }
}
