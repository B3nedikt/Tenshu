@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.di.buildPlatformProxyModule
import app.tenshu.control.di.buildProxyModule
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaFence
import org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps
import org.tenshu.core.common.proxy.db.sqlite.RegistryReplicaMigrationSteps
import org.tenshu.core.common.proxy.db.sqlite.ReplicaOpenResult
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaEntryToken
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaLoops
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import org.tenshu.core.common.proxy.db.sqlite.ReplicaRunOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDeletion
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaWipeStart
import org.tenshu.core.common.proxy.db.sqlite.WipeRefused
import org.tenshu.core.common.proxy.db.sqlite.HoldCeilingExceeded
import org.tenshu.core.common.proxy.db.sqlite.ProtectedDataUnavailable
import org.tenshu.core.common.proxy.db.sqlite.ReplicaJob
import org.tenshu.core.common.proxy.db.sqlite.StoreBeingChecked
import org.tenshu.core.common.proxy.db.sqlite.StoreNeedsForeground
import org.tenshu.core.common.proxy.db.sqlite.StoreUnavailable
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.sync.proxy.SyncProxyComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * Where the SQLite shared replica lives (`ios-shared-store-sqlite.md` §2.2).
 *
 * ⚠⚠ **There is no switch any more** (Appendix B.22, decided by the maintainer 2026-11-05): every
 * first-party iOS app and Control binds the SQLite store, in every build configuration and against
 * any relay. The plist key `TenshuSharedReplicaSqlite` and the dev-lab relay guard (B.13 (180),
 * B.21 Q62) are gone: the guard's reason, two replicas under one device id, ended with the old file
 * store (B.22.9: deleted, no process runs it).
 */
internal object SharedReplicaLocation {
    /** `<group container>/replica`: §2.2. */
    const val SUBDIRECTORY: String = "replica"
}

/**
 * The SQLite store could not be handed to a runtime. [state] is what the host shows for it;
 * ⚠⚠ [retryable]: "not ready, try again" (Q35, Appendix B.16 (214)): the host rebuilds at the next
 * protected entry or foreground. ⚠⚠ Not retryable (a newer build's store, a corrupt one, an
 * extension, no worker): **the stand-in too since B.17 (254)** (Q40), with
 * its surface (the update surface, "Storage damaged", "Storage not ready"). [wipe]: an `UPDATING`
 * whose job is a wipe ("Refreshing…", not "Updating storage…").
 */
internal class SharedReplicaNotReady(
    message: String,
    cause: Throwable? = null,
    val state: SharedReplicaHostState = SharedReplicaHostState.NOT_READY,
    val retryable: Boolean = false,
    val wipe: Boolean = false,
    /**
     * B.17 (286): the open met a job the worker was handed; a caller allowed to wait for it does
     * so **after** the open has let go of its lock, then opens again.
     */
    val awaitableJob: ReplicaJob? = null,
) : IllegalStateException(message, cause)

/**
 * ⚠⚠ **The binding of the SQLite shared replica into a route-1 runtime** (Appendix B.13), one per
 * process ([process]). Every production assembly makes it since B.22. In order, and each once:
 *
 * 1. [installAtLaunch], on the main thread before the graph is built: **Q24's assertion** (an app
 *    extension has no `UIApplication`, so it never gets here), the UIKit adapter made, the store's
 *    directory **attached** to its lifecycle (protection and worker), then the adapter's
 *    `install` with [loops] (B.12 (163): install after every registry is attached).
 * 2. [open], from the graph's `ObjectStore` binding: the production `SharedReplicaStore.open`,
 *    which itself refuses a registry not under an installed protection (B.11.4 (1)).
 * 3. [bindLoops], once the graph exists: the runtime's loops that rule P stops and starts.
 *
 * Internal: Swift never sees it.
 */
