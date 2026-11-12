package app.tenshu.control.bridge

/**
 * What the Explorer folder is doing, for the line under its Settings switch (X4, desktop-windows-port.md §7.11).
 * [pending]: entries the folder holds that the realm does not have yet — uploads, edits, deletes and renames in
 * flight. [failures]: the ones that cannot get there now, each with why; counted in [pending] too.
 */
data class ExplorerStatus(
    val state: State,
    val pending: Int = 0,
    val failures: List<Failure> = emptyList(),
) {
    enum class State {
        /** The switch is off and the folder is gone. */
        OFF,

        /** On, but not serving yet: Control is starting, or Windows refused and the start is retried. */
        STARTING,

        UP_TO_DATE,

        /** Something of the folder's is on its way to the realm. */
        SYNCING,

        /** The realm cannot be listed right now; the folder waits. */
        UNREACHABLE,

        /** Switched off, and a downloaded copy could not be removed yet (open in an app); retried every minute. */
        CLEANING_UP,
    }

    data class Failure(val relative: String, val reason: Reason)

    enum class Reason {
        /** Over the upload limit: it stays local only. */
        TOO_LARGE,

        /** An upload, delete or rename that failed; tried again shortly. */
        RETRYING,
    }

    companion object {
        /** [failures] that are not [pending] any more are gone: fixed, moved or deleted. */
        fun of(pending: Set<String>, failures: Map<String, Reason>, unreachable: Boolean): ExplorerStatus {
            val stillFailing = failures.filterKeys { it in pending }
                .map { (relative, reason) -> Failure(relative, reason) }
                .sortedWith(compareBy<Failure> { it.reason }.thenBy { it.relative })
            val state = when {
                unreachable -> State.UNREACHABLE
                pending.size > stillFailing.size -> State.SYNCING
                else -> State.UP_TO_DATE
            }
            return ExplorerStatus(state, pending.size, stillFailing)
        }
    }
}
