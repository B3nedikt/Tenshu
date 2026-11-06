package app.tenshu.home.data

import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.common.TenshuClient
import org.tenshu.home.device.client.homeDeviceService

/**
 * Home's **one** Control-reachability engine (`ADR 0025`; the `fit-app.md` §9.14 recipe).
 *
 * ⚠⚠ Home reached this late, and for a reason worth recording: its screens looked *populated*
 * without Control rather than empty. `HomeDevicesRepository` was a chain whose base delegate was
 * `MockHomeDevicesRepository()`, so an absent proxy produced a fabricated household — "Living room ·
 * Connected", "Sofa lamp · On · 70%" — indistinguishable from real device state. An app that shows
 * a lamp as on when it cannot reach anything is worse than one that shows nothing, and it is
 * exactly the "fakes are opt-in, never a default" rule the Control-on-iOS work already wrote down.
 *
 * ⚠ The probe is a one-shot device-row read rather than one of the `*Flow` sources: the flows are
 * long-lived subscriptions the screens already hold, and a probe must be a bounded round trip it
 * can lose without disturbing them.
 *
 * [probeScope] `null` (tests, previews) disables the probe; the signal then tracks only the
 * transport edge and organic outcomes.
 */
fun homeControlReachability(
    client: TenshuClient,
    probeScope: CoroutineScope?,
    probeIntervalMs: Long = 5_000,
    /**
     * The schema-skew verdict (`core-schema-skew-surface.md` SK-1b), so the shell can say *update
     * Control* rather than *can't reach Control* when the proxy is answering and refusing.
     */
    schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * The realm schema pull fence (SK-3) and the proxy's published host identity (SK-2).
     * ⚠⚠ Absent, a fenced device renders nothing at all — it answers every read and looks healthy
     * while silently receiving nothing.
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
            client.homeDeviceService.homeDevices.getAll() is TResult.Success<*>
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
    // ADR 0025: lets the shell say "Control isn't installed" instead of "open Control and unlock
    // it" — an instruction naming an app that is not on the device.
    controlInstalled = { client.controlInstalled() },
    // One event per call the proxy served, used to WAKE the probe rather than to answer for it —
    // see `ControlReachability.answered`. This is what replaced the repositories' hand-written
    // `markReachable` calls.
    answered = client.proxyAnswered,
    controlHasRealm = controlHasRealm,
)
