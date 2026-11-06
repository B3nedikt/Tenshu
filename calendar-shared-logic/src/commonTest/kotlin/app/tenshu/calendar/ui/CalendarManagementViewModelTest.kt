package app.tenshu.calendar.ui

import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.EventDraft
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.model.CalendarPalette
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * The calendars screen's state machine (CA-5f).
 *
 * ⚠⚠ Two of the assertions here are about *data loss*, not about form state:
 * that the last calendar cannot be deleted, and that deleting the default
 * re-points it. Everything else is a list and a sheet.
 */
/**
 * Rewrite every element in place.
 *
 * ⚠⚠ This exists because `MutableList.replaceAll` is **not** common stdlib, and the difference is
 * invisible from the JVM. On JVM it binds to `java.util.List.replaceAll` (a JDK-8 default method,
 * no opt-in); on Kotlin/Native it binds to `kotlin.collections.replaceAll`, whose `actual` is
 * `@ExperimentalNativeApi`. So the call compiles on `desktopTest`/`jvmTest` and fails only when an
 * iOS **test** source set is compiled — which neither `scripts/run-gate.sh` nor `iosKotlinCheck`
 * does, so it reached master green and only `./gradlew assemble` ever showed it.
 *
 * Opting in is not the fix from `commonTest`: `kotlin.experimental.ExperimentalNativeApi` does not
 * exist on the JVM, so the annotation cannot even be written here. Use common stdlib instead.
 */
