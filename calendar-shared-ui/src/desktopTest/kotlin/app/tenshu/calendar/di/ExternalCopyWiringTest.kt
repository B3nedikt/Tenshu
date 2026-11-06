package app.tenshu.calendar.di

import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarMirrorSummary
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.MirrorSyncResult
import app.tenshu.calendar.data.NotConnectedCalendarRepository
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.calendar.data.external.InMemoryMirrorDigestStore
import app.tenshu.calendar.data.external.MirrorBackgroundScheduler
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.calendar.ui.ExternalCopyViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * ⚠⚠ The production view-model module, resolved with a platform that has a calendar store, offers
 * *Keep in sync*. Found by review: a merge left a second, older `ExternalCopyViewModel` definition
 * later in the module, which silently replaced the mirror-aware one — every unit test of the view
 * model passed, and the feature was unreachable in the app.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExternalCopyWiringTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private object Reader : ExternalCalendarReader {
        override suspend fun calendars(): List<ExternalCalendar> = emptyList()
        override suspend fun read(calendar: ExternalCalendar) = ExternalSnapshot(emptyList(), skipped = 0)
    }

    private object Mirrors : CalendarMirrorRepository {
        override fun observeMirrors() = emptyFlow<List<CalendarMirrorSummary>>()
        override suspend fun mirrors(): List<CalendarMirrorSummary> = emptyList()
        override suspend fun startMirror(source: ExternalCalendar, remindersEnabled: Boolean) = error("unused")
        override suspend fun syncMirror(mirror: CalendarMirrorSummary, snapshot: ExternalSnapshot): MirrorSyncResult = error("unused")
        override suspend fun stampMirror(mirrorId: String, epochMs: Long) = Unit
        override suspend fun setMirrorReminders(mirrorId: String, enabled: Boolean) = Unit
        override suspend fun stopMirror(mirrorId: String) = Unit
        override suspend fun thisDeviceId(): String? = "device-a"
    }

    @Test
    fun the_module_wires_keep_in_sync_where_a_calendar_store_exists() {
        val koin = koinApplication {
            modules(
                calendarViewModelsModule(),
                module {
                    single<CalendarRepository> { NotConnectedCalendarRepository() }
                    single<ExternalCalendarReader> { Reader }
                    single<CalendarMirrorRepository> { Mirrors }
                    single { MirrorRefresher(Mirrors, Reader, InMemoryMirrorDigestStore()) { 0L } }
                    single { MirrorBackgroundScheduler { } }
                },
            )
        }.koin

        val model = koin.get<ExternalCopyViewModel>()
        assertTrue(model.supported, "the reader reached the view model")
        assertTrue(model.canKeepInSync, "the mirror repository and refresher reached it too")
    }
}
