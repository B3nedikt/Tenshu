package app.tenshu.home.data

import app.tenshu.home.model.ActivityEvent
import app.tenshu.home.model.ActivityKind
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.routine.RoutineRun

/**
 * The Activity feed over the engine's own receipts (`routines-engine.md` RE-1, `ADR 0025`).
 *
 * ⭐ **The receipt and the history row are the same row.** `RoutineRun` carries its outcome, so
 * there is no second history type and no risk of the two disagreeing — the doc's own words, now
 * wired.
 *
 * ## ⚠⚠ One of three kinds, and the tab says so
 *
 * `ActivityKind` has three members and only this one has a source:
 *
 * - **`Automation`** — these rows. Shipped.
 * - **`Device`** ("Person detected") — ⚠⚠ **nothing stores device events.** `home-device-service`
 *   holds `HomeDevice`, `DeviceState` and `DeviceCommand`: state is a *current value*, not a
 *   history. The only event stream that exists is `LocalDeviceEventSource`, which routines-engine
 *   defines as a live trigger seam, bridge-host only, with deliberately just "a short replay tail".
 *   Persisting them is a new schema decision with a retention question attached — a motion sensor
 *   is a firehose — and is designed in `docs/proposals/home-device-event-history.md` (DE-1), which
 *   caps the log by construction and admits only discrete transitions.
 * - **`Voice`** — specified as "from M3"; the voice pipeline does not exist.
 *
 * ⚠ So this repository deliberately returns **only automation rows**, and the screen's empty state
 * names what is missing rather than implying the home was quiet. Replacing the mock with a feed
 * that silently covers a third of what it used to show would be the fake-as-a-default failure in a
 * new costume.
 */
class SyncedHomeActivityRepository(
    private val source: RoutineSource,
) : HomeActivityRepository {

    override fun events(): Flow<List<ActivityEvent>> =
        source.runs().map { runs -> runs.mapNotNull { it.toActivityEvent() } }

    /**
     * ⚠ A `Claimed` row is dropped: it is a firing that has started and not yet reported, so it has
     * no outcome to describe. It becomes `Ran` or `Failed` moments later and the feed picks it up
     * then — showing "started…" would be a row that changes under the reader for no benefit.
     */
    private fun SyncedRoutineRunInfo.toActivityEvent(): ActivityEvent? {
        val detail = when (state) {
            RoutineRun.STATE_RAN -> "Ran"
            RoutineRun.STATE_FAILED -> "Failed"
            // ⚠ Named distinctly rather than folded into "Failed": a skipped routine's conditions
            // were false, which is it working correctly, and a user told "failed" would go looking
            // for a fault that is not there.
            RoutineRun.STATE_SKIPPED -> "Conditions not met"
            // ⚠⚠ The device was asleep or off when the firing was due. Worth its own word: it is
            // the one outcome that says the *home* did not do the thing, rather than the routine
            // deciding not to.
            RoutineRun.STATE_MISSED -> "Missed — the device was not awake"
            RoutineRun.STATE_THROTTLED -> "Throttled — it fired too often"
            else -> return null
        }
        return ActivityEvent(
            id = firingId,
            at = Instant.fromEpochMilliseconds(claimedAtEpochMs),
            kind = ActivityKind.Automation,
            // ⚠ A deleted routine leaves its firings behind; the feed keeps them and says so.
            title = routineName.ifBlank { "A deleted routine" },
            detail = detail,
        )
    }
}
