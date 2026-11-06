package app.tenshu.timeline.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where an import stands, for the one screen that shows it (B2).
 *
 * ⚠ [Failed] and a [Done] that imported nothing are **different**: the first means we could not
 * read the file, the second that we read it and it held no timeline. Collapsing them into "import
 * failed" tells a user to re-export when the real answer is "you ticked the wrong box in Takeout".
 */
sealed interface TimelineImportStatus {

    data object Idle : TimelineImportStatus

    /** The user picked [name]; the walk is running. */
    data class Running(val name: String) : TimelineImportStatus

    data class Done(val name: String, val result: TakeoutImportResult) : TimelineImportStatus

    data class Failed(val name: String, val message: String) : TimelineImportStatus
}

/**
 * Opens the platform's file picker for a Takeout export and imports what comes back (B2).
 *
 * Mirrors Fit's `HealthFilePicker` deliberately, including *why* it is a DI-bound interface rather
 * than `expect`/`actual`: only the app shell owns an `Activity` (Android's Storage Access Framework)
 * or a window (a desktop file dialog), and a platform that cannot pick a file simply does not bind
 * this — so the Import row is absent instead of present and inert.
 */
interface TimelineImportControl {

    val status: StateFlow<TimelineImportStatus>

    /**
     * Whether the photos in the archive come too (B5).
     *
     * ⚠⚠ **Off by default, and it must stay that way.** "I imported my location history" must not
     * quietly upload forty gigabytes of photographs into the user's realm — and their storage
     * quota. Bringing them in is a separate, much larger decision, so it is a separate switch.
     */
    val includePhotos: StateFlow<Boolean>
        get() = MutableStateFlow(false)

    fun setIncludePhotos(include: Boolean) = Unit

    /** Opens the picker. Returns immediately — the import runs once the user has chosen. */
    fun pick()

    /** Acknowledge a finished import, returning to [TimelineImportStatus.Idle]. */
    fun dismiss()
}

/**
 * What to tell the user, in one line.
 *
 * ⚠⚠ It reports what was **not** understood as well as what was. A German export that silently
 * imports nothing while saying "done" is the failure TB-D2 exists to prevent, and the only way a
 * user can tell us about it is if the number is on screen.
 *
 * ⚠ Assembled in English here, like the rest of the import strings — the same ADR-0016 debt the
 * Mia grammar carries.
 */
fun TakeoutImportResult.summary(name: String): String {
    val skipped = total - understood
    // ⚠⚠ The photo count is reported separately, and says how many carried a LOCATION. That is the
    // number "show me my pictures from Munich" actually depends on, and an import that brought in
    // 4 000 photos of which 0 are placeable is a result the user should be able to see rather than
    // discover later as an empty search.
    val photoNote = when {
        photos.found == 0 -> ""
        photos.imported == 0 -> " None of the ${photos.found} photos could be read."
        else -> " Also brought in ${photos.imported} photos (${photos.located} with a location)."
    }
    return photoNote.let { note ->
        timelineSummary(name, skipped) + note
    }
}

private fun TakeoutImportResult.timelineSummary(name: String, skipped: Int): String {
    return when {
        total == 0 -> "\"$name\" is empty — there was nothing to read."

        timelineFiles == 0 -> buildString {
            append("No timeline data in \"$name\" — read $total file(s) and recognised none of them ")
            append("as a Timeline export.")
            if (unmatched.isNotEmpty()) {
                append(" For example: ")
                append(unmatched.take(3).joinToString())
                append('.')
            }
        }

        imported == 0 ->
            "Read $timelineFiles timeline file(s) in \"$name\", and they held no new entries."

        else -> buildString {
            append("Imported $imported entries from $timelineFiles timeline file(s) in \"$name\"")
            if (skipped > 0) append(" ($skipped of $total files were something else).") else append('.')
        }
    }
}
