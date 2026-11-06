package org.tenshu.calendar

import org.tenshu.preference.LongAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringAdapter
import org.tenshu.preference.StringListAdapter

/**
 * Calendar's preference declarations — the per-device view state that
 * deliberately does **not** live on the [Calendar] row.
 *
 * ⭐ A preference is a *code-side* declaration: adding one requires no new
 * composite and no schema version bump (`core-preference-service.md` §3.8), so
 * this is the cheap half of the schema by construction.
 *
 * ⚠ Housed in `:calendar-service` rather than an app module because no
 * `:calendar-shared-logic` exists yet (CA-1), and CA-1's repository, CA-2's UI
 * and CA-5's reminder presenter all need the id. Moving it later is free —
 * nothing about a `Preference` is stored, so relocating the declaration cannot
 * break a row. `ControlPreferences` sets the same precedent of living beside
 * the service rather than in a UI module.
 */
object CalendarPreferences {

    /**
     * `calendarId`s the user has hidden **on this device**
     * (`calendar-app.md` §3 D4; the Q10 sibling decision).
     *
     * ⚠⚠ **Stored as the hidden set, not the visible one, and that polarity is
     * load-bearing.** A newly created or newly synced calendar is absent from
     * this list and therefore visible — which is the correct default *by
     * construction*, with no write required at creation time and no way for a
     * device that has never heard of a calendar to render it as hidden. The
     * inverse encoding would make every new calendar invisible on every device
     * that had not yet written its own row, which is the failure mode where the
     * user's data appears to have vanished.
     *
     * ⭐ `DEVICE`-scoped, so a phone and a laptop keep their own view. See
     * [Calendar]'s KDoc for why this is not a synced field: hiding a calendar is
     * a cheap, frequent gesture, and as an `Eager` row every toggle would have
     * cost an encrypted block pushed to the relay and replayed everywhere.
     *
     * ⚠ One row per device, holding the whole set, rather than one row per
     * calendar: `Preference` ids are static declarations, so a per-calendar
     * preference would need dynamic ids the type does not support — and a single
     * list keeps a visibility change to one write however many calendars exist.
     *
     * ⚠ List-valued preferences ride as opaque bytes via [StringListAdapter]
     * (`genericData` stores inline primitives or composite refs, not arrays), so
     * this value is not self-describing to a generic cross-app reader. Fine
     * here — the only readers are Calendar's own surfaces.
     */
    val HiddenCalendars = Preference(
        id = "calendar.view.hiddenCalendars",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )

    /**
     * The calendar a new event lands in when the caller does not name one
     * (`calendar-app.md` §6.4, Q14).
     *
     * ⭐⭐ **This exists because of Mia.** A UI-only calendar can let the editor
     * screen resolve what the user did not say; an op invoked from the assistant
     * has no editor, so *"add lunch with Anna next Tuesday at one"* has to
     * resolve a calendar somewhere, and the service is the only layer both
     * callers share. Designed in now rather than retrofitted: adding a default
     * to an op that has already shipped changes behaviour for existing callers.
     *
     * ⚠ `REALM`-scoped, unlike [HiddenCalendars]. Which calendar new events
     * belong in is a statement about how the user organises their data, not
     * about the device they happened to say it on — an event created on the
     * phone and one created on the laptop should land in the same place.
     *
     * ⚠ Blank means *unset*, and `SaveEvent` then fails loudly rather than
     * guessing. Seeding a first calendar and writing this row is CA-2's job,
     * where there is a UI to do it in.
     */
    val DefaultCalendarId = Preference(
        id = "calendar.defaults.calendarId",
        scope = PreferenceScope.REALM,
        default = "",
        adapter = StringAdapter,
    )

    /**
     * How long an event lasts when the caller gives a start but no end
     * (`calendar-app.md` §6.4) — the other half of what an editor would
     * otherwise have supplied. One hour, matching every stock calendar.
     *
     * ⚠ Applies to *timed* events only. An all-day event with no end is one
     * day by RFC 5545, which `EventTime.parse` already handles and which this
     * must not override.
     */
    val DefaultEventDurationMinutes = Preference(
        id = "calendar.defaults.eventDurationMinutes",
        scope = PreferenceScope.REALM,
        default = 60L,
        adapter = LongAdapter,
    )

    /**
     * How long before a new timed event a reminder is added, in minutes; a
     * negative value means "no reminder by default" (`calendar-app.md` §6.4).
     *
     * ⭐ §6.4's third default, deferred from CA-1 because there was no
     * [Reminder] to attach it to. Same reason as [DefaultCalendarId]: an event
     * Mia creates from *"remind me about the dentist on Thursday"* has no editor
     * to pick an offset in, so the service layer is the only place both callers
     * share.
     *
     * ⚠ `REALM`-scoped. How far ahead you want warning is a statement about how
     * you work, not about which device you happened to be holding.
     *
     * ⚠ **-1, not 0, means off.** Zero is a real and useful offset — "remind me
     * when it starts" — so it cannot double as the sentinel.
     */
    val DefaultReminderMinutes = Preference(
        id = "calendar.defaults.reminderMinutes",
        scope = PreferenceScope.REALM,
        default = 10L,
        adapter = LongAdapter,
    )

    /**
     * The same, for **all-day** events — minutes before midnight on the day.
     *
     * ⚠⚠ A separate preference rather than reuse, because the two are not the
     * same quantity. An all-day event starts at 00:00, so the timed default of
     * "10 minutes before" would alert at 23:50 the night before — which is why
     * every stock calendar asks this question separately and answers it with a
     * *time of day*. The default here is **-540**: negative, i.e. 540 minutes
     * *after* midnight, which is 09:00 on the day itself.
     */
    val DefaultAllDayReminderMinutes = Preference(
        id = "calendar.defaults.allDayReminderMinutes",
        scope = PreferenceScope.REALM,
        default = -540L,
        adapter = LongAdapter,
    )
}