private fun <T> MutableList<T>.replaceEach(transform: (T) -> T) {
    for (i in indices) this[i] = transform(this[i])
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarManagementViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(
        initial: List<CalendarSummary> = listOf(
            CalendarSummary("work", "Work", 0x4285F4, sensitive = false, visible = true),
            CalendarSummary("personal", "Personal", 0x0F9D58, sensitive = false, visible = true),
        ),
        private val failWith: Throwable? = null,
    ) : CalendarRepository {
        /**
         * ⚠ Flow-backed since CA-2i: the screen *collects* its lists now, so a fake
         * that only mutated a list would leave every test asserting the initial state.
         * `rows` stays a `MutableList` for the tests that inspect it; [emit] is what
         * every mutation must call, which is also how the real store behaves.
         */
        val rows = initial.toMutableList()
        /**
         * ⚠ A **version counter**, not a copy of the rows. Tests seed and mutate `rows`
         * and `feeds` directly, including before anything subscribes; mapping the
         * current contents on each tick means a subscriber always sees what is there
         * now, and [emit] only has to say "something changed".
         */
        private val version = MutableStateFlow(0)
        private fun emit() { version.value += 1 }
        var defaultId = "work"
            set(value) { field = value; defaultLive.value = value }
        private val defaultLive = MutableStateFlow("work")
        val visibilityWrites = mutableListOf<Pair<String, Boolean>>()
        val saves = mutableListOf<CalendarSummary>()
        val deletes = mutableListOf<String>()
        val defaultWrites = mutableListOf<String>()

        override suspend fun calendars(): List<CalendarSummary> {
            failWith?.let { throw it }
            return rows.sortedBy { it.name }
        }

        /** ⚠ Set to hold the write open, so a test can look *before* it lands. */
        var visibilityGate: CompletableDeferred<Unit>? = null

        override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) {
            visibilityGate?.await()
            failWith?.let { throw it }
            visibilityWrites += calendarId to visible
            rows.replaceEach { if (it.calendarId == calendarId) it.copy(visible = visible) else it }
            emit()
        }

        // ⚠ Unused here: this screen's own list is authoritative for itself, and the
        // grid — the surface that must collect this — is `CalendarViewModelTest`.
        override fun observeHiddenCalendars(): Flow<Set<String>> = flowOf(emptySet())

        override fun observeOccurrences(
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<ResolvedOccurrence>> = flowOf(emptyList())

        // ⚠ `failWith` throws here too. The screen's error path is a *read* failing,
        // and with the read now a flow, a fake that only threw from the suspend twin
        // would leave that path untested — green, and never executed.
        override fun observeCalendars(): Flow<List<CalendarSummary>> =
            version.map {
                failWith?.let { error -> throw error }
                rows.sortedBy { row -> row.name }
            }

        override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> =
            version.map { feeds.toList() }

        override fun observeDefaultCalendar(): Flow<String> = defaultLive

        override suspend fun saveCalendar(
            calendarId: String,
            name: String,
            colorArgb: Int,
            sensitive: Boolean,
        ): String {
            failWith?.let { throw it }
            val id = calendarId.ifBlank { "cal-${name.lowercase()}" }
            val row = CalendarSummary(id, name, colorArgb, sensitive, visible = true)
            saves += row
            rows.removeAll { it.calendarId == id }
            rows += row
            emit()
            return id
        }

        override suspend fun deleteCalendar(calendarId: String) {
            failWith?.let { throw it }
            deletes += calendarId
            rows.removeAll { it.calendarId == calendarId }
            // ⚠ Mirrors the real repository's re-point, so the view model is
            // tested against the behaviour it will actually see.
            if (defaultId == calendarId) defaultId = rows.map { it.calendarId }.minOrNull().orEmpty()
            emit()
        }

        override suspend fun defaultCalendar(): String = defaultId

        override suspend fun setDefaultCalendar(calendarId: String) {
            failWith?.let { throw it }
            defaultWrites += calendarId
            defaultId = calendarId
        }

        // --- unused by this screen ---
        override suspend fun occurrences(from: LocalDate, to: LocalDate, includeHidden: Boolean) =
            emptyList<ResolvedOccurrence>()
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
        override suspend fun importIcs(text: String, calendarId: String, origin: String) =
            IcsImportResult(0, 0, 0)
        override suspend fun exportIcs(calendarId: String): String = ""
        override suspend fun copyExternalCalendar(
            source: app.tenshu.calendar.data.external.ExternalCalendar,
            snapshot: app.tenshu.calendar.data.external.ExternalSnapshot,
        ) = error("unused")
        // --- subscriptions (CA-7b) ---
        val feeds = mutableListOf<CalendarSubscriptionSummary>()
        /** ⚠ Same rule as [emit]. */
        fun emitFeeds() { emit() }
        val added = mutableListOf<Pair<String, String>>()
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val removed = mutableListOf<String>()
        var refreshCalls = 0
        /** ⚠ `null` back means "not a scheme we will fetch" — the refusal path. */
        var addReturns: String? = "sub-1"

        override suspend fun subscriptions(): List<CalendarSubscriptionSummary> = feeds.toList()

        override suspend fun addSubscription(url: String, name: String): String? {
            failWith?.let { throw it }
            added += url to name
            return addReturns
        }

        override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) {
            toggles += subscriptionId to enabled
            feeds.replaceEach { if (it.subscriptionId == subscriptionId) it.copy(enabled = enabled) else it }
            emitFeeds()
        }

        override suspend fun removeSubscription(subscriptionId: String) {
            failWith?.let { throw it }
            removed += subscriptionId
            feeds.removeAll { it.subscriptionId == subscriptionId }
            emitFeeds()
        }

        override suspend fun refreshSubscriptions() { refreshCalls++ }
    }

    private fun vm(vms: OwnedViewModels, repo: CalendarRepository = FakeRepository()) =
        vms.own(CalendarManagementViewModel(repo))

    // --- live reads (CA-2i) --------------------------------------------------

    /** ⭐ The screen used to read once on entry. Nobody calls `refresh()` in any of these. */
    @Test
    fun a_calendar_renamed_elsewhere_arrives_on_its_own() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        repo.rows.replaceEach { if (it.calendarId == "work") it.copy(name = "Team") else it }
        repo.emitFeeds()

        assertEquals(listOf("Personal", "Team"), model.uiState.value.calendars.map { it.name })
    }

    /**
     * ⚠ A subscription refresh is a *background* op. A screen that showed a stale
     * "never fetched" until the user left and came back would be reporting the
     * opposite of what had just happened.
     */
    @Test
    fun a_feeds_fetch_stamp_moves_while_the_screen_is_open() = vmTest { vms ->
        val repo = FakeRepository()
        repo.feeds += CalendarSubscriptionSummary("sub-1", "work", "https://x/f.ics", true, 0)
        val model = vm(vms, repo)
        assertEquals(0, model.uiState.value.subscriptionFor("work")?.lastFetchedEpochMs)

        repo.feeds.replaceEach { it.copy(lastFetchedEpochMs = 1_760_000_000_000) }
        repo.emitFeeds()

        assertEquals(
            1_760_000_000_000,
            model.uiState.value.subscriptionFor("work")?.lastFetchedEpochMs,
        )
    }

    @Test
    fun the_default_calendar_changed_elsewhere_arrives_too() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        assertEquals("work", model.uiState.value.defaultCalendarId)

        repo.defaultId = "personal"

        assertEquals("personal", model.uiState.value.defaultCalendarId)
    }

    // --- loading -------------------------------------------------------------

    @Test
    fun it_lists_the_calendars_and_names_the_default() = vmTest { vms ->
        val state = vm(vms, ).uiState.value

        assertEquals(listOf("Personal", "Work"), state.calendars.map { it.name })
        assertEquals("work", state.defaultCalendarId)
        assertFalse(state.isLoading)
    }

    /** ⚠ Loading must finish even when it fails, or the screen spins forever. */
    @Test
    fun a_failed_load_finishes_loading_and_surfaces_the_error() = vmTest { vms ->
        val state = vm(vms, FakeRepository(failWith = IllegalStateException("no proxy"))).uiState.value

        assertFalse(state.isLoading)
        assertEquals("no proxy", state.errorMessage)
    }

    // --- visibility ----------------------------------------------------------

    @Test
    fun hiding_a_calendar_writes_the_device_preference() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.setVisible("work", visible = false)

        assertEquals(listOf("work" to false), repo.visibilityWrites)
        assertFalse(model.uiState.value.calendars.single { it.calendarId == "work" }.visible)
    }

    /**
     * ⚠ The toggle moves before the write returns. A checkbox that waits for a
     * preference round trip reads as broken, and the refresh that follows is the
     * correction — which agrees in every non-failing case.
     */
    @Test
    fun the_toggle_moves_before_the_write_is_acknowledged() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        repo.visibilityGate = CompletableDeferred()

        model.setVisible("work", visible = false)

        assertTrue(repo.visibilityWrites.isEmpty(), "the write has not landed yet")
        assertFalse(
            model.uiState.value.calendars.single { it.calendarId == "work" }.visible,
            "…but the toggle has already moved",
        )
        repo.visibilityGate?.complete(Unit)
    }

    /** ⚠ And a *failed* write puts it back, rather than lying about what is stored. */
    @Test
    fun a_failed_visibility_write_is_corrected_by_the_refresh() = vmTest { vms ->
        val repo = object : CalendarRepository by FakeRepository() {
            override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) =
                throw IllegalStateException("no device id")
        }
        val model = vm(vms, repo)

        model.setVisible("work", visible = false)

        assertTrue(
            model.uiState.value.calendars.single { it.calendarId == "work" }.visible,
            "the optimistic flip was rolled back by the refresh",
        )
    }

    // --- the add/edit sheet --------------------------------------------------

    @Test
    fun creating_opens_a_blank_draft_with_an_unused_colour() = vmTest { vms ->
        val model = vm(vms, )

        model.startCreating()

        val draft = assertNotNull(model.uiState.value.draft)
        assertTrue(draft.isCreating)
        assertEquals("", draft.name)
        assertFalse(draft.sensitive)
    }

    @Test
    fun editing_opens_the_calendars_current_values() = vmTest { vms ->
        val repo = FakeRepository(
            listOf(CalendarSummary("work", "Work", 0xDB4437, sensitive = true, visible = true))
        )
        val model = vm(vms, repo)

        model.startEditing(model.uiState.value.calendars.single())

        val draft = assertNotNull(model.uiState.value.draft)
        assertEquals("work", draft.calendarId)
        assertEquals("Work", draft.name)
        assertEquals(0xDB4437, draft.colorArgb)
        assertTrue(draft.sensitive, "the whole reason this screen exists")
        assertFalse(draft.isCreating)
    }

    @Test
    fun saving_a_new_calendar_writes_it_and_closes_the_sheet() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.startCreating()
        model.setDraftName("  Travel  ")
        model.setDraftColor(0xF4B400)
        model.setDraftSensitive(true)

        model.saveDraft()

        val saved = repo.saves.single()
        assertEquals("Travel", saved.name, "trimmed")
        assertEquals(0xF4B400, saved.colorArgb)
        assertTrue(saved.sensitive)
        assertNull(model.uiState.value.draft)
        assertTrue(model.uiState.value.calendars.any { it.name == "Travel" }, "the list refreshed")
    }

    @Test
    fun a_blank_name_is_refused_without_writing() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.startCreating()
        model.setDraftName("   ")

        model.saveDraft()

        assertTrue(repo.saves.isEmpty())
        assertNotNull(model.uiState.value.draftError)
        assertNotNull(model.uiState.value.draft, "the sheet stays open so it can be fixed")
    }

    /**
     * ⚠⚠ **A duplicate name is refused, not merged.** A new calendar's id is
     * derived from its name so two devices creating "Work" offline converge —
     * right for that case, and silently destructive for a user deliberately
     * making a second "Work", where the save would replace the first.
     */
    @Test
    fun a_duplicate_name_is_refused_rather_than_silently_replacing() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.startCreating()
        model.setDraftName("work")   // differs only in case

        model.saveDraft()

        assertTrue(repo.saves.isEmpty())
        assertNotNull(model.uiState.value.draftError)
    }

    /** ⚠ …but renaming a calendar to its own name is not a duplicate. */
    @Test
    fun a_calendar_may_keep_its_own_name_while_something_else_changes() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.startEditing(model.uiState.value.calendars.single { it.calendarId == "work" })
        model.setDraftSensitive(true)

        model.saveDraft()

        assertEquals("Work", repo.saves.single().name)
        assertTrue(repo.saves.single().sensitive)
    }

    @Test
    fun typing_after_an_error_clears_it() = vmTest { vms ->
        val model = vm(vms, )
        model.startCreating()
        model.saveDraft()
        assertNotNull(model.uiState.value.draftError)

        model.setDraftName("Travel")

        assertNull(model.uiState.value.draftError)
    }

    // --- deleting ------------------------------------------------------------

    @Test
    fun deleting_asks_first_and_only_writes_on_confirm() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        val target = model.uiState.value.calendars.single { it.calendarId == "personal" }

        model.askDelete(target)
        assertEquals(target, model.uiState.value.confirmingDelete)
        assertTrue(repo.deletes.isEmpty(), "asking is not doing")

        model.confirmDelete()

        assertEquals(listOf("personal"), repo.deletes)
        assertNull(model.uiState.value.confirmingDelete)
    }

    @Test
    fun dismissing_the_confirmation_deletes_nothing() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.askDelete(model.uiState.value.calendars.first())

        model.dismissDelete()

        assertNull(model.uiState.value.confirmingDelete)
        assertTrue(repo.deletes.isEmpty())
    }

    /**
     * ⚠⚠ **The last calendar cannot be deleted.** The op cascades to every event
     * in the calendar, so deleting the only one destroys the user's whole
     * calendar and leaves `ensureDefaultCalendar` to re-seed an empty "Personal"
     * on the next launch — data loss dressed up as a fresh start.
     */
    @Test
    fun the_last_calendar_cannot_be_deleted() = vmTest { vms ->
        val repo = FakeRepository(
            listOf(CalendarSummary("work", "Work", 0x4285F4, sensitive = false, visible = true))
        )
        val model = vm(vms, repo)
        assertFalse(model.uiState.value.canDelete)

        model.askDelete(model.uiState.value.calendars.single())

        assertNull(model.uiState.value.confirmingDelete, "the prompt must not even open")
        model.confirmDelete()
        assertTrue(repo.deletes.isEmpty())
    }

    /**
     * ⚠⚠ **Deleting the default re-points it.** Left dangling, every editor-less
     * create — Mia's path — fails with "no default calendar", from a screen that
     * never mentioned defaults.
     */
    @Test
    fun deleting_the_default_calendar_leaves_a_valid_default() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        assertEquals("work", model.uiState.value.defaultCalendarId)

        model.askDelete(model.uiState.value.calendars.single { it.calendarId == "work" })
        model.confirmDelete()

        assertEquals("personal", model.uiState.value.defaultCalendarId)
    }

    @Test
    fun a_failed_delete_surfaces_an_error_and_keeps_the_calendar() = vmTest { vms ->
        val repo = object : CalendarRepository by FakeRepository() {
            override suspend fun deleteCalendar(calendarId: String) = throw IllegalStateException("busy")
        }
        val model = vm(vms, repo)
        model.askDelete(model.uiState.value.calendars.first())

        model.confirmDelete()

        assertEquals("busy", model.uiState.value.errorMessage)
    }

    // --- the default ---------------------------------------------------------

    @Test
    fun making_a_calendar_the_default_writes_the_preference() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.setDefault("personal")

        assertEquals(listOf("personal"), repo.defaultWrites)
        assertEquals("personal", model.uiState.value.defaultCalendarId)
    }

    // --- the palette ---------------------------------------------------------

    @Test
    fun a_new_calendar_avoids_a_colour_already_in_use() = vmTest { vms ->
        val repo = FakeRepository(
            CalendarPalette.COLORS.take(2).mapIndexed { i, c ->
                CalendarSummary("c$i", "C$i", c, sensitive = false, visible = true)
            }
        )
        val model = vm(vms, repo)

        model.startCreating()

        assertEquals(CalendarPalette.COLORS[2], model.uiState.value.draft?.colorArgb)
    }

    /** ⚠ A duplicate colour beats refusing to create a ninth calendar. */
    @Test
    fun an_exhausted_palette_falls_back_rather_than_failing() = vmTest { vms ->
        val repo = FakeRepository(
            CalendarPalette.COLORS.mapIndexed { i, c ->
                CalendarSummary("c$i", "C$i", c, sensitive = false, visible = true)
            }
        )
        val model = vm(vms, repo)

        model.startCreating()

        assertEquals(CalendarPalette.DEFAULT, model.uiState.value.draft?.colorArgb)
    }

    // --- subscriptions (CA-7b) -------------------------------------------------

    private fun feed(
        subscriptionId: String = "sub-1",
        calendarId: String = "holidays",
        enabled: Boolean = true,
    ) = CalendarSubscriptionSummary(
        subscriptionId = subscriptionId,
        calendarId = calendarId,
        url = "https://example.com/h.ics",
        enabled = enabled,
        lastFetchedEpochMs = 0L,
    )

    @Test
    fun a_subscribed_calendar_is_matched_to_its_feed() = vmTest { vms ->
        val repo = FakeRepository(
            listOf(CalendarSummary("holidays", "Holidays", 0xF4B400, sensitive = false, visible = true))
        ).apply { feeds += feed() }

        val state = vm(vms, repo).uiState.value

        assertEquals("sub-1", state.subscriptionFor("holidays")?.subscriptionId)
        assertNull(state.subscriptionFor("work"), "an ordinary calendar has no feed")
    }

    @Test
    fun subscribing_writes_the_feed_and_closes_the_dialog() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.startSubscribing()
        model.setSubscribeUrl("https://example.com/h.ics")
        model.setSubscribeName("Holidays")

        model.commitSubscribe()

        assertEquals(listOf("https://example.com/h.ics" to "Holidays"), repo.added)
        assertNull(model.uiState.value.subscribeDraft)
    }

    /**
     * ⚠⚠ A calendar that stays empty after you add it reads as broken, so the
     * first fetch happens now rather than up to six hours later.
     */
    @Test
    fun subscribing_fetches_immediately_rather_than_waiting_for_the_schedule() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.startSubscribing()
        model.setSubscribeUrl("https://example.com/h.ics")

        model.commitSubscribe()

        assertEquals(1, repo.refreshCalls)
    }

    /**
     * ⚠ A refused URL keeps the dialog open with a message. `null` back means
     * "not a scheme we will fetch", and saying so now beats a subscription that
     * silently never updates.
     */
    @Test
    fun a_refused_url_keeps_the_dialog_open_and_says_why() = vmTest { vms ->
        val repo = FakeRepository().apply { addReturns = null }
        val model = vm(vms, repo)
        model.startSubscribing()
        model.setSubscribeUrl("http://example.com/h.ics")

        model.commitSubscribe()

        val draft = assertNotNull(model.uiState.value.subscribeDraft)
        assertNotNull(draft.error)
        assertFalse(draft.isSaving)
        assertEquals(0, repo.refreshCalls, "nothing was subscribed, so nothing is fetched")
    }

    @Test
    fun a_blank_url_is_refused_without_reaching_the_repository() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.startSubscribing()

        model.commitSubscribe()

        assertTrue(repo.added.isEmpty())
        assertNotNull(model.uiState.value.subscribeDraft?.error)
    }

    /** ⚠ Typing after an error clears it, as everywhere else on this screen. */
    @Test
    fun typing_a_new_url_clears_the_error() = vmTest { vms ->
        val model = vm(vms, FakeRepository().apply { addReturns = null })
        model.startSubscribing()
        model.setSubscribeUrl("http://x")
        model.commitSubscribe()
        assertNotNull(model.uiState.value.subscribeDraft?.error)

        model.setSubscribeUrl("https://x")

        assertNull(model.uiState.value.subscribeDraft?.error)
    }

    /**
     * ⚠⚠ Pausing stops the egress and **keeps** the events — the whole difference
     * from unsubscribing, which takes the calendar with it.
     */
    @Test
    fun pausing_a_feed_writes_the_flag_and_leaves_the_calendar() = vmTest { vms ->
        val repo = FakeRepository(
            listOf(CalendarSummary("holidays", "Holidays", 0xF4B400, sensitive = false, visible = true))
        ).apply { feeds += feed() }
        val model = vm(vms, repo)

        model.setSubscriptionEnabled("sub-1", enabled = false)

        assertEquals(listOf("sub-1" to false), repo.toggles)
        assertTrue(repo.removed.isEmpty())
        assertFalse(model.uiState.value.subscriptionFor("holidays")!!.enabled)
    }

    @Test
    fun unsubscribing_removes_the_feed() = vmTest { vms ->
        val repo = FakeRepository(
            listOf(CalendarSummary("holidays", "Holidays", 0xF4B400, sensitive = false, visible = true))
        ).apply { feeds += feed() }
        val model = vm(vms, repo)

        model.removeSubscription("sub-1")

        assertEquals(listOf("sub-1"), repo.removed)
        assertTrue(model.uiState.value.subscriptionsByCalendar.isEmpty())
    }

    @Test
    fun a_failed_unsubscribe_surfaces_an_error() = vmTest { vms ->
        val repo = object : CalendarRepository by FakeRepository() {
            override suspend fun removeSubscription(subscriptionId: String) =
                throw IllegalStateException("busy")
        }
        val model = vm(vms, repo)

        model.removeSubscription("sub-1")

        assertEquals("busy", model.uiState.value.errorMessage)
    }

    @Test
    fun refresh_now_fetches_and_reloads() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.refreshSubscriptionsNow()

        assertEquals(1, repo.refreshCalls)
    }
}
