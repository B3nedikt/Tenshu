package app.tenshu.drive.data

import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.take
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.file.client.fileService

/**
 * Drive's **one** Control-reachability engine (`ADR 0025`; the `fit-app.md` §9.14 recipe).
 *
 * ## ⚠⚠ Why Drive reached this last, having had a banner all along
 *
 * Drive's `SyncStatusBanner` predates the shared vocabulary and grew its own: `Disconnected` is
 * `UnreachableReason.Transport` under another name, and `NeedsUpdate` is `Fenced`. Two cases were
 * simply absent, and both are defects rather than gaps in tidiness:
 *
 * - **`Skew`** — Drive called `wireSchemaAnnounce` and **discarded the announcer**, exactly as
 *   Calendar, Photos and Home did before their own passes. So a proxy that answers and *refuses* on
 *   a schema version left `proxyConnected` true, no storage figures and no health row — and
 *   `SyncBanner.evaluate` falls through both `null` guards to **no banner at all**. Silent, in the
 *   app whose whole job is showing you your files.
 * - **`NotInstalled`** — rendered as `Disconnected`, whose own KDoc says "informational, no action".
 *
 * ⚠ The other eight Drive banner states are **not** part of this vocabulary and stay where they
 * are: `Locked`, `DeviceUnrecognized`, `NeedsAttention`, `Deferred`, `Offline` and `Syncing` are
 * facts about the *relay and the queue*, which no other app has. Drive is not an app that failed to
 * follow the pattern; it is one carrying a Control surface and a sync surface in a single banner.
 *
 * ⚠ The probe is a one-row, one-field listing — the cheapest round trip that proves the proxy is
 * answering, used only by the recovery loop and never on a healthy path.
 */
fun driveControlReachability(
    client: TenshuClient,
    probeScope: CoroutineScope?,
    probeIntervalMs: Long = 5_000,
    /**
     * The schema-skew verdict (`core-schema-skew-surface.md` SK-1b) — the case Drive never had.
     */
    schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * The realm schema pull fence (SK-3) and the proxy's published host identity (SK-2).
     *
     * ⚠ Drive already surfaces the fence as `SyncBannerState.NeedsUpdate`, derived from its own
     * `schemaFence()` stream, so this stays `null` by default: two paths to one banner state would
     * be two sources of truth for one fact, and whichever disagreed would be invisible.
     */
    schemaFence: StateFlow<List<String>>? = null,
    proxyHost: StateFlow<ProxyHost?>? = null,
    /**
     * Whether Control's device has a realm (ADR 0025 §Decision 8) — wired on Android/desktop to
     * `syncService.getSyncStatus()`. ⚠ Absent (iOS route-1, tests, previews), `NotSetUp` never
     * fires.
     */
    controlHasRealm: app.tenshu.reachability.RealmProbe,
): ControlReachability = ControlReachability(
    probe = {
        try {
            client.fileService.defaultFileSystem.root.files
                .take(1)
                .select { fields(File.Field.PATH) }
                .getAll()
                .fold(onSuccess = { true }, onFailure = { false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead dispatch may throw rather than fold.
            false
        }
    },
    scope = probeScope,
    probeIntervalMs = probeIntervalMs,
    // R4's transport signal: reports the outage instantly instead of after a probe interval, and
    // suppresses probing across a channel that cannot carry one.
    transportConnected = client.proxyConnected,
    schemaSkew = schemaSkew,
    schemaFence = schemaFence,
    proxyHost = proxyHost,
    // ADR 0025: lets the shell say "Control isn't installed" instead of "Control is unreachable",
    // which is informational and carries no route out.
    controlInstalled = { client.controlInstalled() },
    // One event per call the proxy served, used to WAKE the probe rather than to answer for it —
    // see `ControlReachability.answered`. This is what replaced the repositories' hand-written
    // `markReachable` calls.
    answered = client.proxyAnswered,
    controlHasRealm = controlHasRealm,
)
