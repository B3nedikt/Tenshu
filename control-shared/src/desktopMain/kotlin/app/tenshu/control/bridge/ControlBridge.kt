package app.tenshu.control.bridge

import app.tenshu.control.data.SyncPauseState
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.lifecycle.WindowController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.DivergenceWarning
import org.tenshu.sync.proxy.SyncHealthHolder

/**
 * The authoritative sync state the tray renders (bgproc §5.3 tray surface,
 * `core-background-processing.md`). The bridge emits the **code**; the native
 * tray owns the localised strings + glyph (ADR 0016). Precedence, most-blocking
 * first — a lower state is only reported once every state above it is clear:
 *
 *  - [DISABLED]   sync is off — no realm set up yet.
 *  - [LOCKED]     the realm is locked; the user must unlock before sync runs.
 *  - [PAUSED]     the user paused sync from the tray (§5.3 "Paused").
 *  - [DIVERGENCE] the relay may be misreporting HLC (threat-model T-T-04) —
 *                 shown even while otherwise active, because it is a warning. ⚠ The caller
 *                 passes a **recency-bounded** flag ([divergenceIsFresh]), not the raw
 *                 presence of a warning; see that function for why.
 *  - [NEEDS_UPDATE] the schema pull fence holds: another device writes at a newer
 *                 schema, so this one has stopped pulling until Control is updated. Below
 *                 DIVERGENCE only because that headline is time-bounded and yields on its own;
 *                 above every state below it because it is durable and ⚠⚠ a fenced device keeps
 *                 pushing, so without it the tray reads SYNCED for as long as it is fenced.
 *  - [OFFLINE]    the relay was unreachable on the last attempt.
 *  - [SYNCING]    a drain/pull is in flight, or blocks are queued to push.
 *  - [SYNCED]     idle and up to date.
 */
enum class SyncStateCode {
    DISABLED, LOCKED, PAUSED, DIVERGENCE, NEEDS_UPDATE, OFFLINE, SYNCING, SYNCED
}

/**
 * How long a divergence warning stays the tray's **headline** state
 * (`sync-hlc-tip-device-identity.md` TC-1, step 3).
 *
 * ⚠⚠ This is a *surface* bound, not a detector change. The warning itself stays raised in
 * `SyncManager.divergenceWarning` and keeps riding the bridge payload as `divergenceWarning`;
 * past this window it simply stops outranking the state that describes what sync is actually
 * doing, and the tray demotes it to a secondary line.
 *
 * Why a bound is needed at all: the offending peer tip sits in the relay's single
 * `(ownerId, deviceId)` slot, so every poll re-reads it and the warning latches until that peer
 * publishes a tip past the pre-regression floor — which, on a device whose runtimes disagree
 * about the clock, can take hours and in practice ends only when the user happens to open an app
 * there. A permanent red state that no action clears is one users learn to read as "ignore me",
 * which costs more than the warning buys.
 *
 * 30 minutes is 60 poll intervals at the 30 s cadence: long enough that a live, re-confirmed
 * problem is never demoted while it is still developing, short enough that a stale one stops
 * being the only thing the tray says.
 */
const val DIVERGENCE_HEADLINE_WINDOW_MS: Long = 30 * 60 * 1000L

/**
 * Ceiling on the peer-name read in `getSyncState`. The tray polls that method, so an unbounded
 * read would trade a cosmetic label for a frozen status row — the name is the optional half here,
 * and losing it costs a UUID suffix instead of the whole refresh.
 */
private const val DEVICE_NAME_LOOKUP_TIMEOUT_MS: Long = 2_000L

/**
 * Whether a divergence warning first seen at [observedAtMillis] is recent enough to be the
 * headline sync state at [nowMs]. `null` (no warning) is never fresh.
 *
 * ⚠ Reads [observedAtMillis] as **first seen**, which is what `HlcDivergenceTracker` records —
 * a re-observation of the same evidence deliberately does not restamp it, or nothing could ever
 * age out. A stamp in the future (clock moved backwards between the two reads) counts as fresh:
 * treating it as ancient would hide a warning raised seconds ago.
 */
