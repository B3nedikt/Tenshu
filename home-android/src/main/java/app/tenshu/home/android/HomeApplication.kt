package app.tenshu.home.android

import android.app.Application
import app.tenshu.home.di.homeClientModule
import app.tenshu.home.di.homeModules
import app.tenshu.home.di.homePlatformClientModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

// Home Android Application (docs/proposals/home-and-mia-apps.md §6). HD-3: Home is a real
// Tenshu client here, not a mock viewer — `homeClientModule` reaches Control's proxy over
// Binder for hub-authored `HubStatus` / `HomeDevice` / `DeviceState` rows and issues device
// commands. Module order is override order and the client modules come LAST on purpose:
// their `HomeDevicesRepository` binding replaces the mock household. With Control absent,
// sealed, or no hub paired, every read comes back empty and the tab falls back to the mock
// — Home still launches standalone.
class HomeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (the smoke gate asserts Control + clients share this).
        // Home owes one from this slice on: it parses hub-authored rows, so a stale build
        // is exactly the HR #9 silent-garbling case.
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))

        startKoin {
            androidLogger()
            androidContext(this@HomeApplication)
            modules(homeModules() + listOf(homeClientModule, homePlatformClientModule))
        }
    }
}
