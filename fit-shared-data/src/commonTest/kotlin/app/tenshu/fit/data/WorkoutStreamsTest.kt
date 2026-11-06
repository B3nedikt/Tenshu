package app.tenshu.fit.data

import app.tenshu.fit.data.projection.FitProjection
import org.tenshu.health.common.HrSample
import org.tenshu.health.common.RouteSample
import org.tenshu.health.common.WorkoutStreams
import org.tenshu.health.common.WorkoutStreamsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The stored `Workout.Streams` blob substrate (`docs/proposals/fit-app.md` §9.7): the codec is a
 * lossless round-trip and the projector folds real GPS into the abstract 0..1 polyline the UI draws.
 */
class WorkoutStreamsTest {

    @Test
    fun codecRoundTripsRouteAndHeartRate() {
        val streams = WorkoutStreams(
            route = listOf(
                RouteSample(tSec = 0, latE7 = 375_000_000, lngE7 = -1_224_000_000, altCm = 1_250),
                RouteSample(tSec = 5, latE7 = 375_010_000, lngE7 = -1_224_010_000, altCm = 1_270),
                RouteSample(tSec = 10, latE7 = 375_020_000, lngE7 = -1_224_005_000), // no altitude
            ),
            heartRate = listOf(HrSample(0, 96), HrSample(5, 120), HrSample(10, 143)),
        )
        val decoded = WorkoutStreamsCodec.decode(WorkoutStreamsCodec.encode(streams))
        assertEquals(streams, decoded)
        assertEquals(RouteSample.NO_ALT, decoded.route[2].altCm, "missing altitude survives as the sentinel")
    }

    @Test
    fun codecHandlesEmptyAndGarbage() {
        assertEquals(WorkoutStreams.EMPTY, WorkoutStreamsCodec.decode(WorkoutStreamsCodec.encode(WorkoutStreams.EMPTY)))
        assertSame(WorkoutStreams.EMPTY, WorkoutStreamsCodec.decode(null))
        // Truncated / non-codec bytes never throw — they decode to empty.
        assertSame(WorkoutStreams.EMPTY, WorkoutStreamsCodec.decode(byteArrayOf(1, 2, 3)))
        assertSame(WorkoutStreams.EMPTY, WorkoutStreamsCodec.decode(ByteArray(64) { 0x7F }))
    }

    @Test
    fun projectRouteNormalisesIntoUnitFrameAndPreservesCount() {
        val route = listOf(
            RouteSample(0, 375_000_000, -1_224_000_000),
            RouteSample(5, 375_050_000, -1_223_950_000),
            RouteSample(10, 375_020_000, -1_224_030_000),
        )
        val projected = FitProjection.projectRoute(route)
        assertEquals(route.size, projected.size)
        assertTrue(projected.all { it.x in 0f..1f && it.y in 0f..1f }, "all points must land in the unit frame")
        // Distinct real fixes stay distinct after projection.
        assertEquals(route.size, projected.toSet().size)
        assertEquals(emptyList(), FitProjection.projectRoute(emptyList()))
    }
}
