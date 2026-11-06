package app.tenshu.photos.android

import android.app.Application
import app.tenshu.photos.di.photosClientModule
import app.tenshu.photos.di.photosModules
import app.tenshu.photos.di.platformClientModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

/**
 * Photos Android Application (`docs/proposals/photos-app.md` PH-1). Starts Koin with the client
 * graph (IPC to Control over Binder) plus the app modules.
 *
 * ⚠ The schema-lockstep beacon is not boilerplate: HR #9 keeps clients moving across a service
 * version bump in lockstep, and the smoke gate asserts every installed app logs the same line as
 * Control. This app ships in the same PR as a `FileService` bump, so it is the first thing that
 * would be caught out of step.
 */
class PhotosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))
        startKoin {
            androidLogger()
            androidContext(this@PhotosApplication)
            modules(listOf(photosClientModule, platformClientModule) + photosModules())
        }
    }
}