fun divergenceIsFresh(observedAtMillis: Long?, nowMs: Long): Boolean {
    if (observedAtMillis == null) return false
    if (observedAtMillis > nowMs) return true
    return nowMs - observedAtMillis <= DIVERGENCE_HEADLINE_WINDOW_MS
}

/**
 * Pure derivation of the tray's [SyncStateCode] from the coarse unlock/enablement
 * flags, the user [paused] switch, and the [SyncHealthHolder] figures. Extracted
 * so the precedence is unit tested without a live proxy (`SyncStateCodeTest`);
 * [registerControlMethods] only wires it to the daemon state.
 */
fun syncStateCode(
    enabled: Boolean,
    locked: Boolean,
    paused: Boolean,
    divergence: Boolean,
    schemaFenced: Boolean,
    online: Boolean,
    syncing: Boolean,
    pendingBlockCount: Int,
): SyncStateCode = when {
    !enabled -> SyncStateCode.DISABLED
    locked -> SyncStateCode.LOCKED
    paused -> SyncStateCode.PAUSED
    divergence -> SyncStateCode.DIVERGENCE
    schemaFenced -> SyncStateCode.NEEDS_UPDATE
    !online -> SyncStateCode.OFFLINE
    syncing || pendingBlockCount > 0 -> SyncStateCode.SYNCING
    else -> SyncStateCode.SYNCED
}

/**
 * Registers Control daemon's native-bridge methods on [server]
 * (docs/proposals/desktop-macos-integration.md §3).
 *
 * Lives in `:control-shared` rather than `:core-common` so the bridge handlers
 * can reach daemon state (`SyncManager` / `SyncHealthHolder`) — which `:core-common`
 * deliberately doesn't depend on. The handler speaks plain Kotlin maps; the
 * server owns the JSON mapping.
 *
 * Surface: `getSyncState`, `setSyncPaused` (`ping` is built into
 * [NativeBridgeServer]).
 */
