package app.tenshu.drive.ui

import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.ui.common.addfileactions.FolderUploadOutcome
import app.tenshu.drive.ui.common.addfileactions.FolderUploadState
import app.tenshu.drive.ui.common.folderupload.FolderUploadLiveness
import app.tenshu.drive.ui.common.folderupload.FolderUploadNotificationContent
import app.tenshu.drive.ui.common.folderupload.FolderUploadNotificationHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android-free idle ⇄ in-flight ⇄ terminal edge logic of
 * [FolderUploadLiveness] (mirrors Control's `ForegroundTaskLivenessTest`).
 * The actual Android effect is `FolderUploadForegroundService`, not locally
 * compilable here — this exercises the seam's transitions in isolation.
 */
class FolderUploadLivenessTest {

    private sealed interface Event {
        data class Active(val title: String) : Event
        data class Changed(val title: String) : Event
        data class Terminal(val title: String) : Event
        data object Idle : Event
    }

    private class Recording : FolderUploadNotificationHandle {
        val events = mutableListOf<Event>()
        override fun onActive(content: FolderUploadNotificationContent) {
            events += Event.Active(content.title)
        }

        override fun onContentChanged(content: FolderUploadNotificationContent) {
            events += Event.Changed(content.title)
        }

        override fun onTerminal(content: FolderUploadNotificationContent) {
            events += Event.Terminal(content.title)
        }

        override fun onIdle() {
            events += Event.Idle
        }
    }

    private fun inFlight(completed: Int, total: Int) =
        FolderUploadState(UploadProgress("f", completed, total))

    @Test
    fun idleToInFlight_promotesOnce_thenRefreshes_thenTerminal_thenIdle() {
        val flow = MutableStateFlow<FolderUploadState?>(null)
        val rec = Recording()
        val liveness = FolderUploadLiveness(flow, rec)

        // Drive the edges directly (collection is just a thin StateFlow loop).
        liveness.onState(null)                       // INITIAL idle → onIdle (stops a service started for already-finished work)
        liveness.onState(inFlight(0, 3))             // → onActive
        liveness.onState(inFlight(1, 3))             // → onContentChanged
        liveness.onState(inFlight(2, 3))             // → onContentChanged
        liveness.onState(
            FolderUploadState(
                progress = UploadProgress("f", 3, 3),
                outcome = FolderUploadOutcome.Completed(FolderUploadSummary(3, 3, 0, 0)),
            ),
        )                                            // → onTerminal
        liveness.onState(null)                       // controller cleared → onIdle

        assertEquals(
            listOf(
                Event.Idle, // INITIAL idle (crashloop-fix contract)
                Event.Active("Uploading file 1 of 3"),
                Event.Changed("Uploading file 2 of 3"),
                Event.Changed("Uploading file 3 of 3"),
                Event.Terminal("Uploaded 3 files"),
                Event.Idle,
            ),
            rec.events,
        )
    }

    @Test
    fun terminalResetsActive_soARestartRePromotes() {
        val flow = MutableStateFlow<FolderUploadState?>(null)
        val rec = Recording()
        val liveness = FolderUploadLiveness(flow, rec)

        liveness.onState(inFlight(0, 1))
        liveness.onState(
            FolderUploadState(
                progress = UploadProgress("f", 0, 1),
                outcome = FolderUploadOutcome.Cancelled(UploadProgress("f", 0, 1)),
            ),
        )
        // A new upload starts before the controller cleared state.
        liveness.onState(inFlight(0, 2))

        assertEquals(
            listOf(
                Event.Active("Uploading file 1 of 1"),
                Event.Terminal("Upload cancelled — 0 of 1 uploaded"),
                Event.Active("Uploading file 1 of 2"),
            ),
            rec.events,
        )
    }

    @Test
    fun idleFiresOnceThenRepeatsAreSilent() {
        // New contract (Fit launch-crashloop fix): the INITIAL idle delivers exactly one onIdle —
        // a service started for work that finished before the collector attached must stop —
        // and repeated idles stay silent.
        val rec = Recording()
        val liveness = FolderUploadLiveness(MutableStateFlow(null), rec)
        liveness.onState(null)
        liveness.onState(null)
        assertEquals(listOf<Event>(Event.Idle), rec.events)
    }
}