internal class SharedReplicaBinding(
    val directory: String,
    private val appName: String,
    private val seams: Seams = PlatformSeams,
    private val adapterFactory: ((SharedReplicaJobOutcome) -> Unit) -> SharedReplicaUiKitAdapter =
        { onJobOutcome -> SharedReplicaUiKitAdapter(onJobOutcome = onJobOutcome) },
    /**
     * How long a background rebuild's open waits, **off the main thread** ([preOpen]), for the
     * worker to finish a job it was handed (B.16 (227), B.17 (258)). The graph's own open never waits.
     */
    private val updatingWaitMs: Long = UPDATING_WAIT_MS,
) {
    /** What the binding asks of the process, behind a seam for the tests. */
    interface Seams {
        val isMainThread: Boolean

        /** This process is an app extension (its bundle is an `.appex`). */
        fun isAppExtension(): Boolean

        fun open(directory: String, build: ReplicaBuild, steps: ReplicaMigrationSteps, appName: String, foreground: Boolean, protectedDataAvailable: () -> Boolean): ReplicaOpenResult

        /** The store-less wipe with its chunks after the first handed to the worker (B.13 (178)). */
        fun startWipe(directory: String, build: ReplicaBuild, appName: String, reason: String, protectedDataAvailable: () -> Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart

        /** The deletion of an unwipeable store's files (B.13 (179)). */
        fun deleteFiles(directory: String): SharedReplicaDeletion
    }

    object PlatformSeams : Seams {
        override val isMainThread: Boolean get() = NSThread.isMainThread

        override fun isAppExtension(): Boolean = NSBundle.mainBundle.bundlePath.endsWith(".appex")

        override fun open(directory: String, build: ReplicaBuild, steps: ReplicaMigrationSteps, appName: String, foreground: Boolean, protectedDataAvailable: () -> Boolean): ReplicaOpenResult =
            SharedReplicaStore.open(directory, build, steps, appName, foreground, protectedDataAvailable)

        override fun startWipe(directory: String, build: ReplicaBuild, appName: String, reason: String, protectedDataAvailable: () -> Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart =
            SharedReplicaStore.startWipeSharedReplica(directory, build, WipeJobsNeverMigrate, appName, reason, protectedDataAvailable, beforeHandOff = beforeHandOff)

        override fun deleteFiles(directory: String): SharedReplicaDeletion = SharedReplicaStore.deleteSharedReplicaFiles(directory)
    }

    private val lock = NSRecursiveLock()
    private var adapter: SharedReplicaUiKitAdapter? = null

    /** Serialises [open]; held across its bounded wait, so never taken by [wake] or the outcomes. */
    private val opening = NSRecursiveLock()
    /** ⚠ Volatile: [recheckChoice] reads it at a foreground without [opening], which a background rebuild's bounded wait may hold (B.17 (258)). */
    @kotlin.concurrent.Volatile
    private var opened: SharedReplicaStore? = null

    /**
     * The build the graph's last open judged the store by, with its migration chains: what [preOpen]
     * opens with (B.17 (258)). ⚠ Both, always: a build without its chains would wipe a store it can migrate.
     */
    private var lastBuild: Pair<ReplicaBuild, ReplicaMigrationSteps>? = null

    /**
     * D3b's directories, beside `replica/` in the container. ⚠⚠ And `sync-hlc/`, the HLC watermark
     * (B.21.6 (414), done in B.22): a watermark restored from a backup beside a store that re-pulls
     * over-states what that store holds and forges the relay's tombstone-GC ack (T-T-08).
     */
    private val contentDirectories = listOf(
        org.tenshu.core.common.proxy.IosFileDatabase.CONTENT_SUBDIRECTORY,
        UPLOAD_STAGING,
        org.tenshu.core.common.proxy.db.HlcWatermarkFile.SUBDIRECTORY,
    )

    /** The loops rule P drives, bound once the graph exists ([bindLoops]). Handed to `install` empty. */
    val loops = DeferredReplicaLoops()

    private val outcomes = NSCondition()
    private var outcomeCount = 0L
    private var lastOutcome: SharedReplicaJobOutcome? = null

    /** The custody purge owed once a handed-off wipe finishes (B.13 (178)); taken by the first `REPLACEMENT_FINISHED`. */
    private var purgeOnFinish: PendingPurge? = null

    /**
     * The purge owed to **one** handed-off job (B.13 (183)): released by that job's finish only.
     * [notFinished] is told that job's other `REPLACEMENT` outcomes and releases nothing (B.14 (191)).
     */
    private class PendingPurge(val jobId: Long, val purge: () -> Unit, val notFinished: (SharedReplicaJobOutcome) -> Unit = {})

    /** True once [installAtLaunch] has attached and installed. */
    val isInstalled: Boolean get() = locked { adapter != null }

    /**
     * ⚠⚠ The process's runtime host of §6 (Appendix B.16): the fence lifecycle over whatever graph
     * the runtime built over this binding's store. Told every foreground after the choice's re-read.
     */
    val host: SharedReplicaHost = SharedReplicaHost(
        isForegroundSource = { locked { adapter }?.lifecycle?.isForeground == true },
        release = ::releaseForRebuild,
        unbindLoops = { mine -> loops.unbindIf(mine) },
        currentLoops = { loops.currentBinding() },
        protectEntry = { onExpired -> locked { adapter }?.lifecycle?.protectEntry("rebuild", onExpired) },
        preOpen = ::preOpen,
        releasePrepared = { handle -> releasePrepared(handle) },
    )

    /**
     * ⚠⚠ Appendix B.18 (Q47): the damaged store's copy in this process: Control's snapshot (its
     * [DamagedDataCopies.exporter], set by `ControlSharedReplica`) or every other app's look.
     */
    val damagedCopies = DamagedDataCopies(directory)

    /** The process is in the foreground, by the installed adapter's lifecycle; false before [installAtLaunch]. */
    internal fun isForeground(): Boolean = locked { adapter }?.lifecycle?.isForeground == true

    /**
     * ⚠⚠ Q24 (decided 2026-10-18), then attach, then install: on the main thread, once per process
     * (a second call returns). Throws [SharedReplicaNotReady] in an app extension, before anything
     * is made: an extension opens the store only once it has its own `SharedReplicaBackgroundTime`.
     */
    fun installAtLaunch() = locked {
        if (adapter != null) return@locked
        if (seams.isAppExtension()) {
            throw SharedReplicaNotReady(
                "[replica-binding] Q24: an app extension does not open the shared store until it has its own SharedReplicaBackgroundTime",
            )
        }
        check(seams.isMainThread) { "[replica-binding] installAtLaunch is called on the main thread" }
        val made = adapterFactory(::recordOutcome)
        made.onForeground = ::foreground
        made.onProtectedDataAvailable = ::protectedDataAvailable
        // ⚠⚠ B.12 (163): every registry attached BEFORE install. The protection refuses an attach
        // after install; this order is what makes the production open's check pass.
        made.lifecycle.attach(directory)
        made.install(loops = loops)
        adapter = made
    }

    /**
     * The store for this process's runtime, opened through the production entry. ⚠⚠ **Never waits
     * for a job** (B.17 (258), Q41: Q29's 10 s wait on the main thread is gone): a store being
     * wiped or migrated throws a retryable [SharedReplicaNotReady] at once, the graph takes the
     * stand-in ("Updating storage…" or "Refreshing…"), and the job's finish rebuilds it (226).
     * Every other answer that is not a store throws [SharedReplicaNotReady] with its state.
     */
    fun open(build: ReplicaBuild, steps: ReplicaMigrationSteps): SharedReplicaStore = openLocked(build, steps) { true }

    /**
     * ⚠⚠ B.17 (258), Q41: the host's open **off the main thread** before a rebuild's main-thread
     * turn, with the build of the graph's last open (the same binary, so the same vector). True
     * when a store is open now, which the reinstall's own [open] then finds. [waitForJob]: a
     * background rebuild's bounded wait for a job the worker runs (B.16 (227)). Never throws.
     */
    internal fun preOpen(waitForJob: Boolean, stillCurrent: () -> Boolean = { true }): SharedReplicaStore? {
        if (seams.isMainThread) {
            // A seam the host never crosses in production: the wait below is never taken on main.
            println("[replica-binding] preOpen on the main thread: not opened here")
            return null
        }
        val (build, steps) = opening.withLockedValue { lastBuild } ?: return null
        // Before the first open: a finish that lands between it and the wait is still seen.
        val before = outcomesSoFar()
        val store = try {
            openLocked(build, steps, stillCurrent)
        } catch (e: SharedReplicaNotReady) {
            val job = e.awaitableJob
            if (!waitForJob || job == null) {
                println("[replica-binding] the open before the rebuild did not serve (${e.message}): the reinstall opens again")
                return null
            }
            // ⚠⚠ B.17 (286), Codex P2 at a20abbf: the bounded wait runs with NO lock held, so a
            // main-thread release (a foreground rebuild's step 2b, a forget's) never waits behind
            // it; the open after it takes the lock again and re-checks the ticket (273).
            val outcome = awaitReplacementOutcome(before)
            if (outcome == null || !outcome.reopen) {
                println("[replica-binding] the open before the rebuild did not serve (${notReopened(job, outcome).message}): the reinstall opens again")
                return null
            }
            try {
                openLocked(build, steps, stillCurrent)
            } catch (t: Throwable) {
                println("[replica-binding] the open after the job did not serve (${t::class.simpleName}: ${t.message}): the reinstall opens again")
                return null
            }
        } catch (t: Throwable) {
            println("[replica-binding] the open before the rebuild did not serve (${t::class.simpleName}: ${t.message}): the reinstall opens again")
            return null
        }
        // ⚠ B.17 (281): a foreground's re-read of the replica choice that found no handle open
        // (this wait held none yet) is made now, before the store is handed to a graph.
        if (choiceDisallows()) {
            recheckChoice()
            return null
        }
        return store
    }

    /** The choice no longer allows the SQLite store. A throw reads as "not chosen", as in [recheckChoice]. */
    private fun choiceDisallows(): Boolean {
        val check = stillChosen ?: return false
        return try {
            !check()
        } catch (t: Throwable) {
            true
        }
    }

    /**
     * B.17 (273): the handle a host's prepare opened for a rebuild that then bailed, released if it
     * is still the cached one (on main, in the bailing rebuild's turn).
     */
    internal fun releasePrepared(handle: Any) {
        opening.lock()
        try {
            val current = opened ?: return
            if (current !== handle) return
            opened = null
            if (current.fencedBy != ReplicaFence.RELEASED) current.close()
        } finally {
            opening.unlock()
        }
        println("[replica-binding] the handle opened for a rebuild that did not happen was released")
    }

    /** The open under [opening], which **never waits for a job** (B.17 (258), (286)). */
    private fun openLocked(build: ReplicaBuild, steps: ReplicaMigrationSteps, stillCurrent: () -> Boolean): SharedReplicaStore {
        val installed = locked { adapter } ?: throw SharedReplicaNotReady("[replica-binding] open before installAtLaunch")
        opening.lock()
        try {
            // ⚠⚠ B.17 (273): re-checked UNDER the open lock. A forget holds the graph (moving the
            // ticket) before its `releaseOpened`, which takes this lock, and before its wipe: so
            // either this sees the hold and opens nothing, or it opens first and the forget's
            // release then closes what it opened, before the wipe starts. Never a pre-wipe handle.
            if (!stillCurrent()) throw SharedReplicaNotReady("[replica-binding] the rebuild this open was for was superseded (a hold or a forget)")
            lastBuild = build to steps
            // ⚠ One runtime handle per path per process (§2.8): a later assembly in this process
            // (a re-provision) gets the store already open, unless it was fenced, which is
            // released first. Rebuilding a graph over a fenced one is S0b-3's.
            val current = opened
            if (current != null && current.fencedBy == null) return current
            if (current != null) {
                opened = null
                if (current.fencedBy != ReplicaFence.RELEASED) current.close()
            }
            return openAnew(installed, build, steps).also { opened = it }
        } finally {
            opening.unlock()
        }
    }

    private fun openAnew(installed: SharedReplicaUiKitAdapter, build: ReplicaBuild, steps: ReplicaMigrationSteps): SharedReplicaStore =
        when (val first = openOnce(installed, build, steps)) {
            is ReplicaOpenResult.Ready -> first.store
            is ReplicaOpenResult.Updating -> {
                val wipe = first.job == ReplicaJob.WIPE
                if (!first.scheduled) throw SharedReplicaNotReady("[replica-binding] updating (${first.job}) with no worker to run it", state = SharedReplicaHostState.UPDATING, wipe = wipe)
                // ⚠⚠ B.17 (258), Q41 (B.16 (227) widened to the launch): no open waits for the
                // worker here, under the lock: "not ready", the stand-in, and the job's finish
                // schedules the rebuild (226). Only [preOpen] waits, outside the lock (286).
                throw SharedReplicaNotReady("[replica-binding] updating (${first.job}): the open does not wait for it", state = SharedReplicaHostState.UPDATING, retryable = true, wipe = wipe, awaitableJob = first.job)
            }
            is ReplicaOpenResult.Skew -> throw SharedReplicaNotReady("[replica-binding] the store was written by a newer build: ${first.detail}", state = SharedReplicaHostState.SKEW)
            is ReplicaOpenResult.Corrupt -> throw SharedReplicaNotReady("[replica-binding] the store is marked corrupt: ${first.detail}", state = SharedReplicaHostState.CORRUPT)
        }

    /**
     * A job the worker was handed and did not finish within the wait (B.16 (215)): retryable,
     * "Updating storage…" or "Refreshing…", unless the job's run found it may not go on (skew,
     * corrupt), which is not retryable (their surfaces, B.17 (254)).
     */
    private fun notReopened(job: ReplicaJob, outcome: SharedReplicaJobOutcome?): SharedReplicaNotReady {
        val why = "[replica-binding] updating ($job): ${outcome ?: "not finished within $updatingWaitMs ms"}"
        return when (outcome?.kind) {
            SharedReplicaJobOutcome.Kind.REPLACEMENT_SKEW -> SharedReplicaNotReady(why, state = SharedReplicaHostState.SKEW)
            SharedReplicaJobOutcome.Kind.REPLACEMENT_CORRUPT -> SharedReplicaNotReady(why, state = SharedReplicaHostState.CORRUPT)
            else -> SharedReplicaNotReady(why, state = SharedReplicaHostState.UPDATING, retryable = true, wipe = job == ReplicaJob.WIPE)
        }
    }

    /**
     * ⚠⚠ The D3 order for the custody purge (§5.1, Appendix B.13 (171), (178), (179)): the store
     * first, custody after. **True when the purge may go on now**: there was no store, or its files
     * were deleted. **False when custody must be kept for now**:
     * - the wipe was **handed to the worker** (178): it never runs whole on the caller, which is
     *   the main thread at launch under the watchdog. [purgeLater] is run once, on the worker's
     *   queue, when that wipe is told `REPLACEMENT_FINISHED`; if the run stops instead, custody
     *   stays and the next launch's deprovision starts again (`meta.wipe` is joined);
     * - the store's files could not be deleted (a connection is open on it somewhere);
     * - anything else failed, an off-main call or an extension included (never thrown).
     *
     * A store that can **never** be wiped, corrupt or a newer build's, has its files deleted
     * outright (179, the maintainer's decision of 2026-10-19), and the purge goes on.
     */
    fun wipeBeforeCustodyPurge(build: ReplicaBuild, purgeLater: () -> Unit): Boolean =
        startCustodyWipe(build, onFinished = purgeLater, rebuildAfter = false) == CustodyWipe.PURGE_NOW

    /** How [startCustodyWipe] left custody. */
    enum class CustodyWipe {
        /** No store, a wipe finished, or an unwipeable store's files deleted: custody may go now. */
        PURGE_NOW,

        /** The first chunk committed and the worker runs the rest: custody waits for that job's outcome. */
        HANDED_OFF,

        /** Custody must be kept: refused or failed (logged). */
        KEEP,

        /** Custody must be kept: an unwipeable store's files could not be deleted, it is open in another process (D14). */
        KEEP_BUSY,
    }

    /**
     * [wipeBeforeCustodyPurge] with the three answers told apart, for a caller that waits for the
     * handed-off job (Control's forget, Appendix B.14 (191)). [onFinished] runs once, on the
     * worker's queue, at **this** job's `REPLACEMENT_FINISHED`; [onNotFinished] at any other
     * `REPLACEMENT` outcome of the same job, and the purge stays owed (a later run of the same job
     * that finishes still releases it, as for [wipeBeforeCustodyPurge]).
     */
    fun startCustodyWipe(
        build: ReplicaBuild,
        reason: String = "custody purge",
        onFinished: () -> Unit,
        onNotFinished: (SharedReplicaJobOutcome) -> Unit = {},
        /**
         * ⚠ B.16 (217): true for Control's forget, whose graph is rebuilt once the forget says so
         * ([SharedReplicaHost.hold], then `resume`); false for an app's deprovision, whose graph is
         * gone for good ([SharedReplicaHost.forgetGraph]).
         */
        rebuildAfter: Boolean,
    ): CustodyWipe {
        return try {
            installAtLaunch()
            val installed = locked { adapter } ?: return CustodyWipe.KEEP
            // BEFORE the release below fences the graph's store: the host must not take that fence
            // for one to rebuild from.
            if (rebuildAfter) host.hold("a wipe of the shared store ($reason)") else host.forgetGraph("the custody purge ($reason)")
            // ⚠⚠ (187): the store this process cached is released BEFORE the wipe starts. The wipe
            // moves `meta.epoch`, so a cached pre-wipe handle handed to a later assembly (a
            // re-provision in the same process) would throw `ReplicaReplaced` at its first access
            // (181). Released, the next `open` opens anew: while the
            // handed-off wipe runs it is answered `Updating`, which waits or falls back.
            releaseOpened()
            // ⚠⚠ Bound to THIS job by its id (183), and owed BEFORE the dispatch (`beforeHandOff`):
            // a worker that finishes the wipe before startWipe returns still finds it, and another
            // job of the store finishing meanwhile (an earlier migration or wipe) never releases it.
            // Taken back on any answer but a hand-off.
            setPurgeOnFinish(null)
            val started = try {
                seams.startWipe(directory, build, appName, reason, installed::protectedDataAvailable) { jobId ->
                    setPurgeOnFinish(PendingPurge(jobId, onFinished, onNotFinished))
                }
            } catch (t: Throwable) {
                setPurgeOnFinish(null)
                throw t
            }
            when (started) {
                SharedReplicaWipeStart.NOTHING_TO_WIPE, SharedReplicaWipeStart.FINISHED -> {
                    setPurgeOnFinish(null)
                    CustodyWipe.PURGE_NOW
                }
                SharedReplicaWipeStart.HANDED_OFF -> {
                    println("[replica-binding] the wipe before the custody purge was handed to the worker; custody kept until it finishes")
                    CustodyWipe.HANDED_OFF
                }
            }
        } catch (e: WipeRefused) {
            // Judged outside the catch's own type test (K/N): the outcome is a plain value here.
            val unwipeable = isUnwipeable(e.outcome)
            if (!unwipeable) {
                println("[replica-binding] the wipe before the custody purge was refused: ${e.outcome}; custody kept")
                CustodyWipe.KEEP
            } else if (deleteThenPurge(e.outcome)) {
                CustodyWipe.PURGE_NOW
            } else {
                CustodyWipe.KEEP_BUSY
            }
        } catch (t: Throwable) {
            println("[replica-binding] the wipe before the custody purge failed: ${t::class.simpleName}: ${t.message}; custody kept")
            CustodyWipe.KEEP
        }
    }

    private fun setPurgeOnFinish(purge: PendingPurge?) {
        outcomes.lock()
        try {
            purgeOnFinish = purge
        } finally {
            outcomes.unlock()
        }
    }

    private fun isUnwipeable(outcome: ReplicaRunOutcome): Boolean = when (outcome) {
        is ReplicaRunOutcome.Skew, is ReplicaRunOutcome.Corrupt -> true
        else -> false
    }

    /** (179): this process's runtime handle released, then the files deleted if nobody has them open. */
    private fun deleteThenPurge(outcome: ReplicaRunOutcome): Boolean {
        releaseOpened()
        return when (seams.deleteFiles(directory)) {
            SharedReplicaDeletion.DELETED -> {
                println("[replica-binding] the store could never be wiped ($outcome): its files were deleted; custody is purged")
                true
            }
            SharedReplicaDeletion.BUSY -> {
                println("[replica-binding] the store could never be wiped ($outcome) and is open elsewhere: not deleted; custody kept")
                false
            }
        }
    }

    /**
     * §6 step 2b for the host's rebuild: this process's runtime handle released **if it is fenced**.
     * False when the release failed (the registry latched the process unusable): no rebuild may
     * follow. ⚠ B.17 (258): a handle that serves is the one the host's off-main [preOpen] just
     * opened for the reinstall, and is kept (the only way one is cached at a rebuild: every other
     * path that ends a graph releases its handle itself).
     */
    private fun releaseForRebuild(): Boolean = try {
        releaseOpened(onlyFenced = true)
        true
    } catch (t: Throwable) {
        println("[replica-binding] the fenced handle could not be released: ${t::class.simpleName}: ${t.message}")
        false
    }

    /** Releases this process's runtime handle, if it holds one ([onlyFenced]: one fenced): a later [open] opens anew. */
    private fun releaseOpened(onlyFenced: Boolean = false) {
        opening.lock()
        try {
            val current = opened ?: return
            if (onlyFenced && current.fencedBy == null) return
            opened = null
            if (current.fencedBy != ReplicaFence.RELEASED) current.close()
        } finally {
            opening.unlock()
        }
    }

    /**
     * ⚠⚠ Whether this process may go on serving the SQLite store, asked at every foreground (B.14
     * (196)): the route-1 runtime and Control set it to their reading of the replica choice. Null
     * (the default, and every test that does not set it): never asked.
     */
    @kotlin.concurrent.Volatile
    var stillChosen: (() -> Boolean)? = null

    /** Told once when [recheckChoice] fenced this process's store (Control declares `files`). */
    @kotlin.concurrent.Volatile
    var onUnchosen: (() -> Unit)? = null

    /**
     * ⚠⚠ The foreground's re-read of the replica choice (B.14 (196)): a process that bound the SQLite
     * store at launch and finds the choice no longer allows it **fences** its store (released: reads
     * answer absent, writes refused) and retires its loops, with a loud line, rather than run a
     * second replica until its next launch. That is the state a sibling's wipe already leaves a
     * graph in (§6); the next launch binds again. Nothing when no store is open.
     */
    internal fun recheckChoice() {
        val check = stillChosen ?: return
        // ⚠ B.17 (258): read without [opening]: a background rebuild's off-main open may hold it
        // across its bounded wait, and this runs on main inside `willEnterForeground`.
        val open = opened ?: return
        if (open.fencedBy != null) return
        val allowed = try {
            check()
        } catch (t: Throwable) {
            println("[replica-binding] the replica choice could not be read (${t.message}): treated as not chosen")
            false
        }
        if (allowed) return
        println("[replica-binding] ⚠⚠ the replica choice no longer allows the SQLite store: THIS PROCESS'S STORE IS FENCED and its loops stopped; relaunch (B.14 (196))")
        // Gone for good, never rebuilt (B.16 (217)): the next launch binds again.
        host.forgetGraph("the replica choice no longer allows the SQLite store")
        releaseOpened()
        retireLoops()
        try {
            onUnchosen?.invoke()
        } catch (t: Throwable) {
            println("[replica-binding] telling the fence threw: ${t.message}")
        }
    }

    /**
     * The adapter's foreground hook, on the main thread: the choice re-read first (B.14 (196)),
     * then the host's one rebuild of this foreground (B.16 (213)). A throw of either is logged.
     */
    private fun foreground() {
        recheckChoice()
        try {
            host.onForeground()
        } catch (t: Throwable) {
            println("[replica-binding] the host's foreground threw: ${t.message}")
        }
        // B.18.9 (324): whether Control can be opened from here, asked again on main (it may have been installed).
        StorageStateSurface.refreshReachabilityOnMain()
        // B.18: a corrupt store's copy is retried (Control) or looked at again (any app) at each foreground.
        try {
            damagedCopies.onForeground(host.status.value.state)
        } catch (t: Throwable) {
            println("[replica-binding] the damaged copy's foreground threw: ${t.message}")
        }
    }

    /** Stops the runtime's loops for good in this process: no later foreground starts them (B.14 (196)). */
    fun retireLoops() {
        loops.retire()
    }

    private inline fun <T> NSRecursiveLock.withLockedValue(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    /** B.17 (259), Q42: the adapter's protected-data notification, on the main thread. A throw is logged. */
    private fun protectedDataAvailable() {
        try {
            host.onProtectedDataAvailable()
        } catch (t: Throwable) {
            println("[replica-binding] the host's protected-data retry threw: ${t.message}")
        }
    }

    /** A wake (a background refresh): the worker's trigger. Before [installAtLaunch], nothing. */
    fun wake() {
        locked { adapter }?.wake()
    }

    /**
     * ⚠⚠ An entry's explicit token (Appendix B.15 (199)), or null when the platform grants no time.
     * Throws [IllegalStateException] before [installAtLaunch]: [SharedReplicaEntries] asks only an
     * installed binding.
     */
    internal fun protectEntry(reason: String, onExpired: () -> Unit): SharedReplicaEntryToken? {
        val installed = locked { adapter } ?: error("[replica-binding] an entry's token before installAtLaunch")
        return installed.lifecycle.protectEntry(reason, onExpired)
    }

    /** ⚠⚠ A task's token (Appendix B.23): the assertion is the running `BGTask`. Same precondition as [protectEntry]. */
    internal fun protectTask(reason: String, onExpired: () -> Unit): org.tenshu.core.common.proxy.db.sqlite.SharedReplicaTaskToken? {
        val installed = locked { adapter } ?: error("[replica-binding] a task's token before installAtLaunch")
        return installed.lifecycle.protectTask(reason, onExpired)
    }

    /** The runtime's loops (the sync scheduler and the schedule drain), once the graph exists. */
    fun bindLoops(components: () -> SyncProxyComponents?) {
        loops.bind(SyncRuntimeLoops.of(components))
    }

    /** Test seam: the last outcome told for this store. */
    internal fun lastOutcome(): SharedReplicaJobOutcome? {
        outcomes.lock()
        try {
            return lastOutcome
        } finally {
            outcomes.unlock()
        }
    }

    private fun openOnce(installed: SharedReplicaUiKitAdapter, build: ReplicaBuild, steps: ReplicaMigrationSteps): ReplicaOpenResult {
        // ⚠⚠ Fix round 1 (B.21.6 (410)): at every open, locked or not; only the first unlock gates the class.
        protectContentDirectories(installed::protectedDataAvailable)
        return seams.open(directory, build, steps, appName, installed.lifecycle.isForeground, installed::protectedDataAvailable)
    }

    /**
     * ⚠ D3b (§2.4, S0b-4 slice 2): `files/` (the content cache), `upload-staging/` (Drive's
     * background uploads) and, since B.22, `sync-hlc/` (the HLC watermark, B.21.6 (414)), beside
     * `replica/` in the same container, get the replica's class and its backup exclusion, read
     * back, at every open. Best effort: none of them is the store, so a failure is logged and
     * retried at the next open, never a refusal of the store.
     */
    private fun protectContentDirectories(protectedDataAvailable: () -> Boolean) {
        val container = directory.substringBeforeLast('/')
        contentDirectories.forEach { name ->
            try {
                containerAtRest().protect("$container/$name", protectedDataAvailable = protectedDataAvailable)
            } catch (t: Throwable) {
                println("[replica-binding] ⚠ D3b: $name is not protected at rest yet (${t.message}); tried again at the next open")
            }
        }
    }

    /** D3b's setter. A test seam. */
    internal var containerAtRest: () -> org.tenshu.core.common.proxy.db.ContainerAtRest = { org.tenshu.core.common.proxy.db.ContainerAtRest() }

    // ── the worker's outcomes, told on its queue ──

    /** Every outcome is this binding's store's: its lifecycle has no other registry attached. */
    private fun recordOutcome(outcome: SharedReplicaJobOutcome) {
        var purge: (() -> Unit)? = null
        var notFinished: ((SharedReplicaJobOutcome) -> Unit)? = null
        outcomes.lock()
        try {
            lastOutcome = outcome
            outcomeCount++
            val pending = purgeOnFinish
            if (pending != null && outcome.jobId == pending.jobId) {
                if (outcome.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED) {
                    purge = pending.purge
                    purgeOnFinish = null
                } else if (outcome.kind in REPLACEMENT_KINDS) {
                    // ⚠ Told, never released (B.14 (191)): the same job finishing later still purges.
                    notFinished = pending.notFinished
                }
            }
            outcomes.broadcast()
        } finally {
            outcomes.unlock()
        }
        // On the worker's queue, outside the lock; a throw would end the dispatch.
        try {
            purge?.invoke()
            notFinished?.invoke(outcome)
        } catch (t: Throwable) {
            println("[replica-binding] the custody purge after the wipe threw: ${t.message}")
        }
        if (outcome.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED) {
            try {
                host.onReplacementFinished()
            } catch (t: Throwable) {
                println("[replica-binding] telling the host the job finished threw: ${t.message}")
            }
        }
        // ⚠⚠ B.17 (292): a run that ended for good is the host's to show, now.
        if (outcome.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_SKEW || outcome.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_CORRUPT) {
            try {
                host.onReplacementEndedForGood(corrupt = outcome.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_CORRUPT)
            } catch (t: Throwable) {
                println("[replica-binding] telling the host the job ended for good threw: ${t.message}")
            }
        }
    }

    internal fun outcomesSoFar(): Long {
        outcomes.lock()
        try {
            return outcomeCount
        } finally {
            outcomes.unlock()
        }
    }

    /** The first `REPLACEMENT` outcome told after [after] outcomes, or null at the deadline. */
    internal fun awaitReplacementOutcome(after: Long): SharedReplicaJobOutcome? {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(updatingWaitMs / 1000.0)
        outcomes.lock()
        try {
            var seen = after
            while (true) {
                if (outcomeCount > seen) {
                    seen = outcomeCount
                    val last = lastOutcome
                    if (last != null && last.kind in REPLACEMENT_KINDS) return last
                }
                if (!outcomes.waitUntilDate(deadline)) return null
            }
        } finally {
            outcomes.unlock()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** `<group container>/upload-staging`, Drive's sealed chunks (`DriveLocalRuntime`): one of D3b's directories. */
        private const val UPLOAD_STAGING: String = "upload-staging"

        /**
         * Long enough for a small store's chunked wipe (§5.1: about 40 ms per 1,000 rows). ⚠ Only a
         * background rebuild's off-main open waits it (B.17 (258)); nothing on the main thread does.
         */
        const val UPDATING_WAIT_MS: Long = 10_000L

        /** The outcomes of a `REPLACEMENT` run: what an open answered `Updating` waits for (a walk's are not). */
        private val REPLACEMENT_KINDS = setOf(
            SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED,
            SharedReplicaJobOutcome.Kind.REPLACEMENT_STOPPED,
            SharedReplicaJobOutcome.Kind.REPLACEMENT_NEEDS_FOREGROUND,
            SharedReplicaJobOutcome.Kind.REPLACEMENT_SKEW,
            SharedReplicaJobOutcome.Kind.REPLACEMENT_CORRUPT,
        )

        private val processLock = NSRecursiveLock()
        private var processBinding: SharedReplicaBinding? = null

        /**
         * The process's one binding over the App Group's `replica/`, made on first use. Its host is
         * what [StorageStateSurface] shows from then on (B.17 (257)).
         */
        fun process(appName: String): SharedReplicaBinding {
            val (binding, made) = run {
                processLock.lock()
                try {
                    processBinding?.let { return@run it to false }
                    SharedReplicaBinding(
                        directory = org.tenshu.core.common.proxy.db.sharedReplicaDirectory(SharedReplicaLocation.SUBDIRECTORY),
                        appName = appName,
                    ).also { processBinding = it } to true
                } finally {
                    processLock.unlock()
                }
            }
            if (made) {
                StorageStateSurface.attach(binding.host, binding.damagedCopies)
                binding.damagedCopies.follow(binding.host.status, binding::isForeground)
            }
            return binding
        }

        /** The binding already made in this process, if any (a wake before any assembly finds none). */
        fun processOrNull(): SharedReplicaBinding? {
            processLock.lock()
            try {
                return processBinding
            } finally {
                processLock.unlock()
            }
        }
    }
}

/**
 * ⚠⚠ **The bindings that put the SQLite store in a graph** (Appendix B.13), last-wins over
 * `buildPlatformProxyModule()`'s. One definition for the route-1
 * apps' `FirstPartyLocalRuntime` and for Control (B.14 (189)).
 *
 * - `ObjectStore`: the SQLite store over the graph's own schema registry (its declared versions),
 *   whatever the graph's relay; and ⚠⚠ **never a crash for an open that cannot hand out a store**
 *   (B.17 (254), Q40): the not-ready stand-in, whose state the host shows. There is no other store
 *   to fall back to. [onBound] is told the store actually bound, once, when it is resolved.
 * - `DatabaseLock`: the store's own entry when it is SQLite (every outermost access is one SQLite
 *   transaction, §2.5, and `Database.transaction { }` must be that one), or the stand-in's. It
 *   follows the store actually bound.
 */
internal fun sharedReplicaStoreModule(
    sqlite: SharedReplicaBinding,
    onBound: (ObjectStore) -> Unit = {},
    /**
     * ⚠⚠ Fix round 1, B2: a status to bind the not-ready stand-in with, **opening nothing** (an app
     * that read Control's `reset`). Rebuilt like every retryable stand-in.
     */
    standIn: SharedReplicaHostStatus? = null,
): Module = module {
    single<ObjectStore> {
        if (standIn != null) {
            val finishedBefore = sqlite.host.finishedJobsNow()
            return@single NotReadyReplicaStore(standIn, onAccess = sqlite.host::onStandInAccess)
                .also { sqlite.host.recordOpen(it, finishedBefore); onBound(it) }
        }
        val migrations = StoreSchemaMigrations(
            context = get(),
            schemas = get(),
            deviceId = getOrNull<SecureStorageDelegate>()?.getString(SyncManagerImpl.Keys.DEVICE_ID) ?: "",
        )
        val bound = sqliteStoreOrStandIn(sqlite, migrations)
        onBound(bound)
        bound
    }
    single<DatabaseLock> {
        when (val store = get<ObjectStore>()) {
            is SharedReplicaStore -> store.databaseLock
            is NotReadyReplicaStore -> store.databaseLock
            else -> error("the shared replica's module bound neither the SQLite store nor its stand-in")
        }
    }
}

/** True when [store] is the SQLite store. A function of its own: the test is never made in place on a try's value (K/N). */
internal fun isSharedReplicaStore(store: ObjectStore): Boolean = store is SharedReplicaStore

/**
 * The SQLite store, or the not-ready stand-in when it cannot be handed out. ⚠⚠ Whatever the graph's
 * relay is: the dev-lab guard (B.13 (180)) is gone (B.22).
 */
private fun sqliteStoreOrStandIn(
    sqlite: SharedReplicaBinding,
    migrations: StoreSchemaMigrations,
): ObjectStore {
    // B.17 (274): the finishes told before this open began, so one in between is not lost.
    val finishedBefore = sqlite.host.finishedJobsNow()
    return try {
        // ⚠⚠ B.24: the build and its chains from ONE registry, inside the try: a chain that fails
        // its verification refuses the open (the stand-in, not retried), as the JVM refuses the start.
        val chains = RegistryReplicaMigrationSteps(migrations)
        sqlite.open(replicaBuildOf(migrations, chains), chains).also { sqlite.host.recordOpen(it, finishedBefore) }
    } catch (e: Exception) {
        val status = hostStatusOfOpenFailure(e)
        // ⚠⚠ Q35 (decided 2026-10-24), B.16 (214), and Q40 (decided 2026-10-28), B.17 (254): the
        // stand-in for EVERY refusal, retryable or not, and the phone's choice stays as it is (190).
        if (status.willRetry) {
            println("[replica-binding] the SQLite store is not ready (${e::class.simpleName}: ${e.message}): the graph waits on the not-ready stand-in, retried at the next protected entry or foreground")
        } else {
            println("[replica-binding] ⚠⚠ the SQLite store cannot be handed out (${e::class.simpleName}: ${e.message}): the graph waits on the stand-in, ${status.state}; nothing is retried by itself")
        }
        NotReadyReplicaStore(status, onAccess = sqlite.host::onStandInAccess).also { sqlite.host.recordOpen(it, finishedBefore) }
    }
}

/**
 * ⚠⚠ **Which refusals of the open are "not ready, try again"** (Q35, Appendix B.16 (214), (215),
 * confirmed by Q44 on 2026-10-28, B.17 (262)): every [StoreUnavailable] (protected data, the gate,
 * an unverified `-wal` reset, a corruption code being checked, a store that kept changing), a
 * [HoldCeilingExceeded] with its [ProtectionExpired], a [StoreNeedsForeground], and an `Updating`
 * the open did not wait for or whose wait did not see it finish. Everything else is not retryable:
 * the stand-in all the same (B.17 (254)), with its state and nothing retried by itself. A
 * [ProtectedDataUnavailable] is marked so the protected-data notification retries it once (Q42).
 * Type tests here, in a function of their own, never on a try's value (K/N).
 */
internal fun hostStatusOfOpenFailure(e: Throwable): SharedReplicaHostStatus {
    val detail = "${e::class.simpleName}: ${e.message}"
    return when (e) {
        is SharedReplicaNotReady -> SharedReplicaHostStatus(e.state, detail, willRetry = e.retryable, wipe = e.wipe)
        is ProtectedDataUnavailable -> SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, detail, willRetry = true, protectedDataUnavailable = true)
        is StoreBeingChecked -> SharedReplicaHostStatus(SharedReplicaHostState.CHECKING, detail, willRetry = true)
        is StoreUnavailable -> SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, detail, willRetry = true)
        is HoldCeilingExceeded -> SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, detail, willRetry = true)
        is StoreNeedsForeground -> SharedReplicaHostStatus(SharedReplicaHostState.UPDATING, detail, willRetry = true)
        else -> SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, detail, willRetry = false)
    }
}

