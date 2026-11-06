package app.tenshu.calendar.data

import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalSnapshot
import kotlinx.coroutines.flow.Flow

/** A *Keep in sync* calendar, as a screen or the refresher sees it (CX-1b). */
data class CalendarMirrorSummary(
    val mirrorId: String,
    val calendarId: String,
    val sourceKind: String,
    val sourceKey: String,
    val sourceLabel: String,
    val deviceId: String,
    /**
     * Whether **this** device is the one that refreshes it.
     *
     * ⚠ Only this device can see the source (§5.1); every other device shows the mirror's events
     * and when it was last refreshed, and cannot refresh it.
     */
    val boundHere: Boolean,
    val remindersEnabled: Boolean,
    val lastRefreshedEpochMs: Long,
)

/** What one refresh changed. */
data class MirrorSyncResult(
    /** Series written because they were new or differed from the source. */
    val written: Int,
    /** Events removed because the source no longer has them. */
    val deleted: Int,
    /** Series already identical to the source, left untouched. */
    val unchanged: Int,
    /** Events the source holds that could not be placed in time. */
    val skipped: Int,
) {
    val changedAnything: Boolean get() = written > 0 || deleted > 0
}

/**
 * *Keep in sync* (`calendar-external-sources.md` CX-1b): calendars kept in step with one the OS
 * already holds.
 *
 * ⚠ Its own interface rather than more of [CalendarRepository], so the screens and fakes that
 * know nothing about mirrors stay as they are; `TenshuCalendarRepository` implements both.
 */
interface CalendarMirrorRepository {

    fun observeMirrors(): Flow<List<CalendarMirrorSummary>>

    suspend fun mirrors(): List<CalendarMirrorSummary>

    /**
     * Starts keeping [source] in sync from **this** device, making its calendar on first use.
     *
     * ⚠ Called for a source already mirrored from another device, it **rebinds** the mirror here
     * (Q6) — the mirror's id derives from the source, not the device — and keeps its calendar.
     */
    suspend fun startMirror(source: ExternalCalendar, remindersEnabled: Boolean): CalendarMirrorSummary

    /**
     * Makes [mirror]'s calendar match [snapshot]: writes what differs, deletes what the source no
     * longer has, and leaves everything else alone.
     *
     * ⚠⚠ **Diffed against the realm, never upserted wholesale.** A refresh that rewrote every
     * event would turn a calendar of thousands of events into thousands of sync writes each time
     * the phone's calendar provider so much as twitched (§5.3).
     */
    suspend fun syncMirror(mirror: CalendarMirrorSummary, snapshot: ExternalSnapshot): MirrorSyncResult

    /**
     * Records that this device, the bound one, confirmed the mirror at [epochMs].
     *
     * ⚠⚠ Writes this device's stamp row only, never the mirror row (`calendar-external-sources.md`
     * §11 P2-a): a re-save of the shared row from a phone that missed a stop brought the mirror back.
     */
    suspend fun stampMirror(mirrorId: String, epochMs: Long)

    suspend fun setMirrorReminders(mirrorId: String, enabled: Boolean)

    /** Stops syncing and **keeps the events** as an ordinary calendar — see `DeleteCalendarMirror`. */
    suspend fun stopMirror(mirrorId: String)

    /** This device's sync id, or `null` while it is not known. */
    suspend fun thisDeviceId(): String?
}
