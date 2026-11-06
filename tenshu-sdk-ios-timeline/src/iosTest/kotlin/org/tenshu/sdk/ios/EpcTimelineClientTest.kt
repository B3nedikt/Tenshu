package org.tenshu.sdk.ios

import app.tenshu.timeline.data.TimelineProjectionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.transport.TaskTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Headless coverage of the Timeline-on-iOS live-EPC assembly ([EpcTimelineClient.assembleClient] +
 * [TimelineSurface.installRealClient]) on the `iosSimulatorArm64Test` gate — the Timeline twin of
 * [EpcFitClientTest]. It proves the two things verifiable without a live Control:
 *
 *  1. the **full Timeline client graph assembles on iOS** — every service schema config *including
 *     `:timeline-service`/`:place-service`/`:health-service`* + the remote
 *     `TenshuEnvironment`/`TenshuClient` + a real [TimelineProjectionRepository] resolve without error;
 *  2. a real read (`trip(id)`) **marshals down to `TaskTransport.call`** — the op path is wired
 *     end-to-end from the repository through the full schema to the channel.
 *
 * The live op→reply (and op→push→Flow) round-trip needs a Control serving EPC over a reachable relay —
 * the on-device gate. Here the transport records the call and fails (no proxy), which is enough to show
 * the request was serialized and dispatched.
 */
class EpcTimelineClientTest {

    /** Records that an op reached the transport (and its size); fails the call (no proxy in the test). */
    private class RecordingTransport : TaskTransport {
        var called = false
        var lastRequestSize = 0
        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override fun cancel(taskId: String) {}
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
        override suspend fun call(request: ByteArray): ByteArray {
            called = true
            lastRequestSize = request.size
            throw RuntimeException("no proxy in test")
        }
    }

    @Test
    fun full_client_and_projection_repository_assemble_over_the_channel() {
        val timeline = EpcTimelineClient.assembleClient(RecordingTransport())
        // Reaching here means the whole graph (every service schema incl. timeline/place/health +
        // environment + client + projector) resolved on iOS.
        assertTrue(
            timeline.timelineRepository is TimelineProjectionRepository,
            "assembleClient should install the real TimelineProjectionRepository",
        )
    }

    @Test
    fun installRealClient_swaps_the_surface_onto_the_projector() {
        TimelineSurface.installRealClient(RecordingTransport())
        assertTrue(
            TimelineSurface.installedRepository() is TimelineProjectionRepository,
            "installRealClient should swap TimelineSurface off the mock",
        )
        TimelineSurface.installMock() // restore for sibling tests
    }

    @Test
    fun a_read_marshals_to_transport_call() = runTest {
        val transport = RecordingTransport()
        val timeline = EpcTimelineClient.assembleClient(transport)
        try {
            // A one-shot read over the full schema; the recording transport has no proxy to reply, so
            // the projector folds the failure to null — but the op has already reached transport.call.
            timeline.timelineRepository.trip("test-trip")
        } catch (_: Throwable) {
            // Tolerated — dispatch reached the transport, which threw.
        }
        assertTrue(transport.called, "trip(id) did not reach TaskTransport.call")
    }

    /**
     * The runtime fallback surface is the honest empty one, NOT the fabricated mock — the Timeline
     * twin of `EpcFitClientTest`'s not-connected assertion (fit-app.md §11.7). An unprovisioned
     * Timeline used to render `MockTimelineRepository`'s invented places and trips as if they were
     * the user's own.
     */
    @Test
    fun the_not_connected_surface_is_empty_and_flags_control_unreachable() = runTest {
        TimelineSurface.installNotConnected()
        val repo = TimelineSurface.installedRepository()
        assertTrue(
            repo is NotConnectedTimelineRepository,
            "the not-connected surface must not be the mock",
        )
        assertEquals(false, repo.controlReachable().value, "controlReachable must be false → banner")
        assertTrue(!TimelineSurface.controlReachable(), "the Swift shell's banner read must agree")
        assertTrue(repo.homeDays().first().isEmpty(), "home feed must be empty, never fabricated")
        assertTrue(repo.places().first().isEmpty(), "places must be empty, never fabricated")
        assertTrue(repo.boundTrips().first().isEmpty(), "trips must be empty, never fabricated")
        assertNull(repo.tripSuggestion().first(), "must not suggest a fabricated trip")
        TimelineSurface.installMock() // restore for sibling tests
        assertTrue(TimelineSurface.controlReachable(), "the mock surface is 'reachable' — no banner")
    }
}
