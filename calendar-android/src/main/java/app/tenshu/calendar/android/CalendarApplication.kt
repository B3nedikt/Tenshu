package app.tenshu.calendar.android

import android.app.Application
import app.tenshu.calendar.android.mirror.MirrorJobs
import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.calendar.data.external.MirrorBackgroundScheduler
import app.tenshu.calendar.data.external.MirrorDigestStore
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.calendar.data.external.ProviderCalendarReader
import app.tenshu.calendar.data.external.SharedPreferencesMirrorDigestStore
import app.tenshu.calendar.di.calendarClientModule
import app.tenshu.calendar.di.calendarModules
import app.tenshu.calendar.di.platformClientModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

// Calendar's Android Application (calendar-app.md CA-2b). Starts Koin with the client
// graph (IPC to Control over Binder) plus the app modules; the month and agenda views read
// the real `:calendar-service` rows Control hosts.
class CalendarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (smoke gate asserts Control + clients share this).
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))
        startKoin {
            androidLogger()
            androidContext(this@CalendarApplication)
            modules(listOf(calendarClientModule, platformClientModule) + calendarModules() + deviceCalendarModule)
        }
        // CX-1b: arm the *Keep in sync* jobs. ⚠ Cheap and idempotent, and a no-op without
        // `READ_CALENDAR`; the content-trigger job does not survive a reboot, so every start re-arms it.
        MirrorJobs.schedule(this)
    }
}

/**
 * The calendar-store reader for *Copy from this phone* (CX-1). ⚠ Bound only here: its absence is
 * what keeps the flow off platforms with no store (`ExternalCopyViewModel.supported`).
 */
private val deviceCalendarModule = module {
    single<ExternalCalendarReader> { ProviderCalendarReader(androidContext().contentResolver) }

    // CX-1b — *Keep in sync*. ⚠ The clock enters here, at the composition root, like every other
    // "now" in Calendar.
    single<MirrorDigestStore> { SharedPreferencesMirrorDigestStore(androidContext()) }
    single<MirrorBackgroundScheduler> { MirrorJobs }
    single { MirrorRefresher(get(), get(), get(), nowEpochMs = { System.currentTimeMillis() }) }
}
