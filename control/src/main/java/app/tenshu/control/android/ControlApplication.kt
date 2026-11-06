package app.tenshu.control.android

import app.tenshu.control.billing.androidBillingModule
import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.tenshu.liveness.AppForegroundState
import app.tenshu.control.data.AppLockController
import app.tenshu.control.di.dataModule
import app.tenshu.control.di.platformProxyModule
import app.tenshu.control.di.proxyModule
import app.tenshu.control.di.viewModelsModule
import app.tenshu.control.android.server.HostPairingServiceLauncher
import app.tenshu.control.notifications.AndroidPendingRequestNotifier
import app.tenshu.control.notifications.AndroidSchemaFenceNotifier
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.sync.HostPairingMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine
import org.tenshu.sync.proxy.SyncHealthHolder

class ControlApplication : Application() {

    // Process-lifetime scope for the live OS-elevation observer (central-notifications).
    // Gives near-real-time alerts *while the process is alive*; closed-process
    // delivery rides the DrainSync post-drain hook instead (PlatformProxyModule).
    private val notifierScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (smoke gate asserts Control + clients share this).
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))

        val koin = startKoin {
            androidLogger()
            androidContext(this@ControlApplication)
            modules(dataModule, platformProxyModule, proxyModule, viewModelsModule, androidBillingModule)
        }.koin

        // Surface inbound actionable requests as HIGH-importance system notifications
        // while Control's process is alive (§4.3.4); the same notifier is reused by the
        // DrainSync post-drain hook for the backgrounded/closed case.
        val notifier = koin.get<AndroidPendingRequestNotifier>()
        val repository = koin.get<PendingRequestsRepository>()
        // Drive elevation off `unread`: a synced dismissal cancels the OS
        // notification on every device, mirroring the badge.
        notifier.start(notifierScope, repository.unread)

        // "Update Control to keep syncing" when the schema pull fence engages. Fed by the
        // in-process verdict the scheduler writes, so it fires from a background DrainSync wake
        // too; the notifier persists "already alerted", so a fenced device is told once, not
        // once per wake.
        AndroidSchemaFenceNotifier(this).start(
            notifierScope,
            koin.get<SyncHealthHolder>().observeSchemaFence(),
        )

        // control-pairing-ux.md §3.5: keep Control's process — and the in-memory ephemeral pairing
        // key — resident while a host pairing session is underway, via a dataSync FGS started on
        // the HostPairingMonitor's idle → pairing edge. The FGS notification doubles as the
        // backgrounded-approval surface (§3.6). ⚠ An FGS start is refusable from API 31, and an
        // uncaught refusal is fatal. The launcher attempts eagerly and uses this process-foreground
        // signal to retry a refusal — see armForegroundService.
        HostPairingServiceLauncher(
            appContext = this,
            monitor = koin.get<HostPairingMonitor>(),
            foreground = AppForegroundState.observeProcess().isForeground,
        ).start()

        // Soft app-lock (control-keep-device-unlocked.md §4.2, toggle 1): re-arm the
        // gate whenever the whole app goes to background, so foregrounding Control
        // again re-prompts. ProcessLifecycle ON_STOP fires once *all* activities stop
        // (not on config-change/recreate), so it doesn't spuriously re-lock. The gate
        // itself (AppLockGate in MainActivity) reacts to the controller's locked flow.
        val appLock = koin.get<AppLockController>()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    appLock.onEnterBackground()
                }
            }
        )
    }
}
