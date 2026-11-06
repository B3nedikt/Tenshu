package org.tenshu.sdk.ios

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import org.tenshu.core.common.TenshuClient
import org.tenshu.sync.client.catchUpOnThisStore
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.SyncActivity
import org.tenshu.sync.common.UploadInput
import org.tenshu.sync.common.debounceSyncActivity
import org.tenshu.sync.common.evaluateSyncActivity
import org.tenshu.sync.proxy.SyncCatchUp

/**
 * The sync-activity capsule for **first-party iOS apps** (`ios-sync-activity-indicator.md` §3–§4):
 * one small "Syncing…" while a pull is bringing data in, nothing when idle.
 *
 * ## Kotlin decides, Swift renders
 *
 * The rule is [evaluateSyncActivity] and the timing is [debounceSyncActivity], both in
 * `:sync-service-common` with their own tests. What crosses to Swift is a **finished string**, as
 * [RealmStorageSurface] does it and for its reason: a sealed hierarchy exported through the
 * umbrella is the shape that goes wrong quietly, and a `String?` cannot.
 *
 * ## It reads this runtime's own row, or any writer's live heartbeat
 *
 * `SyncService.catchUpOnThisStore(hostAppId)` (`ios-sync-activity-indicator.md` §12): this
 * runtime's own row, exactly as before, **or** any app's `SyncCatchUpHeartbeat` younger than its
 * staleness bound. On the shared replica that is how Photos says "Syncing…" while Control or Drive
 * does the pull. A killed sibling's row stops counting by itself; the reader holds one timer only
 * while some row is fresh. The copy does not name the app that is pulling.
 *
 * ⚠ On the SQLite shared replica a sibling's write reaches no listener in this process: a sibling's
 * beat is seen by a re-read (every read goes to the store), and ages out unless a later re-read
 * finds a newer one; the own row is unaffected (`ios-sync-activity-indicator.md` §12.3).
 *
 * ## It never holds a last value
 *
 * `null` with no installed runtime, `null` when the row says idle. The Swift model clears its own
 * copy when the flow ends and whenever the scene is not active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object SyncActivitySurface {

    private class Source(val client: TenshuClient, val hostAppId: String)

    /** A flow, not a field, for the reason [RealmStorageSurface] records at its `clientFlow`. */
    private val source = MutableStateFlow<Source?>(null)

    /**
     * Install the runtime's client and the id its scheduler writes its row under. Called once per
     * app from `FirstPartyLocalRuntime.assembleBase`, beside [RealmStorageSurface.install].
     */
    fun install(client: TenshuClient, hostAppId: String) {
        source.value = Source(client, hostAppId)
    }

    /**
     * What this process is sending, for the one app that sends (`ios-sync-activity-indicator.md`
     * §6a.1). A flow of flows for the reason [source] is one: the capsule may be observing before
     * the runtime that owns the figures exists.
     *
     * ⚠⚠ In-process figures, never a store row: a backup run's count and the background session's
     * live transfers are facts about the process that owns them. An app that installs nothing here
     * never says "Uploading".
     */
    private val uploads = MutableStateFlow<Flow<UploadInput>?>(null)

    /** Install this app's upload figures. Drive only, from `DriveLocalRuntime`. */
    fun installUploads(input: Flow<UploadInput>) {
        uploads.value = input
    }

    /** The capsule's one line, or `null` when there is nothing to show. */
    fun observeLine(): Flow<String?> = observeActivity().map(::line).distinctUntilChanged()

    /**
     * What VoiceOver reads for the capsule, or `null`. ⚠ Never carries a count: the element
     * updates frequently, and announcing every number would talk over the user.
     */
    fun observeAccessibilityLabel(): Flow<String?> =
        observeActivity().map(::accessibilityLabel).distinctUntilChanged()

    /**
     * The copy, in one table, as [ErrorCopySurface] keeps its own. No Swift literal anywhere.
     *
     * ⚠ "Syncing…", never "Syncing photos…": the pull is realm-wide — each app pulls the whole
     * realm — so naming one kind of data would be a guess.
     * ⚠ Not localised, as everything on this bridge is not; the table is the seam.
     */
    internal fun line(activity: SyncActivity): String? = when (activity) {
        SyncActivity.Idle -> null
        SyncActivity.CatchingUp -> "Syncing…"
        is SyncActivity.Uploading -> activity.count
            ?.let { "Uploading ${it.completed} of ${it.total}" }
            ?: "Uploading…"
    }

    internal fun accessibilityLabel(activity: SyncActivity): String? = when (activity) {
        SyncActivity.Idle -> null
        SyncActivity.CatchingUp -> "Syncing with your other devices"
        is SyncActivity.Uploading -> "Uploading to your other devices"
    }

    private fun observeActivity(): Flow<SyncActivity> {
        // Read at call time on purpose: the harness is installed before any view exists.
        harness?.let { script ->
            return script
                .takeWhile { !it.ended }
                .map { evaluateSyncActivity(catchingUp = it.catchingUp, upload = it.upload) }
                .debounceSyncActivity()
        }
        // No runtime yet ⇒ no pull ⇒ silence, and the flow switches the moment one is installed.
        val catchingUp = source.flatMapLatest { installed ->
            installed?.client?.syncService?.catchUpOnThisStore(installed.hostAppId) ?: flowOf(false)
        }
        val uploading = uploads.flatMapLatest { it ?: flowOf(UploadInput.None) }
        return combine(catchingUp, uploading) { pull, upload -> evaluateSyncActivity(catchingUp = pull, upload = upload) }
            .debounceSyncActivity()
    }

    // ---- DEV_HARNESS -------------------------------------------------------------------------
    //
    // The simulator has no seeded relay, so the capsule is driven by a script instead
    // (`ios-sync-activity-indicator.md` §6). The script replaces the INPUT only: the evaluator,
    // the debounce and the copy above are the shipping ones. Called from `#if DEV_HARNESS` Swift.

    private data class Scripted(
        val catchingUp: Boolean = false,
        val upload: UploadInput = UploadInput.None,
        val ended: Boolean = false,
    )

    private var harness: MutableStateFlow<Scripted>? = null

    /** Replace the runtime's inputs with scripted ones. Call before any view observes. */
    fun installHarness() {
        if (harness == null) harness = MutableStateFlow(Scripted())
    }

    /** The scripted input: a pull is, or is not, bringing data in. */
    fun harnessSetCatchingUp(catchingUp: Boolean) {
        harness?.let { it.value = it.value.copy(catchingUp = catchingUp) }
    }

    /**
     * The scripted upload input: a run's file in hand ([total] `0` = no run counting) and the
     * transfers still with the background session.
     */
    fun harnessSetUpload(completed: Int, total: Int, transfersInFlight: Int) {
        val run = if (total > 0) SyncActivity.Progress(completed, total) else null
        harness?.let { it.value = it.value.copy(upload = UploadInput(run, transfersInFlight)) }
    }

    /** End the scripted flow, as a torn-down runtime would: every observer's loop finishes. */
    fun harnessEndFlow() {
        harness?.let { it.value = it.value.copy(ended = true) }
    }

    /**
     * How many collectors the scripted input has right now. One mounted capsule holds two (its
     * line and its label), so this is how a harness sees a collector that outlived its view.
     */
    fun harnessCollectors(): Int = harness?.subscriptionCount?.value ?: 0

    /**
     * This runtime's real catch-up signal, for [debugPulseRow]. Set by `assembleBase`; resolved
     * lazily because the sync runtime is elected by the first session, after assembly.
     */
    internal var debugCatchUp: (() -> SyncCatchUp?)? = null

    /**
     * Write this runtime's REAL status row `true` then `false`, [times] over, [gapMs] apart —
     * the measurement of whether a status-row write wakes an unrelated observer such as the feed
     * (`ios-sync-activity-indicator.md` §8). Returns the number of pulses written, `0` with no
     * runtime. DEV_HARNESS only; a real pull is not involved.
     */
    suspend fun debugPulseRow(times: Int, gapMs: Long): Int {
        val catchUp = debugCatchUp?.invoke() ?: return 0
        repeat(times) {
            catchUp.raise()
            delay(gapMs)
            catchUp.settle(false)
            delay(gapMs)
        }
        return times
    }
}
