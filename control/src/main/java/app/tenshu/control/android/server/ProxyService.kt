package app.tenshu.control.android.server

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.tenshu.control.android.IProxyInterface
import app.tenshu.control.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.transport.BinderIpc
import org.tenshu.core.common.transport.BinderCallerAdmission
import org.tenshu.core.common.transport.BinderReadEndpoint
import org.tenshu.core.common.transport.FirstPartySigning
import org.tenshu.files.util.ParcelFileDescriptorCache

class ProxyService : Service() {

    private val proxyGateway: ProxyGateway by inject()
    private val attestedClients: app.tenshu.control.di.BinderAttestedClients by inject()
    private val fileDescriptorCache: ParcelFileDescriptorCache by inject()
    // Proxy-side liveness sources; only the user-started ones (map / model downloads) count
    // towards the foreground service (control-background-work-off-fgs.md §5.2).
    private val backgroundLiveness: BackgroundLiveness by inject()
    // Registered bulk tasks: only those a user started count, and they count between their calls.
    private val taskRegistry: org.tenshu.sync.proxy.TaskRegistry by inject()

    // drive-upload-progress.md: the upload-pass figures the notification renders. Exposed as
    // a DI single by the platform module for exactly this.
    private val syncHealthHolder: org.tenshu.sync.proxy.SyncHealthHolder by inject()
    private val syncSchedulerHolder: org.tenshu.sync.proxy.SyncSchedulerHolder by inject()

    // §14.1 (drive-upload-progress.md): which ingest runs an app is driving on this device. While
    // one is in flight the app narrates the upload itself, with the drain total this side cannot
    // derive, so the notification stands down rather than contradicting it.
    private val ingestRuns: org.tenshu.ingest.proxy.IngestRunRegistry by inject()

    // §34: the running index pass's position. ⚠ The SAME holder singleton the indexer ticks — a
    // second instance would render a bar that never moves beside a pass that is working.
    private val indexProgress: org.tenshu.inference.proxy.IndexProgressHolder by inject()

    // Main-thread so startForeground / notify / stopForeground are posted on
    // the looper; the collected StateFlow is conflated so we never queue more
    // than the latest count.
    private val livenessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // ⚠⚠ Option B (control-background-work-off-fgs.md §2, §5.2): the foreground service is
        // held for USER-STARTED work only — the in-flight tasks a client marked user-initiated,
        // plus the user-started proxy-side sources (map-region and model downloads). Play allows a
        // dataSync foreground service for nothing else. Automatic work — the sync push, the
        // maintenance pass, an automatic backup's uploads, unmarked bulk tasks — is served the
        // same way but kept alive by Control's own WorkManager jobs (SyncPushScheduler), and while a
        // client is open its binding already keeps Control alive and networked. StateFlow conflation
        // (via stateIn) means the edge machine only sees distinct transitions.
        // A user-started call names its bulk task to the registry, which then counts that task
        // between its calls too (a folder upload hashing a large video for longer than the linger).
        proxyGateway.onUserInitiatedTask = { taskId ->
            taskRegistry.markUserInitiated(taskId)
            // §5.7: a user-started upload half-opens a content plane that is waiting out refused
            // writes, so the user's own file is tried now rather than after the wait.
            syncSchedulerHolder.scheduler?.retryContentPlaneNow()
        }
        val livenessCount = foregroundLivenessCount(
            userInitiatedInFlight = proxyGateway.userInitiatedInFlightCount,
            backgroundLiveness = backgroundLiveness,
            userInitiatedTasks = taskRegistry.userInitiatedActiveCount,
        )
            .stateIn(livenessScope, SharingStarted.Eagerly, 0)
        ForegroundTaskLiveness(livenessCount, foregroundServiceHandle())
            .launchIn(livenessScope)

