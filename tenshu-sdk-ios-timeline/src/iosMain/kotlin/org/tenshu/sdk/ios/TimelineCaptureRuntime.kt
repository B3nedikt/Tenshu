package org.tenshu.sdk.ios

import app.tenshu.timeline.capture.TimelineCaptureControl
import app.tenshu.timeline.data.capture.IosLocationCapture
import app.tenshu.timeline.data.capture.IosTimelineCaptureControl
import app.tenshu.timeline.data.capture.restoreOngoingMove
import app.tenshu.timeline.data.capture.stopParkedLocationMonitoring
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext

/**
 * The Swift-facing **live-capture runtime** (M4, `docs/proposals/timeline-movement-capture.md` §6):
 * owns the persisted opt-in ([IosTimelineCaptureControl]) and enacts it by running
 * [IosLocationCapture] over the route-1 in-process runtime's client — Timeline's iOS twin of
 * Android's `MainActivity`-toggle + capture-foreground-service pair, minus the service (iOS's
 * `CLVisit`/significant-change monitors relaunch the app per event instead of holding a process).
 *
 * Capture is meaningful only on the **live** runtime (the writers need the real store), so
 * [isCaptureAvailable] gates the Settings toggle on [TimelineLocalRuntime] being installed — the
 * mock tier has nowhere to write. The Swift shell calls [startIfEnabled] at every launch,
 * *including* the background relaunch a location event triggers, so the woken process re-creates
 * its `CLLocationManager` and actually receives the event that woke it (Apple's re-arm contract).
 *
 * A denied/restricted authorization resets the opt-in flag (Android parity: the platform reflects
 * a denied permission back), so the toggle honestly shows capture as off.
 */
object TimelineCaptureRuntime {

    /** The capture's three calls, behind a seam for the tests (B.16 (228)). */
    internal interface Capture {
        fun start()
        fun stop()
        fun detach()
    }

    private class LiveCapture(private val capture: IosLocationCapture) : Capture {
        override fun start() = capture.start()
        override fun stop() = capture.stop()
        override fun detach() = capture.detach()
    }

    private val control: TimelineCaptureControl = IosTimelineCaptureControl()

    /** Whether the user opted in. A test seam; production reads the persisted control. */
    internal var enabled: () -> Boolean = { control.enabled.value }

    /** The installed runtime capture runs over. A test seam. */
    internal var currentAssembly: () -> TimelineLocalRuntime.Assembly? = { TimelineLocalRuntime.assemblyOrNull() }

    /** Builds the capture over an assembly. A test seam; production is [IosLocationCapture]. */
    internal var factory: (TimelineLocalRuntime.Assembly) -> Capture = ::liveCapture

    @kotlin.concurrent.Volatile
    private var active: Capture? = null

    /** The client [active] writes through: what [detachIfOver] compares (B.16 (228)). */
    @kotlin.concurrent.Volatile
    private var activeClient: TenshuClient? = null

    /** Whether capture can run at all — the route-1 live runtime is installed. Gates the toggle. */
    fun isCaptureAvailable(): Boolean = TimelineLocalRuntime.isInstalled()

    /** The persisted opt-in (threat-model A35(a)) — off by default. */
    fun isCaptureEnabled(): Boolean = control.enabled.value

    /** Record the user's choice and enact it (start monitoring / stop + flush). */
    fun setCaptureEnabled(enabled: Boolean) {
        control.setEnabled(enabled)
        if (enabled) startIfEnabled() else stopCapture()
    }

    /**
     * Start monitoring iff opted in and the live runtime is up. Idempotent — the launch path and
     * the Settings toggle both call it. First start on a fresh grant requests Always authorization
     * (the toggle tap is the consent moment).
     */
    fun startIfEnabled() {
        if (!enabled()) {
            // ⚠⚠ (B.16 (238)) Off means off: a manager a detached capture parked is stopped too.
            releaseParked()
            return
        }
        val assembly = currentAssembly() ?: return
        // ⚠ A capture over another graph than the installed one is
        // stale whatever stopped it, or failed to: it is detached and replaced, never kept.
        val running = active
        if (running != null && activeClient === assembly.client.tenshuClient) return
        running?.detach()
        active = factory(assembly).also { it.start() }
        activeClient = assembly.client.tenshuClient
    }

    /**
     * ⚠⚠ S0b-3 (B.16 (228)): the graph whose client [client] is was stopped by the shared store's
     * host. A capture over it is detached: its delegate and worker go, no session end is written
     * through the stopped graph, and its manager is **parked, still monitoring**, so iOS's
     * relaunch registration survives (233) until the rebuild's [startIfEnabled] starts one over the
     * new graph, or until nothing will ([abandonParked], [stopCapture]: (238)). A capture over
     * another graph is left alone.
     */
    internal fun detachIfOver(client: TenshuClient) {
        val current = active ?: return
        if (activeClient !== client) return
        current.detach()
        active = null
        activeClient = null
    }

    private fun liveCapture(assembly: TimelineLocalRuntime.Assembly): Capture {
        // Republish the journey a previous process left open (§12.6). iOS needs this on *every*
        // launch, not just a crash recovery: the monitors relaunch the app per location event and
        // suspend it again in between, so the live state is empty on every wake while the durable
        // journal still holds the leg. Guards (recordable, fresh, never overwrite live) are inside.
        restoreOngoingMove()
        return LiveCapture(IosLocationCapture(
            client = assembly.client.tenshuClient,
            context = assembly.koin.get<TContext>(),
            onAuthorizationDenied = {
                control.setEnabled(false)
                stopCapture()
            },
            // ⚠ The runtime's own stack, not a fresh one: the rename binding, the Settings status
            // line and this capture path must share one store and one `WifiPlaceFingerprint`
            // state, or Settings would report a second facade's separate opinion of what capture
            // just did. Its own consent check makes passing it unconditionally safe — an opted-out
            // user's radio is never read.
            wifi = assembly.wifi,
            // ⚠⚠ S0b (B.15 (210)): each burst of location events' writes under ONE entry token, so
            // the gate admits them in the background and the token's expiry ends them. Unwrapped
            // with the switch off.
            entry = { drain -> SharedReplicaEntries.withProtection("timeline-capture") { drain() } },
        ))
    }

    /** Stop monitoring and flush the open movement tail. */
    fun stopCapture() {
        active?.stop()
        active = null
        activeClient = null
        // ⚠⚠ (B.16 (238), a privacy matter) With no live capture, a
        // manager a detached capture parked would go on monitoring after the user turned capture
        // off: stopped and released here whatever else is running, never through a graph.
        releaseParked()
    }

    /** Stops and releases a parked manager (B.16 (238)). A test seam; production is the capture's. */
    internal var releaseParked: () -> Unit = { stopParkedLocationMonitoring() }

    /**
     * B.16 (238): the graph [client] belongs to is given up with no rebuild to follow (a corrupt
     * store, a latched process, a refused probe, a deprovision): the manager its capture parked has
     * no successor to hand the registration to, and is stopped.
     */
    internal fun abandonParked() {
        releaseParked()
    }
}
