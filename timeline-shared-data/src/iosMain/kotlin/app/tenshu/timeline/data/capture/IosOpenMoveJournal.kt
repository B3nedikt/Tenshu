package app.tenshu.timeline.data.capture

import app.tenshu.outbox.NsFileOutboxStore
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/**
 * The iOS [OpenMoveJournal] — the in-progress journey, checkpointed into the app's Application
 * Support container (the `iosRecordedWorkoutOutbox` shape). **Application Support, not Caches**, so
 * iOS never reclaims an open leg under storage pressure; [NsFileOutboxStore] creates the directory
 * on first write and its writes are atomic.
 *
 * iOS needs this more than Android does: there is no long-lived capture process here at all — the
 * `CLVisit`/significant-change monitors relaunch the app per event and it is suspended again
 * between them, so the segmenter's buffer previously died on *every* inter-event process teardown,
 * not merely on an OEM kill.
 */
internal fun iosOpenMoveJournal(subdir: String = "move_journal"): OpenMoveJournal {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .filterIsInstance<String>()
        .firstOrNull()
        ?: NSTemporaryDirectory()
    return JsonOpenMoveJournal(NsFileOutboxStore("$base/$subdir"))
}
