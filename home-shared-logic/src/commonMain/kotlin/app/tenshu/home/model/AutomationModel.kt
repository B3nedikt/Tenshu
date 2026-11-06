package app.tenshu.home.model

import kotlin.time.Instant

/**
 * The automation taxonomy (docs/proposals/home-and-mia-apps.md §5, M2):
 * 1 trigger + 0..n conditions + 1..n actions. Headline constraint carried from the
 * milestone doc: routines execute **locally on the proxy, no cloud round-trip** — the
 * engine is a later phase; M2 ships the model + editor over the mock repository.
 *
 * The action vocabulary stays extensible (D3) so post-Rei skills can contribute actions
 * without reshaping the editor.
 */
data class Automation(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: Trigger,
    val conditions: List<Condition>,
    val actions: List<Action>,
    /** Feeds the Activity tab; null until the (future) engine first runs it. */
    val lastRunAt: Instant?,
)

enum class SunEvent { Sunrise, Sunset }

enum class GeofenceDirection { Arrive, Leave }

/** Device events a trigger can key on — the mock vocabulary; grows with the device service. */
enum class DeviceEventKind { MotionDetected, PersonDetected, Opened, Closed }

sealed interface Trigger {

    /** A fixed time of day. [minutesOfDay] 0..1439; day-of-week filters are a later slice. */
    data class Schedule(val minutesOfDay: Int) : Trigger

    /** Sunrise/sunset with a signed offset ("30 min before sunset"). */
    data class Sun(val event: SunEvent, val offsetMinutes: Int) : Trigger

    /** Arriving at / leaving home (the proposal's geofence; Timeline proved the plumbing). */
    data class Geofence(val direction: GeofenceDirection) : Trigger

    /** Something a device reported — motion, a person in frame, a contact opening. */
    data class DeviceEvent(val deviceId: String, val event: DeviceEventKind) : Trigger

    /** A spoken phrase, dispatched by Mia (live once the voice pipeline lands). */
    data class VoicePhrase(val phrase: String) : Trigger
}

sealed interface Condition {

    /** Only between [afterMinutes] and [beforeMinutes] (minutes of day; may wrap midnight). */
    data class TimeWindow(val afterMinutes: Int, val beforeMinutes: Int) : Condition

    /** Presence gate: true = only when nobody is home, false = only when somebody is. */
    data class Presence(val nobodyHome: Boolean) : Condition

    /** Only while a device is on/off. */
    data class DeviceIs(val deviceId: String, val on: Boolean) : Condition
}

sealed interface Action {

    /** Switch a device on/off — the M2 command vocabulary; richer commands come with the service. */
    data class DeviceCommand(val deviceId: String, val turnOn: Boolean) : Action

    /** Post a notification to the user's devices. */
    data class Notify(val message: String) : Action

    /** Chain another automation. */
    data class RunAutomation(val automationId: String) : Action
}

/** Parses "22:30" / "8:05" back to minutes-of-day; null for anything malformed. */
fun parseMinutesOfDay(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** Formats minutes-of-day as "22:30" for trigger/condition summaries. */
fun formatMinutesOfDay(minutesOfDay: Int): String {
    val clamped = ((minutesOfDay % 1440) + 1440) % 1440
    val h = clamped / 60
    val m = clamped % 60
    val mm = if (m < 10) "0$m" else "$m"
    return "$h:$mm"
}
