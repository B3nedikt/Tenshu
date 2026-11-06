@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class, org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.core.module.Module
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.sqlite.DamagedSnapshotOutcome
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDamagedCopies
import org.tenshu.sync.proxy.SyncRuntimeHolder
import kotlin.coroutines.CoroutineContext
import kotlin.native.HiddenFromObjC

/**
 * ⚠⚠ **Control's use of the SQLite shared replica** (`ios-shared-store-sqlite.md` Appendix B.14):
 * the same [SharedReplicaBinding] as the nine route-1 apps (B.13), plus the two things only Control
 * does: it **declares** that the phone's store is ready ([SharedReplicaChoice], (190)), and its forget
 * **wipes the store for everyone** before it purges custody (§13's rule, (191)).
 *
 * ⚠⚠ **The only store** (B.22): no plist switch and no relay guard, and no old file store, no
 * cutover and no launch reset (B.22.9).
 *
 * Kotlin-only (hidden from Swift): `ControlSurface` drives it.
 */
@HiddenFromObjC
class ControlSharedReplica internal constructor(
    private val binding: () -> SharedReplicaBinding,
    private val choice: () -> SharedReplicaChoice,
    private val thisBuild: () -> ReplicaBuild,
    /** Where the wipe's start runs: the main thread in production (the binding installs there). */
    private val mainContext: CoroutineContext,
    /** How long the forget waits for the worker's wipe before it reports "not finished". */
    private val forgetWaitMs: Long,
    private val log: (String) -> Unit = ::println,
    /** Control's snapshot of a corrupt store, one attempt (Appendix B.18): the production entry. A test seam. */
    private val takeSnapshot: (directory: String, shouldStop: () -> Boolean) -> DamagedSnapshotOutcome =
        { directory, shouldStop -> SharedReplicaDamagedCopies.take(directory, shouldStop) },
    /**
     * B.18.8 (316): the damaged copies' leftovers removed (stopped `.partial`s; every copy once the
     * marker is gone), off the main thread, at launch and after the forget. A test seam.
     */
    private val cleanUpCopies: (directory: String) -> Unit = { directory ->
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { SharedReplicaDamagedCopies.cleanUp(directory) }
    },
) {

    private var boundSqlite = false

    /**
     * The module that puts the SQLite store, or its not-ready stand-in, in Control's graph: never
     * null, and never anything else. Called on the main thread before the graph is built
     * (`ControlSurface.installRealRuntime`).
     *
     * The binding is installed, then the store bound **whatever the choice reads**: Control is the
     * declarer, so it declares `sqlite` once its store resolves, and the apps wait on their stand-in
     * until then (B.14 (190)).
     * - ⚠⚠ A binding that cannot be installed gets the stand-in (not
     *   retried by itself: relaunch), and declares nothing.
     */
    fun runtimeModule(): Module {
        // Again at a rebuild (B.16): what this assembly binds is decided anew.
        boundSqlite = false
        val made = binding()
        try {
            made.installAtLaunch()
        } catch (t: Throwable) {
            // ⚠⚠ Nothing declared, so no app follows it.
            log("[replica-control] ⚠⚠ the shared store's binding could not be installed (${t.message}): Control waits on the not-ready stand-in; relaunch it")
            return sharedReplicaStoreModule(
                made,
                standIn = SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "the shared store's binding could not be installed", willRetry = false),
            )
        }
        boundSqlite = true
        // ⚠⚠ B.18 (Q47): Control alone takes the corrupt store's snapshot (§6 step 4), so only its
        // binding has the exporter. Once per process: a rebuild's assembly finds it set.
        if (made.damagedCopies.exporter == null) {
            cleanUpCopies(made.directory)
            made.damagedCopies.exporter = DamagedSnapshotRunner(
                made.damagedCopies,
                take = { shouldStop -> takeSnapshot(made.directory, shouldStop) },
                protect = { onExpired -> made.protectEntry("snapshot", onExpired) },
            )
        }
        // ⚠⚠ Control is the declarer and never leaves the SQLite store: its foreground re-read
        // (B.14 (196)) never fences it.
        made.stillChosen = null
        made.onUnchosen = null
        return sharedReplicaStoreModule(made, onBound = { bound ->
            // ⚠⚠ Q35, B.16 (214): the not-ready stand-in declares NOTHING; only a store that took
            // the open is declared, and only then do the apps bind it.
            if (isSharedReplicaStore(bound)) choice().declare(SharedReplicaChoice.SQLITE)
        })
    }

    /**
     * ⚠⚠ B.16: Control's graph handed to the process's host (§6's owner on Control's route), with
     * how to rebuild it ([rebuild]: `ControlSurface`'s reinstall, on the main thread). Nothing when
     * the binding could not be installed.
     *
     * ⚠⚠ [stop] is `ControlSurface`'s (B.16 (218)): the old graph's
     * `SyncManager` locked (in memory only), its pairing session stopped and its OS-alert collector
     * cancelled, so nothing of it goes on talking to the relay with the old keys.
     */
    fun graphInstalled(koin: Koin, stop: () -> Unit, rebuild: () -> Unit) {
        if (!boundSqlite) return
        binding().host.graphInstalled(koin.get<ObjectStore>(), stop = stop, rebuild = rebuild)
    }

    /**
     * The host's state for Control's graph ([StorageStateSurface] reads it). Null when the binding
     * could not be installed.
     */
    val hostStatus: kotlinx.coroutines.flow.StateFlow<SharedReplicaHostStatus>?
        get() = if (boundSqlite) binding().host.status else null

    /** Rule P's loops: the runtime's scheduler and schedule drain, once Control's graph exists, and only over the SQLite store. */
    fun bindLoops(koin: Koin) {
        if (!boundSqlite) return
        binding().bindLoops { koin.getOrNull<SyncRuntimeHolder>()?.runtime?.components }
    }

    /** A wake (Control's background refresh): the worker's trigger. Nothing before the binding is installed. */
    fun wake() {
        binding().wake()
    }

    /**
     * ⚠⚠ [wipeForForget], then [purgeCustody] **only if custody may go** (D3, §13's rule): the one
     * place the forget's custody purge is gated.
     */
    suspend fun wipeThenPurgeCustody(purgeCustody: () -> Unit): ForgetWipe {
        val wiped = wipeForForget()
        if (wiped.custodyMayGo) purgeCustody()
        // ⚠⚠ B.16 (217), lifting B.14 (193): Control's graph is rebuilt in this process. At once
        // on a finished wipe (the onboarding that follows needs a store that takes writes), on the
        // main thread; after an unfinished one at the next foreground or entry, whose open waits
        // for the worker's wipe or answers "not ready".
        val rebuilt = try {
            // ⚠ B.17 (258): the release and the open off the main thread, then the reinstall on it.
            binding().host.resumeAndRebuild(now = wiped.custodyMayGo)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log("[replica-control] the rebuild after the forget threw (${t.message}): relaunch Control")
            false
        }
        return ForgetWipe(custodyMayGo = wiped.custodyMayGo, note = wiped.note, relaunchControl = !rebuilt)
    }

    /**
     * ⚠⚠ **The shared store's wipe in Control's forget** (§13's rule; Appendix B.14 (191)), between
     * the queue purge and the custody purge of `wipeDeviceState`.
     *
     * Whatever store Control's graph runs on (`replica/` may hold the realm being left):
     * - the start on the **main thread** (the binding installs there; the first chunk, the marker
     *   and the new epoch, is short), with this process's cached store **released first** (B.13
     *   (187)), so Control's own graph is fenced before the wipe and a later assembly opens anew;
     * - the remaining chunks **through the worker**, and the forget **waits for that job's own
     *   outcome**, suspended (never blocking main), at most [forgetWaitMs]: §5.1's contract that the
     *   wipe has happened before the forget reports it;
     * - ⚠⚠ **custody may go only when the wipe finished**, there was no store, or a store that can
     *   never be wiped (`Skew`, `Corrupt`) had its files deleted (D14). A stopped run, a refusal,
     *   a store open elsewhere for the deletion, or the deadline: **custody is kept** and the forget
     *   reports "not finished" (D3: never custody gone over a store still present). The marker
     *   stays, so any qualifying open finishes the wipe; the user repeats the forget.
     *
     * Siblings see the wipe through `meta.epoch` and `meta.wipe` at their next access (§6): their
     * handles fence, and their next open waits for the wipe or falls back (B.13 (181)).
     *
     * Every exit declares [SharedReplicaChoice.RESET]: the apps wait on their stand-in until
     * Control's next bound store declares `sqlite` again (190).
     */
    suspend fun wipeForForget(): ForgetWipe {
        try {
            val build = thisBuild()
            val done = CompletableDeferred<String>()
            val start = withContext(mainContext) {
                binding().startCustodyWipe(
                    build,
                    reason = "forget",
                    onFinished = { done.complete(FINISHED) },
                    onNotFinished = { outcome -> done.complete("${outcome.kind}: ${outcome.detail}") },
                    // B.16 (217): the host holds Control's graph (stopped, nothing rebuilt) until
                    // wipeThenPurgeCustody resumes it.
                    rebuildAfter = true,
                )
            }
            // ⚠ The store this process held was released at the start, so Control's old graph
            // refuses writes; its sync loops are stopped and unbound (B.16 (216)), so nothing of it
            // spins against the
            // released store. The rebuilt graph binds its own.
            binding().loops.unbind()
            return when (start) {
                SharedReplicaBinding.CustodyWipe.PURGE_NOW -> ForgetWipe(custodyMayGo = true, note = "shared store dropped", relaunchControl = true)
                SharedReplicaBinding.CustodyWipe.KEEP -> ForgetWipe(custodyMayGo = false, note = "the shared store was not wiped (refused or failed)", relaunchControl = true)
                SharedReplicaBinding.CustodyWipe.KEEP_BUSY -> ForgetWipe(custodyMayGo = false, note = "the shared store is open in another Tenshu app: close the other Tenshu apps and try again", relaunchControl = true)
                SharedReplicaBinding.CustodyWipe.HANDED_OFF -> {
                    val answer = withTimeoutOrNull(forgetWaitMs) { done.await() }
                    when (answer) {
                        FINISHED -> ForgetWipe(custodyMayGo = true, note = "shared store dropped", relaunchControl = true)
                        null -> ForgetWipe(custodyMayGo = false, note = "the shared store's wipe did not finish within ${forgetWaitMs / 1000} s", relaunchControl = true)
                        else -> ForgetWipe(custodyMayGo = false, note = "the shared store's wipe did not finish ($answer)", relaunchControl = true)
                    }
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log("[replica-control] the forget's wipe of the shared store failed (${t::class.simpleName}: ${t.message}); custody kept")
            return ForgetWipe(custodyMayGo = false, note = "the shared store's wipe failed", relaunchControl = true)
        } finally {
            choice().declare(SharedReplicaChoice.RESET)
            // B.18.8 (316): a forget that deleted a corrupt store's files (D14) leaves no marker, so
            // its copy can be offered nowhere: removed with it.
            try {
                cleanUpCopies(binding().directory)
            } catch (t: Throwable) {
                log("[replica-control] the damaged copies' clean-up threw: ${t.message}")
            }
        }
    }

    companion object {
        private const val FINISHED = "finished"

        /** The worker's wipe is about 40 ms per 1,000 rows (§5.1); a large store takes seconds. */
        const val FORGET_WAIT_MS: Long = 120_000L

        /** The production instance: the process's one binding, in the App Group container. */
        fun production(): ControlSharedReplica = ControlSharedReplica(
            binding = { SharedReplicaBinding.process(CONTROL_CLIENT_ID) },
            choice = { SharedReplicaChoice.production() },
            thisBuild = { replicaBuildOfThisBuild() },
            mainContext = Dispatchers.Main,
            forgetWaitMs = FORGET_WAIT_MS,
        )

        /** Control's `platformPackageName` (`buildPlatformProxyModule`): the app the store records as writing. */
        private const val CONTROL_CLIENT_ID = "app.tenshu.control.ios"
    }
}

/**
 * How [ControlSharedReplica.wipeForForget] left the shared store. [custodyMayGo] false: keep every
 * custody key and report "not finished" with [note].
 */
@HiddenFromObjC
class ForgetWipe(
    val custodyMayGo: Boolean,
    val note: String?,
    /**
     * ⚠ True when Control's own graph was **not** rebuilt after a forget, so the user is told to
     * relaunch Control before setting it up again (B.14 (193), B.15 (206)).
     * [ControlSharedReplica.wipeThenPurgeCustody] rebuilds the graph (B.16 (217)), so from it this is
     * true only when that rebuild did not leave a serving graph (an unfinished wipe, no graph to
     * rebuild, a rebuild that failed). [ControlSharedReplica.wipeForForget] alone sets it on every
     * forget.
     */
    val relaunchControl: Boolean,
)
