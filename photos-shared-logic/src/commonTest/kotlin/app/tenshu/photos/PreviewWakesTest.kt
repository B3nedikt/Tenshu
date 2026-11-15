package app.tenshu.photos

import app.tenshu.photos.data.previewWakes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.sync.SyncHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** `suite-sync-status.md` SS-3: when a grey tile should ask again at once. */
class PreviewWakesTest {

    @Test
    fun syncComingBack_wakesOnce_andAStartAlreadyOnlineDoesNot() = runTest {
        val connectivity = MutableStateFlow<String?>(SyncHealthStatus.CONNECTIVITY_ONLINE)
        val wakes = mutableListOf<Unit>()
        val job = launch { previewWakes(connectivity, flowOf(false)).toList(wakes) }
        runCurrent()
        assertEquals(0, wakes.size, "the first value is not a recovery")

        connectivity.value = SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED
        runCurrent()
        assertEquals(0, wakes.size, "going away wakes nothing")

        connectivity.value = SyncHealthStatus.CONNECTIVITY_ONLINE
        runCurrent()
        assertEquals(1, wakes.size, "coming back wakes the tiles")

        // Retrying is reachable too: the relay answers, so no second wake, and no wake on the way.
        connectivity.value = SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING
        runCurrent()
        assertEquals(1, wakes.size)

        connectivity.value = SyncHealthStatus.CONNECTIVITY_OFFLINE
        runCurrent()
        connectivity.value = SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING
        runCurrent()
        assertEquals(2, wakes.size, "offline → retrying is a recovery")
        job.cancel()
    }

    @Test
    fun aCatchUpEnding_wakes_andStartingOneDoesNot() = runTest {
        val catchingUp = MutableStateFlow(false)
        val wakes = mutableListOf<Unit>()
        val job = launch {
            previewWakes(flowOf(SyncHealthStatus.CONNECTIVITY_ONLINE), catchingUp).toList(wakes)
        }
        runCurrent()
        catchingUp.value = true
        runCurrent()
        assertEquals(0, wakes.size)
        catchingUp.value = false
        runCurrent()
        assertEquals(1, wakes.size, "the rows just pulled are worth asking for")
        job.cancel()
    }
}
