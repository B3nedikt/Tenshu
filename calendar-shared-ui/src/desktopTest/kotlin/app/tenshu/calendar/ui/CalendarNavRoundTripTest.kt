package app.tenshu.calendar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.EventDraft
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.ui.navigation.Destination
import app.tenshu.calendar.ui.screens.CalendarScreen
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import app.tenshu.calendar.desktopComposeVmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/** ⚠ `useUnmergedTree`: a `LazyColumn` row merges its children, so the title is not a root node. */
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteractionsProvider.isOnScreen(text: String): Boolean =
    onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/**
 * Pins the round trip: scroll the agenda, open an event, come back — the agenda keeps its place
 * and the calendar keeps its view model.
 *
 * ⚠⚠ **This behaviour breaks for three unrelated reasons** (`docs/tech-debt.md`, "scroll a list,
 * open a detail, come back"): a scaffold rebuilding its content subtree, a paged flow dropping its
 * pages (no `cachedIn`), and no saveable-state registry at all. The third is the one Calendar is
 * exposed to: a `remember`ed route switched on with a `when` tears the leaving screen out of
 * composition and takes every `rememberSaveable` with it, `rememberLazyListState`'s included. This
 * class is what says so if the shell ever takes that shape.
 *
 * ⚠ The harness is the real pairing — [KmpNavHost] over Calendar's own [Destination]s, with the
 * real [CalendarScreen] and a real [CalendarViewModel] — because that pairing IS the mechanism. A
 * `CalendarScreen` rendered on its own, as every other test in this module renders its subject,
 * cannot see any of it. ⚠ There is deliberately no `AdaptiveNavScaffold` in the harness, because
 * there is none in `App.kt`: Calendar has one top-level surface (see `navigation/Destination.kt`),
 * and a harness that wrapped one anyway would be testing a shape the app does not have.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarNavRoundTripTest {

    /** Counts *constructions*, which is the thing a surviving `ViewModelStore` prevents. */
    private class Built {
        var count = 0
    }

    @Test
    fun theAgendaKeepsItsPlaceAfterOpeningAnEventAndComingBack() {
        val repository = FakeCalendarRepository(occurrences(DAYS))
        val built = Built()
        desktopComposeVmTest(width = 420, height = 900) { vms ->
            val backStack = mutableStateListOf<Destination>(Destination.Calendar)
            setContent { Harness(vms, backStack, repository, built) }

            waitUntil(timeoutMillis = 10_000) { isOnScreen(FIRST_EVENT) }

            onNode(hasScrollToIndexAction()).performScrollToIndex(SCROLL_TO)
            waitForIdle()
            // The agenda is deep enough that the first day's event has been recycled away. If it
            // has not, the fixture is too short and the assertion below proves nothing.
            waitUntil(timeoutMillis = 10_000) { !isOnScreen(FIRST_EVENT) }

            backStack.add(Destination.Editor.Existing(eventId = "e-0", recurrenceId = "r-0"))
            waitUntil(timeoutMillis = 10_000) { isOnScreen(EDITOR) }

            backStack.removeLastOrNull()
            waitUntil(timeoutMillis = 10_000) { !isOnScreen(EDITOR) }

            assertEquals(1, backStack.size, "the pop must land back on the calendar")
            // ⚠⚠ THE assertion. If the agenda came back at the top, the first day's event is on
            // screen again — and that is precisely the bug, for every one of its three causes.
            assertFalse(
                isOnScreen(FIRST_EVENT),
                "the agenda came back at the top — its scroll position was not restored",
            )
        }
    }

    /**
     * ⚠ The other half of what an entry keeps, and it is not the same half. Saveable state survives
     * a `SaveableStateHolder` alone; the view model needs the entry's `ViewModelStore`, and losing
     * it while keeping the other shows as a screen whose figures count up from 0 on the way back.
     *
     * ⚠⚠ **It does NOT discriminate against a `when`-switched route, and that is worth saying
     * rather than leaving for the next person to discover.** It pins the nav host's *contract* — an
     * entry sitting below the top of the stack keeps its store — which is the half an eviction bug
     * would break. [theAgendaKeepsItsPlaceAfterOpeningAnEventAndComingBack] is the one that fails
     * against the `when` shape.
     *
     * **The measurement, stated so it can be repeated.** Replace [Harness]'s `KmpNavHost` with
     * `when (backStack.last()) { is Destination.Calendar -> …; else -> … }`, keeping the
     * `viewModel<CalendarViewModel> { … }` call **inside** the branch.
     * Result: 4 tests, 1 failure — this one passes, because without a host every screen shares the
     * ambient `ViewModelStore` and nothing is ever cleared; only the agenda test fails, with its own
     * assertion message.
     *
     * ⚠⚠ **And the trap in repeating it.** BOTH round-trip tests block on
     * `waitUntil { isOnScreen(EDITOR) }`, so a stand-in branch rendering any *other* string —
     * `Text("editor")` rather than `Text(EDITOR)` — fails **both** of them after ten seconds with
     * `ComposeTimeoutException`. That looks exactly like "the swap
     * falsified both tests" and is nothing of the kind: it is the harness never reaching the pop.
     * ⚠ Read the failure *message*, never the count. A timeout here is a broken swap; the two
     * assertion strings are the only outcomes that say anything about navigation.
     */
    @Test
    fun openingAnEventAndComingBackKeepsTheSameCalendarViewModel() {
        val repository = FakeCalendarRepository(occurrences(DAYS))
        val built = Built()
        desktopComposeVmTest(width = 420, height = 900) { vms ->
            val backStack = mutableStateListOf<Destination>(Destination.Calendar)
            setContent { Harness(vms, backStack, repository, built) }

            waitUntil(timeoutMillis = 10_000) { isOnScreen(FIRST_EVENT) }
            assertEquals(1, built.count, "the view model must be built once on first entry")

            backStack.add(Destination.Editor.Existing(eventId = "e-0", recurrenceId = "r-0"))
            waitUntil(timeoutMillis = 10_000) { isOnScreen(EDITOR) }
            backStack.removeLastOrNull()
            waitUntil(timeoutMillis = 10_000) { isOnScreen(FIRST_EVENT) }

            assertEquals(
                1,
                built.count,
                "coming back from an event rebuilt the calendar's view model, which re-reads the " +
                    "window and re-freezes `today`",
            )
        }
    }

    /**
     * ⚠⚠ **The route's design decision, stated as a test.** A route is a back-stack key: both
     * `KmpNavHost` actuals use it as the map key for that entry's `ViewModelStore` and its
     * saveable-state slot. So screen identity is route *equality* — and a route carrying a whole
     * [ResolvedOccurrence] would make screen identity **value** equality over twelve fields,
     * `title` and `isOverridden` among them. Two reads of the same occurrence that disagree about
     * any one of them would then be two different screens.
     *
     * ⚠ Falsified by construction against that shape: `Editor(occurrence = …)` fails the first
     * assertion below, because the two values here differ in exactly the fields a live window read
     * can change. This pins a hazard the address form cannot have, not an observed bug.
     */
    @Test
    fun anEditorRouteIsTheSameKeyWhenTheOccurrenceValueChangesUnderneath() {
        val opened = occurrence(day = 1, title = "Standup")
        // The same occurrence after somebody else renamed it and moved this instance of it.
        val changed = opened.copy(title = "Stand-up", isOverridden = true)

        val a = Destination.Editor.Existing(opened.eventId, opened.recurrenceId)
        val b = Destination.Editor.Existing(changed.eventId, changed.recurrenceId)

        assertEquals(a, b, "an occurrence changing value must not change the screen it addresses")
        assertEquals(a.hashCode(), b.hashCode(), "the nav host keys a map on this")
        assertEquals(a.key, b.key, "and the editor keys its view model on this")
    }

    /**
     * ⚠⚠ The other direction, and it is the trap Drive's `FilesScreenIntegrationTest` documents:
     * without a distinct key Compose hands back the *previous* event's view model and the form
     * opens pre-filled with somebody else's data.
     */
    @Test
    fun twoOccurrencesAreTwoEditorKeys() {
        val a = Destination.Editor.Existing("e-1", "2027-05-04T09:00")
        // ⚠ Same series, different instance of it — the case a bare `eventId` would collapse.
        val b = Destination.Editor.Existing("e-1", "2027-05-05T09:00")
        assertNotEquals(a, b)
        assertNotEquals(a.key, b.key)
        // A create is never confused with an edit, whatever slot it was started from.
        assertNotEquals(a.key, Destination.Editor.New(date = LocalDate(2027, 5, 4)).key)
    }

    // --- harness -------------------------------------------------------------------------------

    @Composable
    private fun Harness(
        vms: OwnedViewModels,
        backStack: SnapshotStateList<Destination>,
        repository: CalendarRepository,
        built: Built,
    ) {
        TenshuTheme {
            KmpNavHost(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryProvider = kmpEntryProvider {
                    entry<Destination.Calendar> {
                        val vm = viewModel<CalendarViewModel> {
                            built.count++
                            vms.own(CalendarViewModel(repository, today = TODAY).also { it.showAgenda() })
                        }
                        CalendarScreen(
                            viewModel = vm,
                            onOpen = {
                                backStack.add(
                                    Destination.Editor.Existing(it.eventId, it.recurrenceId),
                                )
                            },
                            onCreateAt = { _, _ -> },
                            nowMinute = null,
                        )
                    }
                    // ⚠ A stand-in, not `EditorPane`: the real editor resolves its view model
                    // through Koin, and standing up that graph would test the container rather than
                    // the navigation. What this entry has to be is a screen that replaces the
                    // agenda in composition, which any screen does.
                    entry<Destination.Editor.Existing> {
                        Box(Modifier.fillMaxSize()) { Text(EDITOR) }
                    }
                },
            )
        }
    }

    private companion object {
        val TODAY = LocalDate(2027, 5, 3)
        const val DAYS = 60
        /** Far enough down that the first day is recycled; the test asserts that it is. */
        const val SCROLL_TO = 80
        const val FIRST_EVENT = "Event 0"
        const val EDITOR = "Event editor"

        fun occurrence(day: Int, title: String = "Event $day") = ResolvedOccurrence(
            eventId = "e-$day",
            calendarId = "cal",
            recurrenceId = "r-$day",
            title = title,
            location = "",
            notes = "",
            startLocal = LocalDateTime(TODAY.plusDays(day), LocalTime(9, 0)),
            endLocal = LocalDateTime(TODAY.plusDays(day), LocalTime(10, 0)),
            zoneId = "UTC",
            allDay = false,
            isOverridden = false,
            recurrenceRule = "",
        )

        /** ⚠ One event per day, so every title AND every day header is a distinct string. */
        fun occurrences(n: Int) = List(n) { occurrence(it) }

        fun LocalDate.plusDays(days: Int) = LocalDate.fromEpochDays(toEpochDays() + days)
    }
}