/**
 * ⚠⚠ **What a wipe job is given for chains** (B.24): a wipe never migrates. Its first chunk deletes
 * any `meta.migration` and writes `meta.wipe`; every later chunk of the job reads `meta.wipe`, and
 * no open starts a migration on a store marked for a wipe (`classify`: `Updating(WIPE)`). So this
 * is never asked, and if it were, it **throws**: the chunk rolls back and the run stops. Null here
 * would read as "no step: wipe", which is the one answer a set of chains must never give by default.
 */
internal val WipeJobsNeverMigrate: ReplicaMigrationSteps = ReplicaMigrationSteps { service, from ->
    error("[replica-binding] a wipe job was asked for the migration step of '$service' v$from: a wipe never migrates")
}

/**
 * This build's versions as the open judges a store by (§5.2, §7): `:core`'s format version and
 * every declared service's, from the graph's own schema registry. ⚠⚠ Each floor is the oldest
 * version the registered chain migrates from (B.24, [RegistryReplicaMigrationSteps.replicaBuild]),
 * taken from [chains], the very steps the open is given.
 */
internal fun replicaBuildOf(
    migrations: StoreSchemaMigrations,
    chains: RegistryReplicaMigrationSteps = RegistryReplicaMigrationSteps(migrations),
): ReplicaBuild = chains.replicaBuild()

