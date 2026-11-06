package app.tenshu.notify

/** What has to change for the OS to be holding exactly the desired set. */
data class AlertDiff(
    val schedule: List<ScheduledAlert>,
    val cancel: List<String>,
) {
    val isEmpty: Boolean get() = schedule.isEmpty() && cancel.isEmpty()
}

/**
 * Turns "here is what should be scheduled" into "add these, remove those".
 *
 * ⭐⭐ **The shape is borrowed, not invented.** Control's macOS tray agent has
 * had `reconcile(_ current:)` since long before Calendar existed — diff a
 * desired set against a known one, post the arrivals, remove the departures by
 * stable id (`calendar-app.md` §4.6). What was missing is that it lives in
 * Swift, so Android would have written a second one. This is that logic in
 * common Kotlin, where it is unit-testable and where both platforms read the
 * same answer.
 *
 * ⚠⚠ **Re-scheduling an unchanged alert is not free and must not happen.** On
 * Apple platforms it can re-deliver one the user has already seen, and on
 * Android it is a wasted alarm. So the diff compares *content*, not just ids: an
 * alert whose text or time changed is re-scheduled, and one that did not is left
 * alone. Comparing ids only would either re-post everything every tick or never
 * update a changed reminder — both wrong, in opposite directions.
 */
object AlertReconciler {

    /**
     * The change needed to move the OS from [known] to [desired].
     *
     * @param known what the OS is actually holding. ⚠ Ask the platform for this
     *   rather than remembering it: an alert handed to the OS outlives our
     *   process, so a remembered set would re-schedule everything after a
     *   restart — which is the bug the Swift version absorbs only because its
     *   ids are stable.
     */
    fun diff(desired: List<ScheduledAlert>, known: Collection<ScheduledAlert>): AlertDiff {
        val knownById = known.associateBy { it.id }
        val desiredIds = desired.mapTo(mutableSetOf()) { it.id }

        return AlertDiff(
            schedule = desired.filter { knownById[it.id] != it },
            // ⚠ Sorted so the result is deterministic — a diff whose cancel list
            // shuffled between runs would make a test flaky for no reason and
            // make a log impossible to read.
            cancel = knownById.keys.filterNot { it in desiredIds }.sorted(),
        )
    }

    /**
     * The change needed when the platform can only report **ids**, not content.
     *
     * ⚠⚠ A separate entry point rather than a lossy adapter, because the two
     * answer different questions. Given ids alone, an alert already scheduled
     * *must be re-scheduled anyway* — there is no way to tell whether its text
     * changed, and silently keeping a stale one is worse than a redundant write.
     * A caller on such a platform should know it is paying that, which a
     * parameter named `known: Set<String>` makes obvious and an overload hides.
     */
    fun diffAgainstIds(desired: List<ScheduledAlert>, knownIds: Set<String>): AlertDiff {
        val desiredIds = desired.mapTo(mutableSetOf()) { it.id }
        return AlertDiff(
            schedule = desired,
            cancel = knownIds.filterNot { it in desiredIds }.sorted(),
        )
    }
}
