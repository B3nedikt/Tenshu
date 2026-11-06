@file:OptIn(org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import kotlin.coroutines.CoroutineContext

/**
 * What the app's root shows for the shared store, as one finished value (Q39, Appendix B.17
 * (257)). ⚠ No enum and no sealed type crosses to Swift: [state] is a name for logs and
 * accessibility identifiers, never for copy, and the copy is Kotlin's ([StorageStateSurface]).
 */
@ConsistentCopyVisibility
data class StorageStateSnapshot internal constructor(
    /** The host's state ("READY", "REFRESHING", …). */
    val state: String,
    /** ⚠⚠ True: the app's root is covered by this surface, and nothing under it is reachable. */
    val covers: Boolean,
    /** The cover's title ("Refreshing…", "Update Fit", "Storage damaged", …); null when nothing covers. */
    val title: String?,
    /** The cover's one paragraph; null when nothing covers. */
    val message: String?,
    /** B.17 (260), Q42: a "Try again" button; [StorageStateSurface.tryAgain] is its action. */
    val canTryAgain: Boolean,
    /**
     * ⚠⚠ B.17 (257), Q39: changes each time a rebuilt graph serves. The root is built with it as
     * its identity (`.id(generation)`), so every screen, and every view model and engine a screen
     * made, is built anew over the new graph. 0 for the process's first graph, and always 0 with
     * no host attached.
     */
    val generation: Long,
    /**
     * ⚠⚠ B.18 (Q47): "Export damaged data". True only in Control, only over `CORRUPT`, and only
     * while a **complete** copy is on disk (its `COMPLETE` written after every file was synced);
     * [damagedDataFiles] are then that copy's files, for the share sheet. Never otherwise.
     */
    val canExportDamagedData: Boolean = false,
    /** The complete copy's files (absolute paths), when [canExportDamagedData]; empty otherwise. */
    val damagedDataFiles: List<String> = emptyList(),
    /**
     * B.18: the cover names Control as the place to export from (every app but Control, over
     * `CORRUPT`). Swift offers "Open Control" only when this is set **and**
     * `RealmStorageSurface.canOpenControl()` says Control can be reached: no button that does nothing.
     */
    val offersOpenControl: Boolean = false,
    /**
     * ⭐ Q51: "Delete the copy", beside the export: Control only, over a complete
     * copy. Swift confirms first, then calls [StorageStateSurface.deleteDamagedData].
     */
    val canDeleteDamagedData: Boolean = false,
)

/** A [StorageStateSurface.observe] that runs until [cancel]. */
class StorageStateObservation internal constructor(private val job: Job) {
    /** Ends the observation; no callback is made after it returns on the main thread. */
    fun cancel() {
        job.cancel()
    }
}

/**
 * ⚠⚠ **The shared store's state for the app's root** (`ios-shared-store-sqlite.md` §6, Q39,
 * Appendix B.17 (257)): a snapshot and a callback, as [SyncActivitySurface] and
 * [RealmStorageSurface] hand Swift finished values (no `StateFlow` in Swift). Each app's `RootView`
 * observes it: a snapshot that [StorageStateSnapshot.covers] puts its surface over the root, and a
 * new [StorageStateSnapshot.generation] re-creates the root.
 *
 * ⚠⚠ **With no host attached nothing ever changes** (no binding is made in the process): the
 * snapshot is READY, covering nothing, generation 0, for the life of the process.
 *
 * ⚠ The copy is not localised, as nothing on this bridge is; the table below is the seam.
 */
object StorageStateSurface {
    /** B.17 (278): a waiting cover's delay before "Try again". */
    const val TRY_AGAIN_AFTER_MS: Long = 15_000L

    /** The detail of the host's "Try again" status, shown as the cover's title. */
    private const val TRYING_AGAIN = "Trying again…"

    /** The states the host leaves by itself, which a delay turns into a cover with "Try again". */
    private val WAITING = setOf(SharedReplicaHostState.REFRESHING, SharedReplicaHostState.UPDATING, SharedReplicaHostState.CHECKING)

    /** The process's host, once its binding is made (`SharedReplicaBinding.process`). */
    private val hosts = MutableStateFlow<SharedReplicaHost?>(null)

    /** B.18: the process's damaged-copy view, made with the binding; null without one. */
    private val copies = MutableStateFlow<DamagedDataCopies?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What a process with no host shows, for good. ⚠ Above every function that reads it (K/N). */
    private val READY = StorageStateSnapshot("READY", covers = false, title = null, message = null, canTryAgain = false, generation = 0)

    /**
     * ⚠ B.17 (278): how long a waiting cover ("Refreshing…", "Updating storage…", "Checking
     * storage…") stays up before it also offers "Try again", so that no cover is a dead end. A
     * test seam.
     */
    internal var tryAgainAfterMs: Long = TRY_AGAIN_AFTER_MS

    /** Where [observe]'s callback runs: the main thread in production. A test seam. */
    internal var callbackContext: CoroutineContext = Dispatchers.Main

    /**
     * B.18.8 (317): Control can be reached from here (`RealmStorageSurface.canOpenControl`), so the
     * damaged cover may say "open Tenshu Control" and offer the button. A test seam.
     */
    internal var controlReachable: () -> Boolean = { RealmStorageSurface.canOpenControl() }

    /**
     * ⚠ B.18.9 (324): [controlReachable] as last asked **on the main thread** (`UIApplication` is
     * main-thread-only), at the binding's making and at each foreground, so a Control installed
     * meanwhile is seen when the user comes back. Read by the cover, never asked by it.
     */
    private val reachable = MutableStateFlow(false)

    /** Asks [controlReachable] now, on the calling thread: the main thread, or a test. */
    internal fun refreshReachability() {
        reachable.value = try {
            controlReachable()
        } catch (t: Throwable) {
            false
        }
    }

    /** [refreshReachability] on the main thread, from wherever it is asked. */
    internal fun refreshReachabilityOnMain() {
        if (platform.Foundation.NSThread.isMainThread) refreshReachability() else scope.launch(Dispatchers.Main) { refreshReachability() }
    }

    /** This app's name, for the update surface (§7: the app to name on route 1 is the app itself). A test seam. */
    internal var appName: () -> String = {
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String)
            ?: (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleName") as? String)
            ?: "this app"
    }

    /** Made by `SharedReplicaBinding.process`, once per process; a test attaches its own. */
    internal fun attach(host: SharedReplicaHost, damaged: DamagedDataCopies? = null) {
        copies.value = damaged
        hosts.value = host
        if (damaged != null) refreshReachabilityOnMain()
    }

    /** Test seam: back to no host. */
    internal fun detachForTests() {
        hosts.value = null
        copies.value = null
    }

    /** The state now. Any thread. */
    fun snapshot(): StorageStateSnapshot {
        val host = hosts.value ?: return READY
        val damaged = copies.value
        return snapshotOf(host, host.status.value, copy = damaged?.view?.value ?: DamagedCopyView.UNKNOWN, exporter = damaged?.isExporter == true)
    }

    /**
     * B.18: "Open Control", from another app's "Storage damaged" cover. Swift offers it only when
     * the snapshot [StorageStateSnapshot.offersOpenControl] and `RealmStorageSurface.canOpenControl()`
     * is true. Control registers `tenshu-control` and lands on its root, where its own cover offers
     * the export (`control-ios/project.yml`).
     */
    fun openControl() {
        val url = platform.Foundation.NSURL.URLWithString(OPEN_CONTROL_URL) ?: return
        // ⚠⚠ The three-argument form: iOS 18 makes the deprecated one-argument `openURL(_:)` a
        // no-op that returns false, while `canOpenURL` still says yes.
        platform.UIKit.UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private const val OPEN_CONTROL_URL = "tenshu-control://storage-damaged"

    /**
     * ⭐ Q51: the confirmed "Delete the copy" (Control's cover). True when the delete was started; the
     * cover moves to "deleted" once it is done. False when there is nothing it may delete.
     */
    fun deleteDamagedData(): Boolean = copies.value?.delete() ?: false

    /**
     * ⭐ Q50: the sentence Control's forget confirmation adds when a complete
     * copy of damaged data is on this phone (shared or not: a share cannot be confirmed), since the
     * forget removes it. Null with no copy, and always without a binding.
     */
    fun damagedCopyForgetWarning(): String? {
        val directory = copies.value?.replicaDirectory ?: return null
        return if (anyCompleteCopy(directory)) FORGET_WARNING else null
    }

    /** A test seam over [org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDamagedCopies.anyComplete]. */
    internal var anyCompleteCopy: (String) -> Boolean = { org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDamagedCopies.anyComplete(it) }

    internal const val FORGET_WARNING = "A copy of damaged Tenshu data is kept on this phone, and forgetting this device deletes it. " +
        "It can't be exported from here: cancel if you may still need it."

    /** B.18.9 (325): D8's recovery, named on every one of Control's damaged covers. */
    private const val RECOVERY = "To start again, delete every Tenshu app and reinstall them."

    /**
     * [onChange] with the state now, then at each change, on the main thread, until the returned
     * observation is cancelled. ⚠ The Swift owner cancels it when its view goes away, so no
     * collector outlives the root (the precedent of `RealmStorageBanner`).
     */
    fun observe(onChange: (StorageStateSnapshot) -> Unit): StorageStateObservation {
        val job = scope.launch(callbackContext) {
            hosts
                .flatMapLatest { host ->
                    if (host == null) {
                        flowOf(READY)
                    } else {
                        // B.17 (276): an attached observer is what keeps the Q46 poll running.
                        // B.18: the damaged copy's view moves the CORRUPT cover's copy and its export.
                        val copyViews = copies.flatMapLatest { damaged ->
                            if (damaged == null) flowOf(DamagedCopyView.UNKNOWN to false) else combine(damaged.view, damaged.isExporterFlow) { v, e -> v to e }
                        }
                        combine(host.status, copyViews, reachable) { status, copy, _ -> Triple(status, copy.first, copy.second) }
                            .flatMapLatest { (status, copy, exporter) ->
                                flow {
                                    emit(snapshotOf(host, status, copy = copy, exporter = exporter))
                                    if (status.state in WAITING) {
                                        delay(tryAgainAfterMs)
                                        emit(snapshotOf(host, status, waitedLong = true, copy = copy, exporter = exporter))
                                    }
                                }
                            }
                            .onStart { host.observerAttached() }
                            .onCompletion { host.observerDetached() }
                    }
                }
                .distinctUntilChanged()
                .collect { snapshot ->
                    try {
                        onChange(snapshot)
                    } catch (t: Throwable) {
                        println("[storage-state] the observer threw: ${t.message}")
                    }
                }
        }
        return StorageStateObservation(job)
    }

    /**
     * B.17 (260), Q42: the not-ready surface's "Try again": the graph rebuilt over the store, off
     * the main thread then on it. False when there is nothing to try (the state moved on).
     */
    fun tryAgain(): Boolean = hosts.value?.tryAgain() ?: false

    /** The copy, in one table. Internal for the tests. */
    internal fun snapshotOf(
        host: SharedReplicaHost,
        status: SharedReplicaHostStatus,
        waitedLong: Boolean = false,
        copy: DamagedCopyView = DamagedCopyView.UNKNOWN,
        exporter: Boolean = false,
    ): StorageStateSnapshot {
        val name = status.state.name
        // ⚠ B.17 (278): a waiting cover offers "Try again" once it has been up [tryAgainAfterMs].
        val waitingTryAgain = waitedLong && host.canTryAgain
        fun cover(title: String, message: String, tryAgain: Boolean = waitingTryAgain) =
            StorageStateSnapshot(name, covers = true, title = title, message = message, canTryAgain = tryAgain, generation = status.generation)
        return when (status.state) {
            // Nothing to cover: the graph serves, or the app's own gate decides (B.17 (255)).
            SharedReplicaHostState.READY, SharedReplicaHostState.NO_GRAPH ->
                StorageStateSnapshot(name, covers = false, title = null, message = null, canTryAgain = false, generation = status.generation)
            SharedReplicaHostState.REFRESHING ->
                cover("Refreshing…", "Tenshu is reopening the storage on this phone. This takes a moment.")
                    .let { if (status.detail == TRYING_AGAIN) it.copy(title = TRYING_AGAIN) else it }
            SharedReplicaHostState.UPDATING ->
                if (status.wipe) {
                    cover("Refreshing…", "Tenshu is refreshing the storage on this phone. What has synced comes back from your other devices.")
                } else {
                    cover("Updating storage…", "Tenshu is updating the storage on this phone. This takes a moment.")
                }
            SharedReplicaHostState.CHECKING ->
                cover("Checking storage…", "Tenshu is checking the storage on this phone. This takes a moment.")
            SharedReplicaHostState.SKEW -> {
                // ⚠ No button: there is no platform route to the App Store page (core-schema-skew-surface.md).
                val app = appName()
                cover("Update $app", tryAgain = false, message = "Another Tenshu app on this phone has saved data in a newer format than this version of $app can read. Update $app to keep using it.")
            }
            SharedReplicaHostState.CORRUPT -> corruptCover(name, status.generation, copy, exporter)
            SharedReplicaHostState.NOT_READY ->
                if (host.canTryAgain) {
                    cover("Storage isn't ready", "Tenshu couldn't open the storage on this phone just now.", tryAgain = true)
                } else {
                    val app = appName()
                    cover("Storage unavailable", "Tenshu can't use the storage on this phone right now. Quit and reopen $app to try again.", tryAgain = false)
                }
        }
    }

    /** The copy that opens every "Storage damaged" cover: what is lost and what is safe (§6 step 1). */
    private const val DAMAGED_LEAD = "Tenshu can't read its storage on this phone. Everything that had synced is safe on your other devices, " +
        "but changes made on this phone that had not synced yet may be lost."

    /**
     * ⚠⚠ B.18 (Q47): **"Storage damaged", honestly.** It says what the copy's state is and promises
     * only what is built: the export only in Control and only over a complete copy; elsewhere it
     * names Control (the binary that owns the export, `core-schema-skew-surface.md` §3.1's rule)
     * and Swift offers "Open Control" only when Control can be reached.
     */
    private fun corruptCover(name: String, generation: Long, copy: DamagedCopyView, exporter: Boolean): StorageStateSnapshot {
        // ⚠ B.18.8 (317): asked only for another app's cover; Control never sends the user to itself.
        val reachable = !exporter && this@StorageStateSurface.reachable.value
        fun cover(message: String, canExport: Boolean = false) = StorageStateSnapshot(
            name, covers = true, title = "Storage damaged",
            message = if (exporter) "$DAMAGED_LEAD $message $RECOVERY" else "$DAMAGED_LEAD $message", canTryAgain = false, generation = generation,
            canExportDamagedData = canExport, damagedDataFiles = if (canExport) copy.files else emptyList(), offersOpenControl = reachable,
            canDeleteDamagedData = canExport,
        )
        if (!exporter) {
            return when {
                copy.phase == DamagedCopyPhase.DELETED ->
                    cover("The damaged data is still on this phone and has not been changed. Its copy was deleted in Tenshu Control.")
                copy.phase == DamagedCopyPhase.COMPLETE && reachable ->
                    cover("A copy of the damaged data has been kept on this phone. You can export it from Tenshu Control.")
                copy.phase == DamagedCopyPhase.COMPLETE ->
                    cover("A copy of the damaged data has been kept on this phone. Only Tenshu Control can export it, and it isn't installed on this phone.")
                reachable ->
                    cover("The damaged data is still on this phone and has not been changed. Open Tenshu Control to keep a copy of it that you can export.")
                else ->
                    cover("The damaged data is still on this phone and has not been changed. Only Tenshu Control can keep a copy of it to export, and it isn't installed on this phone.")
            }
        }
        return when (copy.phase) {
            DamagedCopyPhase.COMPLETE -> cover(
                "A copy of the damaged data has been kept on this phone. Export it before you delete any Tenshu app: deleting every Tenshu app deletes the copy too. " +
                    "The copy holds this phone's Tenshu data unencrypted, so keep it somewhere private.",
                canExport = copy.files.isNotEmpty(),
            )
            DamagedCopyPhase.COPYING -> cover("Control is keeping a copy of the damaged data. Keep Control open until it is done.")
            // ⭐ Q51: honest after a delete: the copy is gone, the damaged data itself is not.
            DamagedCopyPhase.DELETED -> cover("You deleted the copy of the damaged data. The damaged data itself is still on this phone and has not been changed.")
            DamagedCopyPhase.NO_ROOM -> cover(
                "There isn't enough free space on this phone to keep a copy of the damaged data (about ${megabytes(copy.neededBytes)} MB are needed). " +
                    "Free up some space, then open Control again. The damaged data is still on this phone and has not been changed.",
            )
            DamagedCopyPhase.INCONSISTENT -> cover(
                "The damaged data could not be copied consistently. It is still on this phone and has not been changed; Control tries again the next time you open it.",
            )
            DamagedCopyPhase.NOT_COPIED, DamagedCopyPhase.UNKNOWN -> cover(
                "The damaged data is still on this phone and has not been copied yet. Control tries again the next time you open it.",
            )
        }
    }

    private fun megabytes(bytes: Long): Long = (bytes + 1_048_575) / 1_048_576
}
