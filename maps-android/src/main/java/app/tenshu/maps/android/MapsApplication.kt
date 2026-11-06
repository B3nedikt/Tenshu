package app.tenshu.maps.android

import android.app.Application
import app.tenshu.maps.di.mapsClientModule
import app.tenshu.maps.di.mapsModules
import app.tenshu.maps.di.platformClientModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

// Maps Android Application (docs/proposals/maps-app.md §6 Ph0). Starts Koin with the app
// modules (Ph0: empty); the mock repository binds in Ph2, the real :place-service store
// swaps in behind the same interface in Ph9.
class MapsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (smoke gate asserts Control + clients share this).
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))
        startKoin {
            androidLogger()
            androidContext(this@MapsApplication)
            // The client graph (IPC to Control) + the app modules. The region picker now reaches
            // the real MapRegionService in Control over Binder (D6).
            modules(listOf(mapsClientModule, platformClientModule) + mapsModules())
        }
    }
}