/**
 * [replicaBuildOf] with no runtime graph (the custody purge at a launch that assembled none): a
 * throwaway graph of the same two production modules, of which only the schema registry and its
 * context are resolved. ⚠ The same modules every route-1 graph is built from, so the same vector
 * (`DeclaredServiceVersionsTest` pins that every app declares it).
 */
internal fun replicaBuildOfThisBuild(): ReplicaBuild {
    val app = koinApplication { modules(buildProxyModule(), buildPlatformProxyModule()) }
    try {
        return replicaBuildOf(StoreSchemaMigrations(context = app.koin.get(), schemas = app.koin.get(), deviceId = ""))
    } finally {
        app.close()
    }
}

/**
 * The loops rule P drives, empty until the graph exists. Handed to `install` at launch, before any
 * loop does: a foreground launch's `start()` then finds nothing, and the runtime's own start (its
 * election) starts them as today. ⚠ Every call is made under the protection's lock: none blocks.
 */
internal class DeferredReplicaLoops : SharedReplicaLoops {
    private val lock = NSRecursiveLock()
    private var bound: SharedReplicaLoops? = null

    /**
     * The runtime's loops from now on. ⚠ A later assembly in the same process (a re-provision)
     * replaces them: the newer graph's runtime is the one that runs.
     */
    /**
     * ⚠ B.16 (216): the bound loops stopped and forgotten, **not** retired: the graph they ran for
     * is being replaced, and the rebuilt graph's [bind] binds its own.
     */
    fun unbind() {
        val was = current()
        lock.lock()
        try {
            bound = null
        } finally {
            lock.unlock()
        }
        was?.stop()
    }

