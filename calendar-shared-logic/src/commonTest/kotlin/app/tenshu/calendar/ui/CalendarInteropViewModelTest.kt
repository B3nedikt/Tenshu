package app.tenshu.calendar.ui

import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.EventDraft
import app.tenshu.calendar.data.IcsImportResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * The import/export flow's state machine (CA-6b).
 *
 * ⚠⚠ This is the whole of CA-6b that can be tested here. The pickers themselves —
 * a `ContentResolver` stream, an AWT `FileDialog` — contain no logic beyond moving
 * bytes, which is precisely why the seam was drawn where it was.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarInteropViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(
        var importResult: IcsImportResult = IcsImportResult(imported = 2, overrides = 1, skipped = 0),
        var exportText: String = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
        var failWith: Throwable? = null,
    ) : CalendarRepository {
        val imports = mutableListOf<Triple<String, String, String>>()
        val exports = mutableListOf<String>()

        /** The chooser's inputs — live, because the sheet can open at any moment. */
        val calendarsLive = MutableStateFlow(emptyList<CalendarSummary>())
        val subscriptionsLive = MutableStateFlow(emptyList<CalendarSubscriptionSummary>())

        /** ⚠ Set to hold a call open, so a test can look *while it is in flight*. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun importIcs(
            text: String,
            calendarId: String,
            origin: String,
        ): IcsImportResult {
            gate?.await()
            failWith?.let { throw it }
            imports += Triple(text, calendarId, origin)
            return importResult
        }

        override suspend fun exportIcs(calendarId: String): String {
            gate?.await()
            failWith?.let { throw it }
            exports += calendarId
            return exportText
        }

        override suspend fun copyExternalCalendar(
            source: app.tenshu.calendar.data.external.ExternalCalendar,
            snapshot: app.tenshu.calendar.data.external.ExternalSnapshot,
        ): app.tenshu.calendar.data.ExternalCopyResult = error("unused")

        // --- unused by this flow ---
        override suspend fun subscriptions() = emptyList<CalendarSubscriptionSummary>()
        override suspend fun addSubscription(url: String, name: String): String? = null
        override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) = Unit
        override suspend fun removeSubscription(subscriptionId: String) = Unit
        override suspend fun refreshSubscriptions() = Unit
        override suspend fun calendars(): List<CalendarSummary> = emptyList()
        override suspend fun occurrences(from: LocalDate, to: LocalDate, includeHidden: Boolean) =
            emptyList<ResolvedOccurrence>()
        override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) = Unit

        override fun observeHiddenCalendars(): Flow<Set<String>> = flowOf(emptySet())

        override fun observeOccurrences(
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<ResolvedOccurrence>> = flowOf(emptyList())

        override fun observeCalendars(): Flow<List<CalendarSummary>> = calendarsLive
        override suspend fun saveCalendar(
            calendarId: String,
            name: String,
            colorArgb: Int,
            sensitive: Boolean,
        ): String = calendarId
        override suspend fun deleteCalendar(calendarId: String) = Unit
        override suspend fun defaultCalendar(): String = ""
        override suspend fun setDefaultCalendar(calendarId: String) = Unit
        override suspend fun ensureDefaultCalendar() = Unit
        override suspend fun saveEvent(draft: EventDraft): String = draft.eventId
        override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) = Unit
        override suspend fun deleteEvent(eventId: String) = Unit
        override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) = Unit
        override suspend fun remindersFor(eventId: String): List<Int> = emptyList()
        override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) = Unit
        override suspend fun defaultReminderMinutes(allDay: Boolean): Int? = null
        override suspend fun dismissReminder(reminderId: String, recurrenceId: String) = Unit
        override suspend fun snoozeReminder(
            reminderId: String,
            recurrenceId: String,
            untilEpochMs: Long,
        ) = Unit

        override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> =
            subscriptionsLive

        override fun observeDefaultCalendar(): Flow<String> = flowOf("")
    }

    private fun vm(vms: OwnedViewModels, repo: CalendarRepository = FakeRepository()) =
        vms.own(CalendarInteropViewModel(repo))

    private fun opened(vms: OwnedViewModels, repo: CalendarRepository = FakeRepository()) =
        vm(vms, repo).also { it.open("work", "Work") }

    // --- the chooser's targets (CA-6e) ---------------------------------------

    private fun calendar(id: String, name: String) =
        CalendarSummary(id, name, 0, sensitive = false, visible = true)

    /**
     * ⚠⚠ The rule that used to be a `.filter {}` in a Compose file. Importing into a
     * feed-backed calendar loses the events at its next refresh, which replaces the
     * calendar's whole contents — so a chooser must not offer one. Moving it here is
     * what makes it a test rather than a comment.
     */
    @Test
    fun the_chooser_never_offers_a_subscribed_calendar() = vmTest { vms ->
        val repo = FakeRepository()
        repo.calendarsLive.value = listOf(calendar("work", "Work"), calendar("feed", "Holidays"))
        repo.subscriptionsLive.value = listOf(
            CalendarSubscriptionSummary("sub-1", "feed", "https://x/f.ics", true, 0),
        )
        val model = vms.own(CalendarInteropViewModel(repo))

        assertEquals(listOf("Work"), model.uiState.value.importTargets.map { it.name })
    }

    /** ⚠ CX-1b: a mirrored calendar is replaced on its next refresh, exactly like a feed. */
    @Test
    fun the_chooser_never_offers_a_kept_in_sync_calendar() = vmTest { vms ->
        val repo = FakeRepository()
        repo.calendarsLive.value = listOf(calendar("work", "Work"), calendar("mirror-1-cal", "Team"))
        val mirrors = object : app.tenshu.calendar.data.CalendarMirrorRepository {
            val row = app.tenshu.calendar.data.CalendarMirrorSummary(
                "mirror-1", "mirror-1-cal", "ANDROID_PROVIDER", "k", "Team", "device-a", true, false, 0L,
            )
            override fun observeMirrors() = flowOf(listOf(row))
            override suspend fun mirrors() = listOf(row)
            override suspend fun startMirror(
                source: app.tenshu.calendar.data.external.ExternalCalendar,
                remindersEnabled: Boolean,
            ) = error("unused")
            override suspend fun syncMirror(
                mirror: app.tenshu.calendar.data.CalendarMirrorSummary,
                snapshot: app.tenshu.calendar.data.external.ExternalSnapshot,
            ) = error("unused")
            override suspend fun stampMirror(mirrorId: String, epochMs: Long) = Unit
            override suspend fun setMirrorReminders(mirrorId: String, enabled: Boolean) = Unit
            override suspend fun stopMirror(mirrorId: String) = Unit
            override suspend fun thisDeviceId() = "device-a"
        }
        val model = vms.own(CalendarInteropViewModel(repo, mirrors))

        assertEquals(listOf("Work"), model.uiState.value.importTargets.map { it.name })

        // ⚠⚠ And from the calendar's own row, not only the chooser: no import, and a refusal.
        model.open("mirror-1-cal", "Team")
        assertEquals(false, model.uiState.value.canImport)
        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")
        assertTrue(repo.imports.isEmpty(), "nothing reached the repository")
        model.open("work", "Work")
        assertEquals(true, model.uiState.value.canImport, "the user's own calendar still takes one")
    }

    /**
     * ⚠ Every entry point resets the sheet wholesale, which is right — but the targets
     * are a live list on their own schedule, and dropping them would empty the chooser
     * of the very flow that needs it, possibly until an emission that never comes.
     */
    @Test
    fun opening_for_a_handed_over_file_keeps_the_chooser_filled() = vmTest { vms ->
        val repo = FakeRepository()
        repo.calendarsLive.value = listOf(calendar("work", "Work"))
        val model = vms.own(CalendarInteropViewModel(repo))

        model.openForImport("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", origin = "invite.ics")

        assertTrue(model.uiState.value.choosingCalendar)
        assertEquals(listOf("Work"), model.uiState.value.importTargets.map { it.name })
    }

    // --- opening -------------------------------------------------------------

    @Test
    fun the_sheet_is_shut_until_a_calendar_is_named() = vmTest { vms ->
        assertFalse(vm(vms, ).uiState.value.visible)
    }

    @Test
    fun opening_names_the_calendar_and_shows_the_choices() = vmTest { vms ->
        val state = opened(vms, ).uiState.value

        assertTrue(state.visible)
        assertEquals("Work", state.calendarName)
        assertTrue(state.showingChoices)
    }

    /**
     * ⚠⚠ Asserted on the state **after `dismiss()`**, not after a reopen. Found by
     * mutation: the original test dismissed and then called `open`, which resets
     * everything itself — so `dismiss` could have kept the whole result and the
     * test would still have passed.
     */
    @Test
    fun dismissing_drops_the_last_outcome() = vmTest { vms ->
        val model = opened(vms, )
        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")
        assertTrue(model.uiState.value.lastImport != null)

        model.dismiss()

        assertFalse(model.uiState.value.visible)
        assertNull(model.uiState.value.lastImport, "no stale result is retained behind a shut sheet")
    }

    /** ⚠ And reopening therefore starts clean, rather than on last time's result. */
    @Test
    fun reopening_starts_on_the_choices() = vmTest { vms ->
        val model = opened(vms, )
        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")
        model.dismiss()

        model.open("work", "Work")

        assertTrue(model.uiState.value.showingChoices)
    }

    // --- importing -----------------------------------------------------------

    @Test
    fun importing_writes_into_the_named_calendar_and_reports_the_result() = vmTest { vms ->
        val repo = FakeRepository()
        val model = opened(vms, repo)

        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR", origin = "work.ics")

        assertEquals(Triple("BEGIN:VCALENDAR\r\nEND:VCALENDAR", "work", "work.ics"), repo.imports.single())
        assertEquals(IcsImportResult(2, 1, 0), model.uiState.value.lastImport)
        assertFalse(model.uiState.value.isBusy)
    }

    /**
     * ⚠⚠ **Zero out of a non-empty file is a failure, not a quiet success.** The
     * file had content and none of it landed — exactly the case a user needs told.
     */
    @Test
    fun a_file_that_yields_no_events_is_reported_as_a_failure() = vmTest { vms ->
        val repo = FakeRepository(importResult = IcsImportResult(0, 0, 3))
        val model = opened(vms, repo)

        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        assertEquals(InteropProblem.NothingImported, model.uiState.value.problem)
        assertNull(model.uiState.value.lastImport)
    }

    /** ⚠ …but *some* landing is a success, and the skipped count rides along. */
    @Test
    fun skipped_entries_do_not_make_a_partial_import_a_failure() = vmTest { vms ->
        val repo = FakeRepository(importResult = IcsImportResult(imported = 2, overrides = 0, skipped = 2))
        val model = opened(vms, repo)

        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        assertNull(model.uiState.value.problem)
        assertEquals(2, model.uiState.value.lastImport?.skipped)
    }

    @Test
    fun blank_text_is_refused_without_reaching_the_repository() = vmTest { vms ->
        val repo = FakeRepository()
        val model = opened(vms, repo)

        model.importText("   ")

        assertTrue(repo.imports.isEmpty())
        assertEquals(InteropProblem.EmptyFile, model.uiState.value.problem)
    }

    /** ⚠ Nothing can be imported before a calendar is chosen — there is no target. */
    @Test
    fun importing_with_the_sheet_shut_does_nothing() = vmTest { vms ->
        val repo = FakeRepository()

        vm(vms, repo).importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        assertTrue(repo.imports.isEmpty())
    }

    @Test
    fun a_failing_import_surfaces_an_error_rather_than_a_result() = vmTest { vms ->
        val repo = FakeRepository(failWith = IllegalStateException("no proxy"))
        val model = opened(vms, repo)

        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        assertEquals(InteropProblem.Unreadable, model.uiState.value.problem)
        assertFalse(model.uiState.value.isBusy, "and it stops being busy")
    }

    @Test
    fun the_sheet_shows_progress_while_the_import_is_in_flight() = vmTest { vms ->
        val repo = FakeRepository().apply { gate = CompletableDeferred() }
        val model = opened(vms, repo)

        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        assertTrue(model.uiState.value.isBusy)
        assertFalse(model.uiState.value.showingChoices)
        repo.gate?.complete(Unit)
        assertFalse(model.uiState.value.isBusy)
    }

    // --- exporting -----------------------------------------------------------

    /**
     * ⚠⚠ **The text is produced before the save dialog opens.** A failure to read
     * the calendar has to be an error message, not a file picker that appears and
     * then writes nothing.
     */
    @Test
    fun exporting_hands_the_text_over_before_anything_is_saved() = vmTest { vms ->
        val repo = FakeRepository(exportText = "BEGIN:VCALENDAR\r\nX\r\nEND:VCALENDAR")
        val model = opened(vms, repo)
        var handed: String? = null

        model.exportText { handed = it }

        assertEquals("BEGIN:VCALENDAR\r\nX\r\nEND:VCALENDAR", handed)
        assertEquals(listOf("work"), repo.exports)
        assertFalse(model.uiState.value.exported, "nothing is saved until the picker says so")
    }

    @Test
    fun a_saved_export_is_reported() = vmTest { vms ->
        val model = opened(vms, )
        model.exportText { }

        model.exportSaved()

        assertTrue(model.uiState.value.exported)
        assertFalse(model.uiState.value.isBusy)
    }

    @Test
    fun a_failed_write_is_distinguished_from_a_failed_read() = vmTest { vms ->
        val model = opened(vms, )
        model.exportText { }

        model.exportFailed()

        assertEquals(InteropProblem.ExportFailed, model.uiState.value.problem)
    }

    /** ⚠ A read that throws must not open a picker — the callback never fires. */
    @Test
    fun a_failing_export_read_never_reaches_the_save_dialog() = vmTest { vms ->
        val repo = FakeRepository(failWith = IllegalStateException("gone"))
        val model = opened(vms, repo)
        var handed: String? = null

        model.exportText { handed = it }

        assertNull(handed)
        assertEquals(InteropProblem.Unreadable, model.uiState.value.problem)
    }

    @Test
    fun exporting_with_the_sheet_shut_does_nothing() = vmTest { vms ->
        val repo = FakeRepository()

        vm(vms, repo).exportText { }

        assertTrue(repo.exports.isEmpty())
    }

    // --- cancelling ----------------------------------------------------------

    /** ⚠ Backing out is a decision, not a failure: no message, back to the choices. */
    @Test
    fun cancelling_a_picker_reports_nothing_and_shows_the_choices_again() = vmTest { vms ->
        val model = opened(vms, )
        model.pickStarted()

        model.pickCancelled()

        assertNull(model.uiState.value.problem)
        assertTrue(model.uiState.value.showingChoices)
    }

    @Test
    fun acknowledging_a_result_returns_to_the_choices() = vmTest { vms ->
        val model = opened(vms, )
        model.importText("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        model.acknowledgeResult()

        assertTrue(model.uiState.value.showingChoices)
        assertTrue(model.uiState.value.visible, "…without closing the sheet")
    }

    // --- a file the host OS handed over (CA-6c) ------------------------------

    @Test
    fun a_handed_over_file_opens_the_sheet_asking_which_calendar() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.openForImport("BEGIN:VCALENDAR\r\nEND:VCALENDAR", origin = "team.ics")

        val state = model.uiState.value
        assertTrue(state.visible)
        assertTrue(state.choosingCalendar)
        assertFalse(state.showingChoices, "the import/export choices are not the question yet")
        assertEquals("team.ics", state.pendingImportOrigin)
    }

    /**
     * ⚠⚠ **The load-bearing assertion of the whole flow.** A file arriving from
     * outside the app is not consent to write it somewhere in particular, so nothing
     * may reach the repository until a calendar has been named.
     */
    @Test
    fun a_handed_over_file_writes_nothing_until_a_calendar_is_chosen() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.openForImport("BEGIN:VCALENDAR\r\nEND:VCALENDAR", origin = "team.ics")

        assertTrue(repo.imports.isEmpty())
    }

    @Test
    fun choosing_a_calendar_imports_the_waiting_file_with_its_name_as_the_origin() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.openForImport("BEGIN:VCALENDAR\r\nEND:VCALENDAR", origin = "team.ics")

        model.chooseCalendar("home", "Home")

        assertEquals(
            Triple("BEGIN:VCALENDAR\r\nEND:VCALENDAR", "home", "team.ics"),
            repo.imports.single(),
        )
        assertEquals(IcsImportResult(2, 1, 0), model.uiState.value.lastImport)
        assertEquals("Home", model.uiState.value.calendarName)
    }

    /**
     * ⚠⚠ Found by asking what a double-tap does. Without the pending text being
     * cleared as the import starts, a second choice would import the same file into a
     * second calendar — and the user would have to hand-delete every event in it.
     */
    @Test
    fun choosing_twice_imports_the_file_once() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.openForImport("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        model.chooseCalendar("home", "Home")
        model.chooseCalendar("work", "Work")

        assertEquals(1, repo.imports.size)
        assertEquals("home", repo.imports.single().second)
    }

    /** ⚠ And the chooser is gone the moment it is answered, not once the write lands. */
    @Test
    fun the_chooser_closes_while_the_import_is_still_in_flight() = vmTest { vms ->
        val repo = FakeRepository().apply { gate = CompletableDeferred() }
        val model = vm(vms, repo)
        model.openForImport("BEGIN:VCALENDAR\r\nEND:VCALENDAR")

        model.chooseCalendar("home", "Home")

        assertFalse(model.uiState.value.choosingCalendar)
        assertTrue(model.uiState.value.isBusy)
        repo.gate?.complete(Unit)
    }

    @Test
    fun choosing_a_calendar_with_no_file_waiting_does_nothing() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.chooseCalendar("home", "Home")

        assertTrue(repo.imports.isEmpty())
        assertFalse(model.uiState.value.visible)
    }

    @Test
    fun a_handed_over_file_that_is_empty_says_so_instead_of_asking() = vmTest { vms ->
        val model = vm(vms, )

        model.openForImport("   ", origin = "team.ics")

        assertEquals(InteropProblem.EmptyFile, model.uiState.value.problem)
        assertFalse(model.uiState.value.choosingCalendar)
    }

    /**
     * ⚠⚠ A file the host could not read still **opens the sheet**. The alternative is
     * a double-click that launches the app and then appears to do nothing, which reads
     * as "this app cannot open .ics files" — the opposite of what the association just
     * claimed.
     */
    @Test
    fun a_file_the_host_could_not_read_is_reported_rather_than_swallowed() = vmTest { vms ->
        val model = vm(vms, )

        model.openFailed("team.ics", InteropProblem.Unreadable)

        assertTrue(model.uiState.value.visible)
        assertEquals(InteropProblem.Unreadable, model.uiState.value.problem)
    }

    // --- the suggested file name ---------------------------------------------

    @Test
    fun the_suggested_name_is_a_safe_slug_of_the_calendar() = vmTest { vms ->
        val model = vm(vms, )

        model.open("work", "Work & Life")
        assertEquals("work-life.ics", model.uiState.value.suggestedFileName)

        model.open("x", "Ünïcode / slashes")
        assertEquals("ünïcode-slashes.ics", model.uiState.value.suggestedFileName)
    }

    /** ⚠ A calendar named only in punctuation still needs a usable file name. */
    @Test
    fun an_unnameable_calendar_falls_back_rather_than_suggesting_a_bare_extension() = vmTest { vms ->
        val model = vm(vms, )

        model.open("x", "!!!")

        assertEquals("calendar.ics", model.uiState.value.suggestedFileName)
    }
}
