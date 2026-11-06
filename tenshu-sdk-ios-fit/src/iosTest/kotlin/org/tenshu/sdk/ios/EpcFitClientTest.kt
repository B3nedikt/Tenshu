package org.tenshu.sdk.ios

import app.tenshu.fit.data.FitProjectionRepository
import app.tenshu.fit.data.IosRecordingEngine
import app.tenshu.fit.data.MockRecordingEngine
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.tenshu.core.common.transport.TaskTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless coverage of the Fit-on-iOS live-EPC assembly ([EpcFitClient.assembleClient] +
 * [FitSurface.installRealClient]) on the `iosSimulatorArm64Test` gate — the Fit twin of
 * `EpcDriveClientTest`. It proves the two things verifiable without a live Control:
 *
 *  1. the **full Fit client graph assembles on iOS** — every service schema config *including
 *     `:health-service`* + the remote `TenshuEnvironment`/`TenshuClient` + a real
 *     [FitProjectionRepository] resolve without error (a missing/broken schema or wiring throws here);
 *  2. a real health op (`addWorkout` → `recordWorkout`) **marshals down to `TaskTransport.call`** — the
 *     op path is wired end-to-end from the repository through the full schema to the channel.
 *
 * The live op→reply (and op→push→Flow) round-trip needs a Control serving EPC over a reachable relay —
 * the on-device gate. Here the transport records the call and fails (no proxy), which is enough to show
 * the request was serialized and dispatched.
 */
class EpcFitClientTest {

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
        val fit = EpcFitClient.assembleClient(RecordingTransport())
        // Reaching here means the whole graph (every service schema incl. health + environment + client
        // + projector) resolved on iOS.
        assertTrue(
            fit.fitRepository is FitProjectionRepository,
            "assembleClient should install the real FitProjectionRepository",
        )
    }

    @Test
    fun installRealClient_swaps_the_surface_onto_the_projector() {
        FitSurface.installRealClient(RecordingTransport())
        assertTrue(
            FitSurface.installedRepository() is FitProjectionRepository,
            "installRealClient should swap FitSurface off the mock",
        )
        FitSurface.installMock() // restore for sibling tests
    }

    @Test
    fun installRealClient_swaps_the_recorder_onto_the_real_engine() {
        // A live client records for real (R-iOS-1): the recorder factory swaps off the mock ticker
        // onto the CoreLocation/CoreMotion/CoreBluetooth engine. Building the engine is
        // side-effect-free (managers are lazy) — assert the type without arming any iOS sensor.
        FitSurface.installRealClient(RecordingTransport())
        assertTrue(
            FitSurface.buildRecordingEngine() is IosRecordingEngine,
            "installRealClient should bind the real IosRecordingEngine",
        )
        FitSurface.installMock()
        assertTrue(
            FitSurface.buildRecordingEngine() is MockRecordingEngine,
            "installMock should restore the mock recorder",
        )
    }

    @Test
    fun a_health_op_marshals_to_transport_call() = runTest {
        val transport = RecordingTransport()
        val fit = EpcFitClient.assembleClient(transport)
        val at = LocalDateTime(2026, 7, 9, 8, 0, 0)
        try {
            fit.fitRepository.addWorkout(
                Workout(
                    workoutId = "test-workout",
                    activityType = ActivityType.Running,
                    source = Source.Manual,
                    title = "Smoke run",
                    device = "Fit",
                    start = at,
                    end = at,
                    durationSec = 1,
                ),
            )
        } catch (_: Throwable) {
            // Expected — the recording transport has no proxy to reply.
        }
        assertTrue(transport.called, "addWorkout did not reach TaskTransport.call")
    }

    @Test
    fun default_and_teardown_surface_is_not_connected_not_mock() = runTest {
        // fit-app.md §11.7: the runtime not-connected surface is empty + controlReachable=false
        // (drives the banner), NOT the fabricated mock. installMock stays for tests/previews only.
        FitSurface.installNotConnected()
        val repo = FitSurface.installedRepository()
        assertTrue(repo is NotConnectedFitRepository, "not-connected surface should not be the mock")
        assertEquals(false, repo.controlReachable().value, "controlReachable must be false → banner")
        assertTrue(repo.workouts().first().isEmpty(), "not-connected reads are empty, not fabricated")
        FitSurface.installMock() // restore for sibling tests
    }

    @Test
    fun runBackgroundRefresh_is_a_noop_over_the_mock() = runTest {
        FitSurface.installMock()
        assertEquals("bg-refresh: not live", FitSurface.runBackgroundRefresh(maxWaitSeconds = 0))
    }

    @Test
    fun runBackgroundRefresh_runs_over_a_live_client() = runTest {
        // R-iOS-5: over a live client the refresh drains the recorder outbox (the transport records
        // the drain's op and fails — no proxy — so the drain retains + returns) and gates the import
        // on autoRefreshReady (false in the bare test host: no NSHealthShareUsageDescription). It
        // never throws, and returns a drain status rather than the mock's "not live".
        FitSurface.installRealClient(RecordingTransport())
        val status = FitSurface.runBackgroundRefresh(maxWaitSeconds = 0)
        assertTrue(status.startsWith("bg-refresh: drained"), "expected a drain status, got: $status")
        FitSurface.installMock() // restore for sibling tests
    }
}