    /** The loops bound now (a graph's at its install, B.16 (251)). */
    fun currentBinding(): SharedReplicaLoops? = current()

    /**
     * ⚠⚠ B.16 (251): [unbind], but only if [expected] is still the bound loops: a stopped graph's
     * late teardown never stops or forgets a successor's. Null: nothing was bound for it.
     */
    fun unbindIf(expected: Any?) {
        if (expected == null) return
        val was = run {
            lock.lock()
            try {
                if (bound !== expected) return
                bound.also { bound = null }
            } finally {
                lock.unlock()
            }
        }
        was?.stop()
    }

    fun bind(loops: SharedReplicaLoops) {
        lock.lock()
        try {
            if (retired) return
            bound = loops
        } finally {
            lock.unlock()
        }
    }

    private var retired = false

    /** Stops the bound loops and forgets them, for good: a later start or bind does nothing. */
    fun retire() {
        val was = current()
        lock.lock()
        try {
            retired = true
            bound = null
        } finally {
            lock.unlock()
        }
        was?.stop()
    }

    private fun current(): SharedReplicaLoops? {
        lock.lock()
        try {
            return bound
        } finally {
            lock.unlock()
        }
    }

    override fun start() {
        current()?.start()
    }

    override fun stop() {
        current()?.stop()
    }