fun NativeBridgeServer.registerControlMethods(
    koin: Koin,
    /** Wall clock for the divergence recency bound; injectable so tests need no live proxy. */
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val syncManager = koin.get<SyncManager>()
    val syncHealthHolder = koin.get<SyncHealthHolder>()
    // Peer *name* for a divergence warning. Resolved here rather than carried on
    // `DivergenceWarning` on purpose: a security-detector type has no business holding
    // presentation data, and the tray cannot name a device from an opaque UUID.
    //
    // ⚠ Only ever called when a warning exists, which is rare — `getSyncState` is on the tray's
    // poll path and this reads through the proxy. ⚠ Bounded and total: a slow or missing
    // repository degrades to the short device id, never to a hung tray refresh.
    val deviceNameLookup: (String) -> String? = lookup@{ deviceId ->
        val repository = koin.getOrNull<SyncRepository>() ?: return@lookup null
        runCatching {
            runBlocking {
                withTimeoutOrNull(DEVICE_NAME_LOOKUP_TIMEOUT_MS) {
                    repository.devices.first()
                        .firstOrNull { it.deviceId.get() == deviceId }
                        ?.name?.get()
                }
            }
        }.getOrNull()
    }
    // The shared user pause switch (registered by control-desktop Main.kt). Same
    // instance the proxy's syncGate reads, so a tray toggle gates the scheduler.
    // getOrNull: a bridge wired without the desktop app-layer registration just
    // reports never-paused.
    // ⚠⚠ Resolved on EVERY call, never once here: Main.kt registers these methods before it loads the
    // SyncPauseState single, so a lookup at registration time was null forever — setSyncPaused did
    // nothing and getSyncState always said "not paused" (found on Windows, 2026-11-10; the macOS tray
    // shares the path). Pinned by ControlBridgePauseTest.
    fun pauseState(): SyncPauseState? = koin.getOrNull<SyncPauseState>()

    register("getSyncState") { _ ->
        val unlock = syncManager.getUnlockStatus()
        val enabled = syncManager.isEnabled()
        val health = syncHealthHolder.current()
        val paused = pauseState()?.isPaused() ?: false
        // divergenceWarning is a StateFlow<Any?>; non-null == the relay may be
        // misreporting HLC (threat-model T-T-04). The concrete type is proxy-side, which this
        // module can see — `:core-common` deliberately cannot, which is why the flow is `Any?`.
        val warning = syncManager.divergenceWarning.value as? DivergenceWarning
        val divergence = warning != null
        // ⚠ The *state code* gets the recency-bounded flag, the payload keeps the raw one. A
        // warning that has not been re-derived from new evidence in DIVERGENCE_HEADLINE_WINDOW_MS
        // stops being the headline, but never stops being reported — the tray demotes it to a
        // detail line rather than dropping it, so demotion loses no information.
        val divergenceFresh = divergenceIsFresh(warning?.observedAtMillis, nowMs())
        // The fence verdict from this process's own scheduler. `null` (not yet evaluated)
        // reads as not fenced for the state code — the tray must not demand an update on a guess.
        val fencedServiceIds = syncHealthHolder.observeSchemaFence().value
        val schemaFenced = !fencedServiceIds.isNullOrEmpty()
        // The authoritative bgproc §5.3 state code the tray renders directly; the
        // granular fields below let other surfaces refine without another round-trip.
        val state = syncStateCode(
            enabled = enabled,
            locked = unlock.isLocked,
            paused = paused,
            divergence = divergenceFresh,
            schemaFenced = schemaFenced,
            online = health.online,
            syncing = health.isSyncing,
            pendingBlockCount = health.pendingBlockCount,
        )
        mapOf(
            "state" to state.name,
            "enabled" to enabled,
            "setUp" to unlock.isSetUp,
            "locked" to unlock.isLocked,
            "paused" to paused,
            "online" to health.online,
            "syncing" to health.isSyncing,
            "pendingBlockCount" to health.pendingBlockCount,
            "lastSyncTimestamp" to syncManager.lastSyncTimestamp,
            "deviceId" to syncManager.currentDeviceId,
            "ownerId" to syncManager.currentOwnerId,
            "divergenceWarning" to divergence,
            // TC-1 step 2: the detail the surface needs to say something true — *which* peer and
            // *when*, which the bare boolean above cannot carry.
            "divergenceFresh" to divergenceFresh,
            "divergenceKind" to when (warning) {
                is DivergenceWarning.Regression -> "REGRESSION"
                is DivergenceWarning.SignatureMismatch -> "SIGNATURE_MISMATCH"
                null -> null
            },
            "divergencePeerDeviceId" to warning?.peerDeviceId,
            "divergencePeerName" to warning?.peerDeviceId?.let(deviceNameLookup),
            "divergenceObservedAtMillis" to warning?.observedAtMillis,
            // Drives the tray's "Update Control" notification, which must fire even while
            // a higher state (a fresh divergence) holds the headline. ⚠ Tri-state on purpose:
            // `null` = not yet evaluated (a daemon that just restarted), which the tray must not
            // read as "cleared" — that would withdraw the alert and re-post it on every restart.
            // The ids are diagnostics only: the copy names Control, never a service (§4).
            "schemaFenced" to fencedServiceIds?.isNotEmpty(),
            "schemaFencedServiceIds" to fencedServiceIds.orEmpty().joinToString(","),
        )
    }

    // The tray's Pause/Resume-sync action (§5.3). Sets the shared device-local
    // pause switch, which the in-process scheduler's gate honours on its next
    // tick. `paused` param defaults to true so a bare call pauses.
    register("setSyncPaused") { params ->
        val paused = params["paused"]?.toBooleanStrictOrNull() ?: true
        val state = pauseState()
        state?.setPaused(paused)
        mapOf("ok" to true, "paused" to (state?.isPaused() ?: false))
    }
}

/**
 * Registers the window-lifecycle bridge methods backed by
 * [windowController]. Separate from [registerControlMethods] because the
 * controller lives in the desktop entry point (`Main.kt`), not in Koin.
 *
 *  - `openControlWindow` — the tray's *Open Control* ([WindowController.show]).
 *  - `quitApp` — the tray's *Quit* ([WindowController.requestQuit]).
 */
fun NativeBridgeServer.registerWindowMethods(windowController: WindowController) {
    register("openControlWindow") {
        windowController.show()
        mapOf("ok" to true)
    }
    register("quitApp") {
        windowController.requestQuit()
        mapOf("ok" to true)
    }
}