        // Re-post the notification as an upload pass advances. The liveness path above only
        // refreshes when the task COUNT changes, which a pass does not do — a twelve-file drain is
        // one count the whole way through, so without this the text would freeze at "1 of 12".
        // Distinct-until-changed on the pair, because the holder emits on every scheduler tick.
        // ⚠ The ingest-run signal is folded in here, not just read at build time: a run starting or
        // ending changes the message while the pass figures may not move at all, and without a
        // re-post the notification would keep whatever it said when the last pass tick landed.
        // ⚠⚠ §34 folds the index pass in for the same reason, and it is not optional: a pass raises
        // BackgroundLiveness once, so the liveness path posts ONE notification — at a moment when the
        // library walk has not finished and the total is still absent — and nothing re-posts after
        // it. The text would read "Keeping 1 background task running" for the hours the pass takes.
        // ⚠ Coarsened to every INDEX_NOTIFICATION_STEP images: the holder ticks once per image
        // (~121 ms on a phone, IN-3m), and Android silently drops a notification updated ~8×/s.
        // The build reads the holder's CURRENT value, so the posted number is exact, just not every
        // number.
        combine(
            syncHealthHolder.observe().map { it.uploadPassTotal to it.uploadPassCompleted },
            ingestRuns.runningSources.map { it.isNotEmpty() },
            indexProgress.progress.distinctUntilChangedBy { pass ->
                pass?.let { it.done / INDEX_NOTIFICATION_STEP to it.total }
            },
        ) { figures, ingesting, pass -> Triple(figures, ingesting, pass) }
            .distinctUntilChanged()
            .onEach {
                // Only while promoted: notifying otherwise would post a standalone notification
                // with no foreground service behind it (the hazard ForegroundPromotion guards).
                if (foregroundPromotion?.promoted == true) repostNotification(lastTaskCount)
            }
            .launchIn(livenessScope)
    }

    /**
     * Who may call [binder] at all: first-party Tenshu apps only (threat model T-C-20). Every method
     * derives its caller through [IProxyInterface.Stub]'s `admittedCallerPackage()`, which asks this
     * first, so no method reaches [proxyGateway] for a stranger.
     */
    private val callerAdmission by lazy {
        BinderCallerAdmission(
            selfUid = android.os.Process.myUid(),
            selfPackage = packageName,
            packagesForUid = { uid -> packageManager.getPackagesForUid(uid) },
            isFirstParty = { candidate ->
                FirstPartySigning.isFirstParty(packageManager, candidate, selfPackage = packageName)
            },
            // Logged once per uid per refusal window. The signer digests are what make a wrong pin
            // (a Play key upgrade, a new app) readable off logcat rather than a silent lock-out.
            onRefused = { uid, packages ->
                Log.w(
                    "ProxyService",
                    "Refused Binder caller uid=$uid packages=$packages signers=" +
                        packages.associateWith { FirstPartySigning.signerDigests(packageManager, it) },
                )
            },
        )
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d("ProxyService", "Bound to service")
        return binder
    }

    /**
     * Binder clients that have transacted with this service, so [onUnbind] can drop **their**
     * sessions and nobody else's. Populated on the binder thread by `admittedCallerPackage()`.
     */
    private val boundClients = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onUnbind(intent: Intent?): Boolean {
        // ⚠ Never `unregisterAll()`, which drains EVERY session in the registry — and the
        // registry holds more than this service's Binder clients. Control's own UI talks to the
        // proxy in-process and holds no binding (`clientId` == our own package), and an
        // EPC-paired peer is not a Binder client at all; both would have their session
        // cancelled and their ExecutionContext destroyed whenever the last bound app went
        // away. That is the same hazard `onDestroy` documents for `shutdownAndJoin()` — the
        // "Registering your realm" forever-spinner — reached by a different door.
        //
        // The framework calls this once all clients of this interface have disconnected, so
        // dropping exactly the ids we served is both correct and sufficient. A detached
        // Layer-2 task still defers its own session's teardown (ClientRegistry.unregisterClient).
        Log.d("ProxyService", "Unbinding from service, releasing ${boundClients.size} client session(s)")
        boundClients.forEach { proxyGateway.unregisterClient(it) }
        boundClients.clear()
        callerAdmission.forgetAdmitted()
        return super.onUnbind(intent)
    }

    /**
     * Android 15's `dataSync` time limit (6 h per 24 h): the service must leave the foreground
     * within seconds of this or the OS crashes it. The work carries on bound-only; nothing is
     * promoted again until it has gone idle (`ForegroundPromotion.onTimedOut`).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("ProxyService", "Foreground service time limit reached (type=$fgsType) — demoting")
        foregroundPromotion?.onTimedOut() ?: stopSelf()
    }

    /**
     * API 34's form of the callback. ⚠ It fires only for a `shortService` foreground service, which
     * this is not; kept so a future type change cannot crash for want of it. The `dataSync` limit
     * arrives through the two-argument form above.
     */
    override fun onTimeout(startId: Int) {
        Log.w("ProxyService", "Foreground service timed out — demoting")
        foregroundPromotion?.onTimedOut() ?: stopSelf()
    }

    override fun onDestroy() {
        livenessScope.cancel()
        // Service destruction ≠ process death: quiesce (cancel + join) the
        // sessions' background loops, but keep the ClientRegistry OPEN. The OS
        // routinely destroys this bound-only service (last Binder client
        // unbinds) while the app process — Control's own UI, which talks to
        // the proxy in-process and holds no binding — lives on. A full
        // `shutdownAndJoin()` here would close every session for good and leave each
        // in-process op dying against a cancelled sessionScope (a
        // silently-swallowed CancellationException — the "Registering your
        // realm" forever-spinner); re-binding Binder clients and the
        // session-aware local executor both mint fresh sessions after this.
        // onUnbind intentionally stays on the fire-and-forget unregisterClient()
        // path — never a join — so a detached Layer-2 task can outlive a routine
        // unbind.
        // runBlocking is consistent with the binder bridge in this file; the
        // join is not expressible synchronously in commonMain.
        runBlocking { proxyGateway.quiesceSessionsAndJoin() }
        super.onDestroy()
    }

    /**
     * Bridges [TaskLivenessHandle] to Android's foreground-service contract.
     * `startForegroundService` first promotes this otherwise bound-only
     * service into the started state so `startForeground` is permitted; the
     * service is already running in this process when the edge fires, so the
     * two calls happen back-to-back well within the OS window.
     *
     * ⚠ Neither call is guaranteed to succeed — from API 31 the OS refuses an FGS start when the
     * app holds no allowance, and an uncaught refusal is a fatal main-thread crash.
     * [ForegroundPromotion] owns the promote / degrade / retry decisions (and the reasoning); this
     * only supplies the Android effects.
     */
    /**
     * The live promotion state machine, retained (rather than only its [TaskLivenessHandle] face)
     * so the upload-progress collector can ask whether we are promoted before re-posting. Posting a
     * notification we never posted via `startForeground` would put up a standalone notification
     * with no service behind it — the hazard [ForegroundPromotion.onCountChanged] already guards.
     */
    private var foregroundPromotion: ForegroundPromotion? = null

    /** Last liveness count seen, so a progress-driven re-post keeps the fallback text accurate. */
    private var lastTaskCount: Int = 0

    private fun foregroundServiceHandle(): TaskLivenessHandle = ForegroundPromotion(
        startService = {
            ContextCompat.startForegroundService(
                this@ProxyService,
                Intent(this@ProxyService, ProxyService::class.java)
            )
        },
        startForeground = { taskCount ->
            lastTaskCount = taskCount
            ServiceCompat.startForeground(
                this@ProxyService,
                NOTIFICATION_ID,
                buildNotification(taskCount),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        },
        stopForeground = {
            ServiceCompat.stopForeground(this@ProxyService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        },
        // Ends the started lifecycle started by startForegroundService;
        // the service stays alive as long as Drive remains bound.
        stopStartedLifecycle = { stopSelf() },
        refreshNotification = { taskCount ->
            lastTaskCount = taskCount
            repostNotification(taskCount)
        },
    ).also { foregroundPromotion = it }

    /**
     * Re-posts the foreground notification under its existing [NOTIFICATION_ID] with fresh text.
     *
     * ⚠ Skipped, not attempted, when `POST_NOTIFICATIONS` is not held: the service stays alive via
     * the `startForeground()` in `onActive` either way, so only the count text goes unshown. The
     * check is explicit rather than leaning on `notify` no-op'ing — that no-op is an implementation
     * detail of the platform, and `ContextCompat.checkSelfPermission` answers correctly below
     * API 33 too (it maps to `areNotificationsEnabled()` there).
     */
    private fun repostNotification(taskCount: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(taskCount))
    }

    private fun buildNotification(taskCount: Int): Notification {
        val figures = syncHealthHolder.current()
        val pass = indexProgress.progress.value
        val content = foregroundNotificationContent(
            taskCount = taskCount,
            passTotal = figures.uploadPassTotal,
            passCompleted = figures.uploadPassCompleted,
            ingestRunning = ingestRuns.anyRunning,
            indexing = pass?.let { it.done to it.total },
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                when (content) {
                    is ForegroundNotificationContent.Uploading -> getString(
                        R.string.foreground_uploading,
                        content.current,
                        content.total,
                    )

                    is ForegroundNotificationContent.Indexing -> getString(
                        R.string.foreground_indexing,
                        content.done,
                        content.total,
                    )

                    is ForegroundNotificationContent.Tasks -> resources.getQuantityString(
                        R.plurals.foreground_task_liveness,
                        content.count,
                        content.count,
                    )
                }
            )
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.foreground_task_liveness_channel))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private val readEndpoint by lazy { BinderReadEndpoint(proxyGateway) }

    private val binder: IProxyInterface.Stub = object : IProxyInterface.Stub() {

        // Binder IPC is synchronous — the AIDL contract returns ByteArray, not a coroutine.
        // ProxyGateway.call is suspend so the class is platform-independent (works on JS too);
        // the runBlocking bridge lives here, where the synchronous transport demands it.
        override fun call(request: ByteArray): ByteArray = runBlocking {
            val clientId = admittedCallerPackage("call")
            val currentPid = getCallingPid()

            proxyGateway.call(clientId, request, currentPid)
        }

        // Same as call() but registers the client-minted taskId so a
        // separate cancel(taskId) transaction can unwind requestJob. Blocks
        // this binder-pool thread for the task's duration; cancel() runs on a
        // different pool thread. On cancellation the client has already
        // abandoned its waiter — binder still needs a byte[], so return empty.
        override fun callCancellable(request: ByteArray, taskId: String): ByteArray =
            runBlocking {
                val clientId = admittedCallerPackage("callCancellable")
                val currentPid = getCallingPid()
                try {
                    proxyGateway.call(clientId, request, currentPid, taskId)
                } catch (e: CancellationException) {
                    ByteArray(0)
                }
            }

        // Cancellable reads (core-background-processing.md §6.2.1). Revision first: a client asks
        // before it uses the two methods below, and an older Control answers 0 by not having this.
        override fun ipcRevision(): Int {
            admittedCallerPackage("ipcRevision")
            return BinderIpc.REVISION
        }

        // A plain call the client may stop, served on the caller's session (pid) like call().
        // ⚠⚠ Registered by (package, readId) — NO pid: cancelRead is oneway, and a oneway
        // transaction does not report the caller's pid. BinderReadEndpoint carries the why.
        override fun callCancellableRead(request: ByteArray, readId: String): ByteArray =
            readEndpoint.callCancellableRead(admittedCallerPackage("callCancellableRead"), getCallingPid(), request, readId)

        // oneway, like cancel(): returns to the (already cancelled) caller at once.
        override fun cancelRead(readId: String) {
            readEndpoint.cancelRead(admittedCallerPackage("cancelRead"), readId)
        }

        // Revision 2 (control-background-work-off-fgs.md §5.1): a task the user started. The ONLY
        // kind of task that counts towards the foreground service; otherwise identical.
        override fun callCancellableUserInitiated(request: ByteArray, taskId: String): ByteArray =
            runBlocking {
                val clientId = admittedCallerPackage("callCancellableUserInitiated")
                val currentPid = getCallingPid()
                try {
                    proxyGateway.call(clientId, request, currentPid, taskId, userInitiated = true)
                } catch (e: CancellationException) {
                    ByteArray(0)
                }
            }

        // oneway → returns to the caller immediately. Same clientId derivation
        // as callCancellable (same calling package) so the (clientId, taskId)
        // registry key matches; unknown/completed id is a no-op.
        override fun cancel(taskId: String) {
            runBlocking {
                proxyGateway.cancel(admittedCallerPackage("cancel"), taskId)
            }
        }

        override fun sendFile(
            fileDescriptor: ParcelFileDescriptor,
            filePath: String
        ) {
            // Scoped to the calling package, not to `filePath` alone. One cache serves every
            // Binder client and the key is whatever the caller passed, so an unscoped `put`
            // let any client overwrite the descriptor another was about to upload under —
            // substituting the bytes that reach the realm. `admittedCallerPackage()` is
            // kernel-derived, so the namespace is the one thing here a caller cannot choose.
            //
            // ⚠ A refused caller's descriptor is closed before the refusal: it is ours once it is in
            // this process, and leaving it to a finalizer lets a stranger spend our descriptor table.
            val sender = try {
                admittedCallerPackage("sendFile")
            } catch (e: SecurityException) {
                runCatching { fileDescriptor.close() }
                throw e
            }
            fileDescriptorCache.putFrom(sender, filePath, fileDescriptor)
        }

        /**
         * The calling app's package, resolved from the **kernel-supplied uid** — a caller
         * cannot state its own name here, which is what makes Android's client identity
         * OS-attested (threat-model A14's Android counterpart).
         *
         * ⚠⚠ **This is also the gate** (threat model T-C-20): a caller that is not a first-party
         * Tenshu app gets a [SecurityException] here, before any of its bytes reach the gateway.
         * Every method takes its client id from this function, so none can serve a stranger. A
         * method added without it would have no client id to pass on.
         *
         * Each resolved id is recorded as attested (`control-app-catalogue.md` C4), which is what
         * `AppRegistration.verified` reports on Android.
         */
        private fun admittedCallerPackage(method: String): String {
            val packageName = callerAdmission.requireAdmitted(getCallingUid(), method)
            attestedClients.record(packageName)
            boundClients.add(packageName)
            return packageName
        }
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_foreground_task_liveness"
        const val NOTIFICATION_ID = 0xC047

        /**
         * How many indexed images move the notification on (§34).
         *
         * ⚠ ~2.5 s of work at IN-3m's measured 121 ms/image — often enough to look live, rare
         * enough that Android does not start dropping the updates.
         */
        const val INDEX_NOTIFICATION_STEP = 20
    }
}
