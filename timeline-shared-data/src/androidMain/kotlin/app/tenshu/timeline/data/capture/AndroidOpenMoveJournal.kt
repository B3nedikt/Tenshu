package app.tenshu.timeline.data.capture

import android.content.Context
import app.tenshu.outbox.FileOutboxStore
import java.io.File

/**
 * The Android [OpenMoveJournal] — the in-progress journey, checkpointed under the app-private
 * `filesDir/move_journal/` (same posture as the visit outbox: threat-model A35, coordinates never
 * leave app-private storage; encrypted at rest by the platform).
 *
 * ⚠ A **separate directory** from `visit_outbox/` deliberately: `DurableImportWriter.drain` decodes
 * every entry in the visit outbox's store as an `ImportedTimeline` and purges whatever fails, which
 * would quietly eat this record on the first drain.
 *
 * This is what makes an OEM force-kill mid-walk survivable — ColorOS kills the capture foreground
 * service routinely (§11.4), and before this the whole open leg died with the process.
 */
internal fun androidOpenMoveJournal(context: Context): OpenMoveJournal =
    JsonOpenMoveJournal(FileOutboxStore(File(context.applicationContext.filesDir, "move_journal")))

/**
 * The Android **open-dwell** journal (§29) — the stay's twin of [androidOpenMoveJournal].
 *
 * ⚠ Its **own** directory, not a second record in the move journal's: the two are written at different
 * moments and cleared on different events, and sharing a store would make one's corruption purge the
 * other's record. The entry id differs too, so even a mis-wired store cannot cross them.
 */
internal fun androidOpenDwellJournal(context: Context): OpenMoveJournal =
    JsonOpenMoveJournal(
        store = FileOutboxStore(File(context.applicationContext.filesDir, "dwell_journal")),
        entryId = JsonOpenMoveJournal.DWELL_ENTRY_ID,
    )