    override fun awaitStopped(timeoutMs: Long) {
        current()?.awaitStopped(timeoutMs)
    }
}

/**
 * One loop rule P stops and starts: a `start()`, a `stop()` that only cancels, and ⚠ (B.15 (205))
 * the loop's own `stopAndAwait()`, which [SyncRuntimeLoops] runs instead of [stop] when it is
 * given, so that [SyncRuntimeLoops.awaitStopped] can wait for the tick it cancelled.
 */
internal class RuntimeLoop(
    val name: String,
    val start: () -> Unit,
    val stop: () -> Unit,
    val stopAndAwait: (suspend () -> Unit)? = null,
)

/**
 * The sync runtime's loops, the scheduler and the schedule drain (Appendix B.13 (169)).
 *
 * - **stop**: each loop's `stop()` (a cancel, never a wait), and remembered.
 * - **start**: only after a stop of this object's: a runtime that was never started (or one
 *   published since) is left to its own election, so a foreground cannot start a loop nobody did.
 * - ⚠⚠ **awaitStopped waits** (B.13 (169), built in B.15 (205)): each loop's `stopAndAwait()` is
 *   started at [stop], undispatched, so its cancel happens inside [stop] (synchronously, under the
 *   protection's lock, never blocking: it runs to its first suspension, the join); [awaitStopped]
 *   then waits, at most its timeout, for those joins. So step 3's second really is the cancelled
 *   ticks finishing, with the gate still open, rather than the drain's (10 s, or 2 s at an expiry).
 *   A loop with no `stopAndAwait` is cancelled as before and not waited for.
 *
 * A throw from a loop or from [resolve] is logged and never leaves: the protection calls these
 * under its lock. [resolve] answers null while no runtime is published.
 */
