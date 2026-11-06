package app.tenshu.calendar.ui.navigation

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * Calendar navigation destinations (CA-3). Custom KMP navigation (`:ui-core` `KmpNavHost`) over a
 * plain `mutableStateListOf` back stack — the shape every sibling app uses.
 *
 * ⚠⚠ **A route is an ADDRESS, not cargo, and that is the whole reason this file exists.** Calendar
 * previously switched on a `remember`ed route whose editor case carried a *payload*: a whole
 * [org.tenshu.calendar.common.recurrence.ResolvedOccurrence], twelve fields including the series'
 * `RRULE`. A back-stack key is not a place to put one: both `KmpNavHost` actuals use the route as
 * the map key for **that entry's saveable-state slot and its `ViewModelStore`**, so screen identity
 * becomes value equality over all twelve — two reads of the same occurrence that disagree about
 * `title` or `isOverridden` are then two different screens, each with its own view model.
 *
 * ⚠ To be exact about what that is and is not: no path exercises it *today*, because the old route
 * was captured once at the tap and never written to again. It is a hazard the address form cannot
 * have rather than a bug that was observed, and it is worth removing on those terms alone — a route
 * that can be re-derived from its ids is also the only kind that could ever be serialised, survive
 * process death, or be reached from a link, which is why every sibling app's routes are ids.
 * `CalendarNavRoundTripTest` pins the property directly.
 *
 * ⚠ The occurrence did not have to be re-read to make this work. It moved to a shell-held slot that
 * outlives every nav entry, which is where a value resolved once and only once belongs — see
 * `App.kt`'s `openedOccurrence`. The old KDoc's reasoning — *"the occurrence travels whole rather
 * than as an id … so the editor never re-reads a row the list just handed it and the two cannot
 * disagree"* — still holds in full; only its carrier changed.
 *
 * ⚠⚠ **There is no `TopLevel` marker here and no `AdaptiveNavScaffold` in `App.kt`** — unlike
 * Contacts / Home / Mia, and *like* Maps, which is the sibling with the same shape. Calendar has
 * exactly one top-level surface: [Calendar]. Material's bar and rail exist to switch between peers,
 * and the width a rail would take is width this app has already decided it cannot spare —
 * `CalendarPane` gates its own 220 dp sidebar at `Large` rather than `Expanded` on precisely that
 * argument. Adaptive chrome becomes worth having the day Calendar grows a second peer surface;
 * until then it would cost the grid and show nothing.
 */
@Serializable
sealed interface Destination {

    /** The month / week / day grid — the app's one top-level surface. */
    @Serializable
    data object Calendar : Destination

    /** The calendars list — add, rename, recolour, hide, delete (CA-5f). */
    @Serializable
    data object Calendars : Destination

    /** Calendars → Open-source licences (`docs/oss-licences.md`). */
    @Serializable
    data object Licences : Destination

    /**
     * The event editor, in its two genuinely different modes.
     *
     * ⚠ Two cases rather than one shape with four nullable fields, because *creating* and *editing*
     * are addressed differently: a create is named by the slot the user tapped, an edit by the
     * occurrence it opens. The old single `Editor(initialDate, initialTime, occurrence)` let every
     * combination of those be spelled, including the ones that mean nothing.
     */
    @Serializable
    sealed interface Editor : Destination {

        /**
         * Distinguishes one editor instance from the next.
         *
         * ⚠⚠ Load-bearing: it is the `koinViewModel(key = …)` key, and without one Compose hands
         * back the view model from the *previous* event and the form opens pre-filled with somebody
         * else's data. Drive's `FilesScreenIntegrationTest` documents this exact trap.
         */
        val key: String

        /** Creating, on the day — and optionally the time — the user tapped. */
        @Serializable
        data class New(val date: LocalDate? = null, val time: LocalTime? = null) : Editor {
            override val key: String get() = "new|$date|$time"
        }

        /**
         * Editing one occurrence, addressed by the pair that identifies it.
         *
         * ⭐ The same `(eventId, recurrenceId)` pair a reminder tap and a cross-app open already
         * carry — an occurrence has no stored row of its own, so this *is* its address everywhere
         * else in the app (CO-D5). The route did not have to invent one.
         */
        @Serializable
        data class Existing(val eventId: String, val recurrenceId: String) : Editor {
            override val key: String get() = "$eventId|$recurrenceId"
        }
    }
}
