package app.tenshu.timeline.data.projection

import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.TraceInput
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.MovementModes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **§33 L5 — the line reaches the row.**
 *
 * ⚠⚠ The property worth pinning is not that a line *appears*, it is that a **blank** one changes
 * nothing. Every failure in the overlay chain — no coverage, no artefact, a fetch that failed —
 * arrives here as `""`, and the row must then read byte-for-byte as it did before overlays existed.
 * A line label is an enrichment; a regression in the ordinary no-overlay case would be a far worse
 * outcome than never showing one.
 */
class TransitLineRowTest {

    private val t0 = 1_800_000_000_000L

    private fun movement(id: String = "m1") = MomentInput(
        id = id,
        timestampMs = t0,
        kind = MomentKind.Movement.name,
        title = "Transit · 2.9 km",
        durationSec = 458,
        subjectKind = TimelineProjection.Subject.TRACE,
        subjectId = "cap-trace:1",
    )

    private fun trace(line: String) = TraceInput(
        id = "cap-trace:1",
        mode = MovementModes.TRANSIT,
        distanceM = 2_941,
        line = line,
    )

    private fun rowLine(line: String): String {
        val days = TimelineProjection.days(
            moments = listOf(movement()),
            places = emptyList(),
            workouts = emptyList(),
            traces = listOf(trace(line)),
        )
        return days.single().moments.single().movementLine
    }

    @Test
    fun aResolvedLineReachesTheRow() {
        assertEquals("U5", rowLine("U5"))
    }

    @Test
    fun anAmbiguousSetReachesTheRowAsTheSettledLabel() {
        // The shared-trunk case. The repository renders it through `TransitLineIndex.label`, so the
        // projection carries a string that is already the decided form rather than a set to re-decide.
        assertEquals("U3/U6", rowLine("U3/U6"))
    }

    @Test
    fun noOverlayLeavesTheRowExactlyAsItWas() {
        // ⚠⚠ The regression guard. Blank is the ordinary case for every region without an artefact.
        assertEquals("", rowLine(""))
    }

    @Test
    fun aTraceWithNoLineFieldAtAllStillProjects() {
        // Defaulted rather than nullable, so a trace read by an older path (or a fixture written
        // before L5) projects unchanged instead of failing.
        val days = TimelineProjection.days(
            moments = listOf(movement()),
            places = emptyList(),
            workouts = emptyList(),
            traces = listOf(TraceInput(id = "cap-trace:1", mode = MovementModes.TRANSIT, distanceM = 2_941)),
        )
        assertEquals("", days.single().moments.single().movementLine)
        assertEquals(2_941, days.single().moments.single().distanceM)
    }
}
