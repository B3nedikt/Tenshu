package app.tenshu.fit.android

import android.app.Application
import app.tenshu.fit.di.fitClientModule
import app.tenshu.fit.di.fitModules
import app.tenshu.fit.di.platformClientModule
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

// Fit Android Application (docs/proposals/fit-app.md). Starts Koin with the standalone data graph +
// a best-effort region client reaching Control over Binder for real offline basemaps on workout
// routes (maps-region-delivery.md §7); if Control is absent the route keeps the bundled demo
// (bindService returning false is non-fatal).
class FitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (the smoke gate asserts Control + clients share this).
        // Fit was the one Android app without it, which the gate reported as a WARN rather
        // than a version — and Fit is emphatically not a Mia-style mock: it reads and writes
        // Control-authored Workout rows, so a silent lockstep skew here garbles real data.
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))
        startKoin {
            androidLogger()
            androidContext(this@FitApplication)
            modules(listOf(fitClientModule, platformClientModule) + fitModules() + fitAndroidModule())
        }
        // Arms the import foreground service: it starts on the idle → in-flight edge of an import and
        // keeps Fit alive (with a progress notification) through the minutes-long Takeout import.
        // ⚠ That start is refusable (fatal before 2026-07-30 — see FitImportServiceLauncher's KDoc);
        // the launcher is handed process foregroundedness so it can retry a refusal.
        get<FitImportServiceLauncher>().start()
    }
}
