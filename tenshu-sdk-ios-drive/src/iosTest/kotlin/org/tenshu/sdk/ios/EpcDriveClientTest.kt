package org.tenshu.sdk.ios

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.transport.TaskTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless coverage of the Drive-on-iOS live-EPC assembly ([EpcDriveClient.assembleClient]) on the
 * `iosSimulatorArm64Test` gate — the twin of `EpcFitClientTest` / `EpcTimelineClientTest`.
 *
 * It replaces `EpcDriveClientSmoke`, which ran the same two checks inside the DEV_HARNESS app's
 * Smokes tab and so only ever ran when someone launched that scheme (deleted 2026-11-05 with the
 * tab). [EpcDriveClient] stays — it is the §8 remote-proxy client — so its assembly keeps a guard:
 *
 *  1. the **full client graph assembles on iOS** — every service schema config + the remote
 *     `TenshuEnvironment`/`TenshuClient` + a real `TenshuFilesRepository` resolve without error;
 *  2. a real domain op (`createFolder`) **marshals down to `TaskTransport.call`**.
 *
 * The live op→reply round-trip needs a Control serving EPC over a reachable relay — the on-device
 * gate. Here the transport records the call and fails (no proxy).
 */
class EpcDriveClientTest {

    /** Records that an op reached the transport (and its size); fails the call (no proxy in the test). */
    private class RecordingTransport : TaskTransport {
        var called = false
        var lastRequestSize = 0
        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
        override suspend fun call(request: ByteArray): ByteArray {
            called = true
            lastRequestSize = request.size
            throw RuntimeException("no proxy in test")
        }
    }

    @Test
    fun full_client_and_files_repository_assemble_over_the_channel() {
        val drive = EpcDriveClient.assembleClient(RecordingTransport())
        // Reaching here means the whole graph (every service schema + environment + client + repo)
        // resolved on iOS; the two handles are what `LiveClientSession` installs.
        assertTrue(drive.tenshuClient::class.simpleName != null)
        assertTrue(drive.filesRepository::class.simpleName != null)
    }

    @Test
    fun a_file_op_marshals_to_transport_call() = runTest {
        val transport = RecordingTransport()
        val drive = EpcDriveClient.assembleClient(transport)
        try {
            drive.filesRepository.createFolder("/Smoke")
        } catch (_: Throwable) {
            // Expected — the recording transport has no proxy to reply.
        }
        assertTrue(transport.called, "createFolder did not reach TaskTransport.call")
        assertTrue(transport.lastRequestSize > 0, "createFolder sent an empty request")
    }
}
