package app.tenshu.calendar.ui

import app.tenshu.calendar.data.CalendarRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.calendar.data.external.MirrorRefreshOutcome
import app.tenshu.calendar.data.external.MirrorBackgroundScheduler
import app.tenshu.calendar.data.external.InMemoryMirrorDigestStore
import app.tenshu.calendar.data.MirrorSyncResult
import app.tenshu.calendar.data.CalendarMirrorSummary
import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.ExternalCopyResult
import app.tenshu.calendar.data.NotConnectedCalendarRepository
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCalendarAccessDenied
import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.calendar.data.external.ExternalCopyIds
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.calendar.data.external.ExternalSourceKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest

/**
 * *Copy from this phone*'s state machine (`calendar-external-sources.md` CX-1).
 *
 * ⚠ The Android reader and the permission prompt are platform code with no logic of their own;
 * everything that decides anything is here or in `ProviderConversion`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExternalCopyViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun calendar(name: String, account: String = "me@gmail.com", holdsEvents: Boolean = true) =
        ExternalCalendar(
            kind = ExternalSourceKind.ANDROID_PROVIDER,
            sourceKey = "com.google|$account|$name",
            handle = name,
            name = name,
            accountName = account,
            accountType = "com.google",
            colorArgb = null,
            holdsEvents = holdsEvents,
        )

    private fun event(uid: String) = ImportedEvent(
        uid = uid,
        title = uid,
        startLocal = "2026-07-01T09:00",
        endLocal = "2026-07-01T10:00",
        timeZoneId = "Europe/Berlin",
        allDay = false,
        location = "",
        notes = "",
        recurrenceRule = "",
        exceptionDates = "",
        recurrenceId = null,
        reminderMinutes = emptyList(),
        raw = "",
    )

    private class FakeReader(
        var calendars: List<ExternalCalendar> = emptyList(),
        var snapshot: ExternalSnapshot = ExternalSnapshot(emptyList(), 0),
        var failWith: Throwable? = null,
    ) : ExternalCalendarReader {
        val reads = mutableListOf<String>()

        /** ⚠ Set to hold a read open, so a test can look while it is in flight. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun calendars(): List<ExternalCalendar> {
            failWith?.let { throw it }
            return calendars
        }

        override suspend fun read(calendar: ExternalCalendar): ExternalSnapshot {
            reads += calendar.name
            gate?.await()
            failWith?.let { throw it }
            return snapshot
        }
    }

    private class FakeRepository(var failWith: Throwable? = null) :
        CalendarRepository by NotConnectedCalendarRepository() {
        val copies = mutableListOf<Pair<ExternalCalendar, ExternalSnapshot>>()

        override suspend fun copyExternalCalendar(
            source: ExternalCalendar,
            snapshot: ExternalSnapshot,
        ): ExternalCopyResult {
            failWith?.let { throw it }
            copies += source to snapshot
            return ExternalCopyResult(
                calendarId = "copy-1",
                calendarName = source.name,
                copied = snapshot.events.size,
                added = snapshot.events.size,
                overrides = 0,
                skipped = snapshot.skipped,
            )
        }
    }

    private fun vm(vms: OwnedViewModels, reader: ExternalCalendarReader?, repo: CalendarRepository = FakeRepository()) =
        vms.own(ExternalCopyViewModel(repo, reader))

    private val ExternalCopyViewModel.step get() = uiState.value.step

    @Test
    fun a_platform_with_no_reader_does_not_offer_the_flow() = vmTest { vms ->
        assertFalse(vm(vms, reader = null).supported)
        assertTrue(vm(vms, reader = FakeReader()).supported)
    }

    @Test
    fun opening_lists_calendars_by_account_with_the_copyable_ones_first() = vmTest { vms ->
        val reader = FakeReader(
            calendars = listOf(
                calendar("Work"),
                calendar("Birthdays", holdsEvents = false),
                calendar("Holidays"),
                calendar("Team", account = "anna@corp.com"),
            )
        )
        val model = vm(vms, reader)

        model.open()

        val accounts = assertIs<ExternalCopyStep.Choosing>(model.step).accounts
        assertEquals(listOf("anna@corp.com", "me@gmail.com"), accounts.map { it.accountName })
        assertEquals(listOf("Holidays", "Work", "Birthdays"), accounts[1].calendars.map { it.name })
    }

    /**
     * ⚠ A calendar copied before is *updated* by a second copy (its id is derived from the source),
     * so its row names the Tenshu calendar — under the name the user gave it — instead of
     * promising a new one. Measured on a real phone: every row said "new".
     */
    @Test
    fun a_calendar_copied_before_is_offered_as_an_update_of_its_copy() = vmTest { vms ->
        val work = calendar("Work")
        val holidays = calendar("Holidays")
        val repo = object : CalendarRepository by FakeRepository() {
            override suspend fun calendars() = listOf(
                CalendarSummary(ExternalCopyIds.calendarId(work), "Job", 0, sensitive = false, visible = true),
                CalendarSummary("personal", "Personal", 0, sensitive = false, visible = true),
            )
        }
        val model = vm(vms, FakeReader(calendars = listOf(work, holidays)), repo)
        model.open()

        val choosing = assertIs<ExternalCopyStep.Choosing>(model.step)
        assertEquals(mapOf(work.sourceKey to "Job"), choosing.copiedInto)
    }

    /** ⚠ Tenshu's calendars unreadable must not stop the chooser — the label is best effort. */
    @Test
    fun the_chooser_opens_even_when_tenshu_calendars_cannot_be_read() = vmTest { vms ->
        val repo = object : CalendarRepository by FakeRepository() {
            override suspend fun calendars(): List<CalendarSummary> = error("Control unreachable")
        }
        val model = vm(vms, FakeReader(calendars = listOf(calendar("Work"))), repo)
        model.open()

        val choosing = assertIs<ExternalCopyStep.Choosing>(model.step)
        assertEquals(emptyMap(), choosing.copiedInto)
    }

    /** ⚠ No accounts is a real state — its answer is *Add an account*, not an error. */
    @Test
    fun a_phone_with_no_calendars_is_an_empty_chooser_not_a_failure() = vmTest { vms ->
        val model = vm(vms, FakeReader())
        model.open()
        assertEquals(ExternalCopyStep.Choosing(emptyList()), model.step)
    }

    @Test
    fun a_refused_read_says_why() = vmTest { vms ->
        val denied = vm(vms, FakeReader(failWith = ExternalCalendarAccessDenied()))
        denied.open()
        assertEquals(ExternalCopyStep.Failed(ExternalCopyProblem.PermissionDenied), denied.step)

        val broken = vm(vms, FakeReader(failWith = IllegalStateException("provider died")))
        broken.open()
        assertEquals(ExternalCopyStep.Failed(ExternalCopyProblem.Unreadable), broken.step)
    }

    /** ⚠ A refusal opens the sheet on a reason; a button that silently does nothing reads as broken. */
    @Test
    fun a_refused_permission_opens_the_sheet_on_the_reason() = vmTest { vms ->
        val model = vm(vms, FakeReader())
        model.permissionDenied()
        assertTrue(model.uiState.value.isOpen)
        assertEquals(ExternalCopyStep.Failed(ExternalCopyProblem.PermissionDenied), model.step)
    }

    @Test
    fun choosing_a_calendar_copies_what_the_reader_returned() = vmTest { vms ->
        val work = calendar("Work")
        val snapshot = ExternalSnapshot(listOf(event("a"), event("b")), skipped = 1)
        val repo = FakeRepository()
        val model = vm(vms, FakeReader(calendars = listOf(work), snapshot = snapshot), repo)
        model.open()

        model.copy(work)

        val result = assertIs<ExternalCopyStep.Done>(model.step).result
        assertEquals(2, result.copied)
        assertEquals(1, result.skipped, "skipped events are reported, not swallowed")
        assertEquals(listOf(work to snapshot), repo.copies)
    }

    @Test
    fun a_calendar_that_holds_no_events_cannot_be_copied() = vmTest { vms ->
        val birthdays = calendar("Birthdays", holdsEvents = false)
        val reader = FakeReader(calendars = listOf(birthdays))
        val model = vm(vms, reader)
        model.open()

        model.copy(birthdays)

        assertIs<ExternalCopyStep.Choosing>(model.step)
        assertEquals(emptyList(), reader.reads)
    }

    /** ⚠ A second tap while the first copy runs must not read and write the calendar twice. */
    @Test
    fun a_second_tap_during_a_copy_does_nothing() = vmTest { vms ->
        val work = calendar("Work")
        val reader = FakeReader(calendars = listOf(work)).apply { gate = CompletableDeferred() }
        val model = vm(vms, reader)
        model.open()

        model.copy(work)
        model.copy(work)
        assertIs<ExternalCopyStep.Copying>(model.step)

        reader.gate!!.complete(Unit)
        assertIs<ExternalCopyStep.Done>(model.step)
        assertEquals(listOf("Work"), reader.reads)
    }

    /** ⚠ The sheet stays while a copy runs — closing it would leave no way to learn how it ended. */
    @Test
    fun the_sheet_cannot_be_dismissed_mid_copy() = vmTest { vms ->
        val work = calendar("Work")
        val reader = FakeReader(calendars = listOf(work)).apply { gate = CompletableDeferred() }
        val model = vm(vms, reader)
        model.open()
        model.copy(work)

        model.dismiss()
        assertTrue(model.uiState.value.isOpen)

        reader.gate!!.complete(Unit)
        model.dismiss()
        assertFalse(model.uiState.value.isOpen)
    }

    /** ⚠⚠ Events that were there and none placeable is a failure, never an empty success. */
    @Test
    fun a_calendar_whose_every_event_was_skipped_is_a_failure() = vmTest { vms ->
        val work = calendar("Work")
        val repo = FakeRepository()
        val model = vm(vms, FakeReader(listOf(work), ExternalSnapshot(emptyList(), skipped = 3)), repo)
        model.open()

        model.copy(work)

        assertEquals(ExternalCopyStep.Failed(ExternalCopyProblem.NothingToCopy), model.step)
        assertEquals(emptyList(), repo.copies, "nothing was written")
    }

    @Test
    fun an_honestly_empty_calendar_copies_as_empty() = vmTest { vms ->
        val work = calendar("Work")
        val model = vm(vms, FakeReader(listOf(work), ExternalSnapshot(emptyList(), skipped = 0)))
        model.open()

        model.copy(work)

        assertEquals(0, assertIs<ExternalCopyStep.Done>(model.step).result.copied)
    }

    @Test
    fun a_failed_write_is_reported_as_such() = vmTest { vms ->
        val work = calendar("Work")
        val repo = FakeRepository(failWith = IllegalStateException("Proxy not connected"))
        val model = vm(vms, FakeReader(listOf(work), ExternalSnapshot(listOf(event("a")), 0)), repo)
        model.open()

        model.copy(work)

        assertEquals(ExternalCopyStep.Failed(ExternalCopyProblem.CopyFailed), model.step)
    }

    @Test
    fun a_permission_revoked_between_listing_and_reading_says_so() = vmTest { vms ->
        val work = calendar("Work")
        val reader = FakeReader(listOf(work))
        val model = vm(vms, reader)
        model.open()
        reader.failWith = ExternalCalendarAccessDenied()

        model.copy(work)

        assertEquals(ExternalCopyStep.Failed(ExternalCopyProblem.PermissionDenied), model.step)
    }

    // ── Keep in sync (CX-1b) ─────────────────────────────────────────────────

    private class FakeMirrors(var failStart: Boolean = false) : CalendarMirrorRepository {
        val started = mutableListOf<Pair<String, Boolean>>()
        private val rows = mutableListOf<CalendarMirrorSummary>()

        /** A mirror that already exists — started earlier, here or on another device. */
        fun seed(source: ExternalCalendar, boundHere: Boolean, remindersEnabled: Boolean = false, kind: String = source.kind.name) {
            rows += CalendarMirrorSummary(
                mirrorId = "m-${source.name}",
                calendarId = "m-${source.name}-cal",
                sourceKind = kind,
                sourceKey = source.sourceKey,
                sourceLabel = source.name,
                deviceId = if (boundHere) "device-a" else "device-b",
                boundHere = boundHere,
                remindersEnabled = remindersEnabled,
                lastRefreshedEpochMs = 0L,
            )
        }

        override fun observeMirrors(): Flow<List<CalendarMirrorSummary>> = flowOf(rows.toList())
        override suspend fun mirrors(): List<CalendarMirrorSummary> = rows.toList()
        override suspend fun thisDeviceId(): String = "device-a"

        override suspend fun startMirror(
            source: ExternalCalendar,
            remindersEnabled: Boolean,
        ): CalendarMirrorSummary {
            if (failStart) error("no device id yet")
            started += source.name to remindersEnabled
            return CalendarMirrorSummary(
                mirrorId = "m-${source.name}",
                calendarId = "m-${source.name}-cal",
                sourceKind = source.kind.name,
                sourceKey = source.sourceKey,
                sourceLabel = source.name,
                deviceId = "device-a",
                boundHere = true,
                remindersEnabled = remindersEnabled,
                lastRefreshedEpochMs = 0L,
            ).also { rows += it }
        }

        override suspend fun syncMirror(mirror: CalendarMirrorSummary, snapshot: ExternalSnapshot) =
            MirrorSyncResult(written = snapshot.events.size, deleted = 0, unchanged = 0, skipped = snapshot.skipped)

        override suspend fun stampMirror(mirrorId: String, epochMs: Long) = Unit
        override suspend fun setMirrorReminders(mirrorId: String, enabled: Boolean) = Unit
        override suspend fun stopMirror(mirrorId: String) = Unit
    }

    private fun syncingVm(
        vms: OwnedViewModels,
        reader: FakeReader,
        mirrors: FakeMirrors = FakeMirrors(),
        background: MirrorBackgroundScheduler? = null,
        repo: CalendarRepository = FakeRepository(),
    ) = vms.own(
        ExternalCopyViewModel(
            repository = repo,
            reader = reader,
            mirrors = mirrors,
            refresher = MirrorRefresher(mirrors, reader, InMemoryMirrorDigestStore()) { 0L },
            background = background,
        )
    )

    /** Without mirror support a tap still copies, exactly as before CX-1b. */
    @Test
    fun choosing_without_keep_in_sync_copies_straight_away() = vmTest { vms ->
        val work = calendar("Work")
        val model = vm(vms, FakeReader(listOf(work), ExternalSnapshot(listOf(event("a")), 0)))
        model.open()

        model.choose(work)

        assertIs<ExternalCopyStep.Done>(model.step)
    }

    /** ⚠ Asked every time: the two choices leave very different calendars behind. */
    @Test
    fun choosing_with_keep_in_sync_asks_first_and_either_answer_works() = vmTest { vms ->
        val work = calendar("Work")
        val model = syncingVm(vms, FakeReader(listOf(work), ExternalSnapshot(listOf(event("a")), 0)))
        model.open()

        model.choose(work)
        assertEquals(ExternalCopyStep.Deciding(work), model.step)

        model.copy(work)
        assertIs<ExternalCopyStep.Done>(model.step)
    }

    @Test
    fun keeping_in_sync_starts_a_mirror_arms_the_background_and_refreshes_once() = vmTest { vms ->
        val work = calendar("Work")
        val mirrors = FakeMirrors()
        var armed = 0
        val model = syncingVm(
            vms,
            FakeReader(listOf(work), ExternalSnapshot(listOf(event("a"), event("b")), 0)),
            mirrors,
            background = { armed++ },
        )
        model.open()
        model.choose(work)

        model.keepInSync(work, remindersEnabled = false)

        val done = assertIs<ExternalCopyStep.Mirrored>(model.step)
        assertEquals(2, assertIs<MirrorRefreshOutcome.Synced>(done.outcome).result.written)
        assertEquals(listOf("Work" to false), mirrors.started, "reminders stay off unless asked for")
        assertEquals(1, armed, "the background refresh is armed now, not at the next launch")
    }

    @Test
    fun a_mirror_that_cannot_start_says_so() = vmTest { vms ->
        val work = calendar("Work")
        val model = syncingVm(vms, FakeReader(listOf(work)), FakeMirrors(failStart = true))
        model.open()
        model.choose(work)

        model.keepInSync(work, remindersEnabled = true)

        assertEquals(ExternalCopyStep.Failed(ExternalCopyProblem.SyncFailed), model.step)
    }

    @Test
    fun a_calendar_that_holds_no_events_offers_no_choice() = vmTest { vms ->
        val birthdays = calendar("Birthdays", holdsEvents = false)
        val model = syncingVm(vms, FakeReader(listOf(birthdays)))
        model.open()

        model.choose(birthdays)

        assertIs<ExternalCopyStep.Choosing>(model.step)
    }

    // ── A source a mirror already brings in ──────────────────────────────────

    /** Tenshu's calendars, as the chooser reads them for names. */
    private fun repoWith(backing: FakeRepository, vararg calendars: Pair<String, String>) = object : CalendarRepository by backing {
        override suspend fun calendars() = calendars.map { (id, name) ->
            CalendarSummary(id, name, 0, sensitive = false, visible = true)
        }
    }

    /**
     * ⚠⚠ The incident (2026-11-05, maintainer's Mac + iPhone): iCloud "Arbeit" was kept in sync from
     * the Mac; on the iPhone the chooser said "Bring into a new Tenshu calendar", *Copy once* made
     * "Arbeit (iCloud)" beside the mirror, and every event showed twice. The row must name the
     * mirror, and the decision must not offer the copy.
     */
    @Test
    fun a_source_kept_in_sync_from_another_device_reads_as_such_and_cannot_be_copied() = vmTest { vms ->
        val arbeit = calendar("Arbeit")
        val mirrors = FakeMirrors().apply { seed(arbeit, boundHere = false, remindersEnabled = true) }
        val backing = FakeRepository()
        val repo = repoWith(backing, "m-Arbeit-cal" to "Work (synced)")
        val model = syncingVm(vms, FakeReader(listOf(arbeit), ExternalSnapshot(listOf(event("a")), 0)), mirrors, repo = repo)
        model.open()

        val choosing = assertIs<ExternalCopyStep.Choosing>(model.step)
        val expected = MirroredSource(calendarName = "Work (synced)", boundHere = false, remindersEnabled = true)
        assertEquals(mapOf(arbeit.sourceKey to expected), choosing.mirroredInto)

        model.choose(arbeit)
        assertEquals(ExternalCopyStep.Deciding(arbeit, mirrored = expected), model.step)

        model.copy(arbeit)
        assertEquals(ExternalCopyStep.Deciding(arbeit, mirrored = expected), model.step, "Copy once is refused, not just hidden")
        assertTrue(backing.copies.isEmpty(), "nothing was copied")

        // The one way forward is a rebind, which keeps the mirror (and its calendar).
        model.keepInSync(arbeit, remindersEnabled = expected.remindersEnabled)
        assertIs<ExternalCopyStep.Mirrored>(model.step)
        assertEquals(listOf("Arbeit" to true), mirrors.started, "the rebind keeps the mirror's reminder switch")
    }

    /** ⚠ The guard is the view model's, so a sheet that skips the decision cannot copy either. */
    @Test
    fun copy_straight_from_the_list_is_refused_for_a_mirrored_source() = vmTest { vms ->
        val arbeit = calendar("Arbeit")
        val repo = FakeRepository()
        val mirrors = FakeMirrors().apply { seed(arbeit, boundHere = true) }
        val model = syncingVm(vms, FakeReader(listOf(arbeit), ExternalSnapshot(listOf(event("a")), 0)), mirrors, repo = repo)
        model.open()

        model.copy(arbeit)

        assertIs<ExternalCopyStep.Choosing>(model.step)
        assertTrue(repo.copies.isEmpty())
        val mirrored = assertIs<ExternalCopyStep.Choosing>(model.step).mirroredInto.getValue(arbeit.sourceKey)
        assertTrue(mirrored.boundHere)
        assertEquals("Arbeit", mirrored.calendarName, "an unreadable Tenshu name falls back to the source's")
    }

    /** ⚠ Mirrors read but no refresher wired: a tap on a mirrored row still says why, never nothing. */
    @Test
    fun a_mirrored_source_gets_the_decision_even_without_keep_in_sync() = vmTest { vms ->
        val arbeit = calendar("Arbeit")
        val mirrors = FakeMirrors().apply { seed(arbeit, boundHere = false) }
        val model = vms.own(ExternalCopyViewModel(FakeRepository(), FakeReader(listOf(arbeit)), mirrors = mirrors))
        model.open()

        model.choose(arbeit)

        assertIs<ExternalCopyStep.Deciding>(model.step).also { assertTrue(it.mirrored != null) }
    }

    /** ⚠ Matched on kind **and** key, the pair a mirror's id derives from — not the key alone. */
    @Test
    fun a_mirror_of_another_kind_with_the_same_key_does_not_count() = vmTest { vms ->
        val work = calendar("Work")
        val mirrors = FakeMirrors().apply { seed(work, boundHere = false, kind = "APPLE_EVENTKIT") }
        val model = syncingVm(vms, FakeReader(listOf(work), ExternalSnapshot(listOf(event("a")), 0)), mirrors)
        model.open()

        assertTrue(assertIs<ExternalCopyStep.Choosing>(model.step).mirroredInto.isEmpty())
        model.choose(work)
        model.copy(work)
        assertIs<ExternalCopyStep.Done>(model.step)
    }

    /**
     * The reverse order: copied once before, now kept in sync. Still allowed (the mirror gets its own
     * calendar, never the copy's — §11), and the decision carries the copy's name so the sheet can
     * say the copy stays.
     */
    @Test
    fun a_source_copied_before_carries_its_copy_into_the_decision() = vmTest { vms ->
        val work = calendar("Work")
        val repo = repoWith(FakeRepository(), ExternalCopyIds.calendarId(work) to "Job")
        val model = syncingVm(vms, FakeReader(listOf(work), ExternalSnapshot(listOf(event("a")), 0)), repo = repo)
        model.open()

        model.choose(work)

        assertEquals(ExternalCopyStep.Deciding(work, mirrored = null, copiedInto = "Job"), model.step)
    }

    /** ⚠ Mirrors unreadable must not stop the chooser either — rows just read as unmirrored. */
    @Test
    fun the_chooser_opens_when_mirrors_cannot_be_read() = vmTest { vms ->
        val work = calendar("Work")
        val mirrors = object : CalendarMirrorRepository by FakeMirrors() {
            override suspend fun mirrors(): List<CalendarMirrorSummary> = error("Control unreachable")
        }
        val reader = FakeReader(listOf(work))
        val model = vms.own(
            ExternalCopyViewModel(
                repository = FakeRepository(),
                reader = reader,
                mirrors = mirrors,
                refresher = MirrorRefresher(mirrors, reader, InMemoryMirrorDigestStore()) { 0L },
            )
        )
        model.open()

        assertTrue(assertIs<ExternalCopyStep.Choosing>(model.step).mirroredInto.isEmpty())
    }
}