/** ⚠ Only `observeOccurrences` / `observeCalendars` carry data; the rest is the interface's tail. */
private class FakeCalendarRepository(
    occurrences: List<ResolvedOccurrence>,
) : CalendarRepository {
    private val live = MutableStateFlow(occurrences)

    override fun observeOccurrences(from: LocalDate, to: LocalDate): Flow<List<ResolvedOccurrence>> =
        live
    override fun observeCalendars(): Flow<List<CalendarSummary>> = flowOf(
        listOf(CalendarSummary("cal", "Personal", 0x2196F3, sensitive = false, visible = true)),
    )
    override fun observeHiddenCalendars(): Flow<Set<String>> = flowOf(emptySet())
    override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> = flowOf(emptyList())
    override fun observeDefaultCalendar(): Flow<String> = flowOf("cal")

    override suspend fun calendars(): List<CalendarSummary> = emptyList()
    override suspend fun occurrences(from: LocalDate, to: LocalDate, includeHidden: Boolean) =
        live.value
    override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) = Unit
    override suspend fun saveCalendar(
        calendarId: String,
        name: String,
        colorArgb: Int,
        sensitive: Boolean,
    ): String = calendarId
    override suspend fun deleteCalendar(calendarId: String) = Unit
    override suspend fun defaultCalendar(): String = "cal"
    override suspend fun setDefaultCalendar(calendarId: String) = Unit
    override suspend fun ensureDefaultCalendar() = Unit
    override suspend fun saveEvent(draft: EventDraft): String = draft.eventId
    override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) = Unit
    override suspend fun deleteEvent(eventId: String) = Unit
    override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) = Unit
    override suspend fun subscriptions(): List<CalendarSubscriptionSummary> = emptyList()
    override suspend fun addSubscription(url: String, name: String): String? = null
    override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) = Unit
    override suspend fun removeSubscription(subscriptionId: String) = Unit
    override suspend fun refreshSubscriptions() = Unit
    override suspend fun importIcs(text: String, calendarId: String, origin: String) =
        IcsImportResult(imported = 0, overrides = 0, skipped = 0)
    override suspend fun exportIcs(calendarId: String): String = ""
    override suspend fun copyExternalCalendar(
        source: app.tenshu.calendar.data.external.ExternalCalendar,
        snapshot: app.tenshu.calendar.data.external.ExternalSnapshot,
    ) = error("unused")
    override suspend fun remindersFor(eventId: String): List<Int> = emptyList()
    override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) = Unit
    override suspend fun defaultReminderMinutes(allDay: Boolean): Int? = null
    override suspend fun dismissReminder(reminderId: String, recurrenceId: String) = Unit
    override suspend fun snoozeReminder(
        reminderId: String,
        recurrenceId: String,
        untilEpochMs: Long,
    ) = Unit
}
