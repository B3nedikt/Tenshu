package app.tenshu.home.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Activity feed model (docs/proposals/home-and-mia-apps.md §3, M2): one chronological
 * stream of everything that happened in the home, filterable by [ActivityKind] — device
 * events, automation runs, and (from M3) voice interactions.
 */
enum class ActivityKind { Device, Automation, Voice }

data class ActivityEvent(
    val id: String,
    val at: Instant,
    val kind: ActivityKind,
    /** The actor — a device name, an automation name, or "Mia". */
    val title: String,
    /** What happened — "Person detected", "Turned off Sofa lamp". */
    val detail: String,
)

/** "14:32" in [timeZone] — the feed's per-row timestamp. */
fun formatTimeOfDay(at: Instant, timeZone: TimeZone): String {
    val local = at.toLocalDateTime(timeZone)
    return formatMinutesOfDay(local.hour * 60 + local.minute)
}

/** The calendar date of [at] in [timeZone] — the feed groups rows under day headers. */
fun localDayOf(at: Instant, timeZone: TimeZone): LocalDate =
    at.toLocalDateTime(timeZone).date