internal class SyncRuntimeLoops(
    /** Where a loop's `stopAndAwait` runs once it suspends. A test seam. */
    private val joinScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val resolve: () -> List<RuntimeLoop>?,
) : SharedReplicaLoops {
    private val lock = NSRecursiveLock()
    private var stoppedByRuleP = false

    /** The joins [stop] started, which [awaitStopped] waits for. */
    private val stopping = ArrayList<Job>()

    override fun start() {
        val restart = locked {
            val was = stoppedByRuleP
            stoppedByRuleP = false
            was
        }
        if (!restart) return
        loops()?.forEach { loop -> guarded("starting the ${loop.name}") { loop.start() } }
    }

    override fun stop() {
        val loops = loops() ?: return
        loops.forEach { loop ->
            guarded("stopping the ${loop.name}") {
                val andAwait = loop.stopAndAwait
                if (andAwait == null) {
                    loop.stop()
                } else {
                    // UNDISPATCHED: the loop's cancel runs here, before stop() returns; only its
                    // join continues on the scope.
                    val join = joinScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            andAwait()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            println("[replica-binding] stopping the ${loop.name} threw: ${t.message}")
                        }
                    }
                    locked {
                        // A stop with no awaitStopped after it (an expiry's shutdown) leaves joins: pruned here.
                        stopping.removeAll { it.isCompleted }
                        stopping += join
                    }
                }
            }
        }
        locked { stoppedByRuleP = true }
    }

    /**
     * ⚠ Called off the main thread with no lock held, inside the shutdown's step 3 (B.5): waits at
     * most [timeoutMs] in all for the ticks [stop] cancelled to finish. Never throws.
     */
    override fun awaitStopped(timeoutMs: Long) {
        val joins = locked { stopping.toList().also { stopping.clear() } }
        if (joins.isEmpty()) return
        try {
            val done = runBlocking { withTimeoutOrNull(timeoutMs) { joins.joinAll() } }
            if (done == null) println("[replica-binding] the cancelled loops had not stopped after $timeoutMs ms; the drain waits for their store access")
        } catch (t: Throwable) {
            println("[replica-binding] waiting for the loops threw: ${t.message}")
        }
    }

    private fun loops(): List<RuntimeLoop>? = try {
        resolve()
    } catch (t: Throwable) {
        println("[replica-binding] resolving the sync runtime threw: ${t.message}")
        null
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            println("[replica-binding] $what threw: ${t.message}")
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** The published runtime's scheduler and schedule drain, or null while none is published. */
        fun of(components: () -> SyncProxyComponents?): SyncRuntimeLoops = SyncRuntimeLoops {
            components()?.let { runtime ->
                listOf(
                    RuntimeLoop("scheduler", runtime.scheduler::start, runtime.scheduler::stop, runtime.scheduler::stopAndAwait),
                    RuntimeLoop("schedule drain", runtime.scheduleDrainLoop::start, runtime.scheduleDrainLoop::stop, runtime.scheduleDrainLoop::stopAndAwait),
                )
            }
        }
    }
}
